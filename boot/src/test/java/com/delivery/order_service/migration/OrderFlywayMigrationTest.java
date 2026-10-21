package com.delivery.order_service.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderFlywayMigrationTest {

    @Test
    void cleanSchemaCreatesAggregateHistoryAndQueryIndexes() throws Exception {
        String url = databaseUrl("clean");
        migrate(url);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long orderId = insertOrder(statement, 10);
            insertItem(statement, orderId);
            assertThat(count(statement, "SELECT count(*) FROM order_items")).isEqualTo(1);
            assertThat(indexExists(connection, "idx_orders_user_created")).isTrue();
            assertThat(indexExists(connection, "idx_orders_restaurant_creator_created")).isTrue();
            assertThat(indexExists(connection, "idx_orders_created_id")).isTrue();
            assertThat(indexExists(connection, "idx_order_items_order_id")).isTrue();
            assertThat(indexExists(connection, "uk_orders_inventory_reservation")).isTrue();

            long anotherOrderId = insertOrder(statement, 11);
            UUID reservationId = UUID.randomUUID();
            statement.executeUpdate("UPDATE orders SET inventory_reservation_id = '" + reservationId
                    + "' WHERE id = " + orderId);
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE orders SET inventory_reservation_id = '" + reservationId
                            + "' WHERE id = " + anotherOrderId))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("Unique");
        }
    }

    @Test
    void legacySchemaPreservesAggregateAndItem() throws Exception {
        String url = databaseUrl("legacy");
        createLegacySchema(url, false, false, false);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long orderId = insertOrder(statement, 10);
            insertItem(statement, orderId);
        }

        migrate(url);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT count(*) FROM orders")).isEqualTo(1);
            assertThat(count(statement, "SELECT count(*) FROM order_items")).isEqualTo(1);
        }
    }

    @Test
    void legacyVersionOneSchemaMissingFlashSaleItemIdIsRepairedByVersionFour() throws Exception {
        String url = databaseUrl("legacy_missing_flash_sale_item_id");
        createLegacySchema(url, false, false, true);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long orderId = insertOrder(statement, 10);
            insertItem(statement, orderId);
        }

        migrate(url);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(columnExists(connection, "order_items", "flash_sale_item_id")).isTrue();
            assertThat(count(statement, "SELECT count(*) FROM order_items")).isEqualTo(1);
        }
    }

    @Test
    void missingCoreColumnStopsMigrationInsteadOfGuessingOrderData() throws Exception {
        String url = databaseUrl("missing_core");
        createLegacySchema(url, true, false, false);

        assertThatThrownBy(() -> migrate(url))
                .isInstanceOf(FlywayException.class)
                .rootCause()
                .hasMessageContaining("missing core column")
                .hasMessageContaining("status");
    }

    @Test
    void incompleteLegacyTablePairStopsMigration() throws Exception {
        String url = databaseUrl("incomplete");
        createLegacySchema(url, false, true, false);

        assertThatThrownBy(() -> migrate(url))
                .isInstanceOf(FlywayException.class)
                .rootCause()
                .hasMessageContaining("schema is incomplete");
    }

    private void migrate(String url) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();
    }

    private void createLegacySchema(String url, boolean omitStatus, boolean omitItems) throws Exception {
        createLegacySchema(url, omitStatus, omitItems, false);
    }

    private void createLegacySchema(String url, boolean omitStatus, boolean omitItems,
                                    boolean omitFlashSaleItemId) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE orders (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        restaurant_id BIGINT NOT NULL,
                        shipper_id BIGINT,
                        subtotal_price DECIMAL(12,2),
                        discount_amount DECIMAL(12,2),
                        shipping_fee DECIMAL(12,2),
                        total_price DECIMAL(12,2),
                        %s
                        payment_method VARCHAR(255),
                        delivery_address TEXT,
                        delivery_lat DOUBLE PRECISION,
                        delivery_lng DOUBLE PRECISION,
                        pickup_lat DOUBLE PRECISION,
                        pickup_lng DOUBLE PRECISION,
                        restaurant_name VARCHAR(255),
                        restaurant_address VARCHAR(255),
                        restaurant_phone VARCHAR(255),
                        customer_name VARCHAR(255),
                        customer_phone VARCHAR(255),
                        notes TEXT,
                        cancel_reason TEXT,
                        cancelled_by BIGINT,
                        created_at TIMESTAMP,
                        updated_at TIMESTAMP,
                        creator_id BIGINT NOT NULL
                    )
                    """.formatted(omitStatus ? "" : "status VARCHAR(32) NOT NULL,"));
            if (!omitItems) {
                statement.execute("""
                        CREATE TABLE order_items (
                            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                            order_id BIGINT NOT NULL,
                            menu_item_id BIGINT NOT NULL,
                            %s
                            menu_item_name VARCHAR(255) NOT NULL,
                            quantity INTEGER NOT NULL,
                            price DECIMAL(12,2) NOT NULL,
                            notes VARCHAR(255)
                        )
                        """.formatted(omitFlashSaleItemId ? "" : "flash_sale_item_id BIGINT,"));
            }
        }
    }

    private long insertOrder(Statement statement, long userId) throws Exception {
        statement.executeUpdate("""
                INSERT INTO orders (user_id, restaurant_id, status, creator_id, created_at, updated_at)
                VALUES (%d, 20, 'PENDING', %d, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(userId, userId), Statement.RETURN_GENERATED_KEYS);
        try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
            assertThat(generatedKeys.next()).isTrue();
            return generatedKeys.getLong(1);
        }
    }

    private void insertItem(Statement statement, long orderId) throws Exception {
        statement.executeUpdate("""
                INSERT INTO order_items (order_id, menu_item_id, menu_item_name, quantity, price)
                VALUES (%d, 30, 'Pho', 1, 50000)
                """.formatted(orderId));
    }

    private boolean indexExists(Connection connection, String expectedName) throws Exception {
        for (String table : new String[]{"orders", "order_items"}) {
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    if (name != null && expectedName.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private long count(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
            return columns.next();
        }
    }

    private String databaseUrl(String label) {
        return "jdbc:h2:mem:order_migration_" + label + "_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }
}
