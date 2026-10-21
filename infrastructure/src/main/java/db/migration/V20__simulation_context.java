package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Additive execution boundary after all pre-existing order migrations. */
public class V20__simulation_context extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE orders ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(16) NOT NULL DEFAULT 'REAL'");
            statement.execute("ALTER TABLE orders ADD COLUMN IF NOT EXISTS simulation_run_id UUID");
            statement.execute("ALTER TABLE orders ADD COLUMN IF NOT EXISTS simulation_cohort_id UUID");
            statement.execute("ALTER TABLE orders ADD COLUMN IF NOT EXISTS simulation_binding_version BIGINT");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_orders_simulation_run ON orders (simulation_run_id, created_at)");
        }
    }
}
