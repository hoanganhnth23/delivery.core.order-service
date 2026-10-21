package com.delivery.order_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.delivery.order_service.OrderServiceApplication;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderStatus;
import com.delivery.order_service.repository.OrderRepository;
import com.delivery.order_service.repository.SagaCommandReceiptRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises the actual Saga-to-Order Kafka listener with PostgreSQL. Separate
 * JVM application contexts emulate two Order replicas in one consumer group;
 * the receipt makes duplicate partitions and a fresh-group replay converge to
 * one transition, while contradictory reuse is failed closed into Order's DLT.
 */
@SpringBootTest(classes = OrderServiceApplication.class, properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        // The shared fallback defaults to .DLT; this owner-DLT fixture opts in
        // explicitly, preserving the production .DLT vs .order.DLT discrepancy.
        "platform.kafka.dlt.suffix=.order.DLT",
        "app.outbox.relay-enabled=false",
        "spring.task.scheduling.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "app.order.payment-event-processing-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class SagaOrderKafkaPostgresIntegrationTest {

    private static final String TOPIC = "saga.command.update-order-status.order-inbox-proof";
    private static final String DLT_TOPIC = TOPIC + ".order.DLT";
    private static final String REPLICA_GROUP = "order-saga-inbox-replicas";
    private static final String REPLAY_GROUP = "order-saga-inbox-replay";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.8.0"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_saga_kafka")
            .withUsername("order")
            .withPassword("order");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("app.kafka.input-topics.saga-update-order-status", () -> TOPIC);
        registry.add("spring.kafka.consumer.group-id", () -> REPLICA_GROUP);
    }

    @Autowired private OrderRepository orderRepository;
    @Autowired private SagaCommandReceiptRepository receiptRepository;
    private final ObjectMapper wireMapper = JacksonUtils.enhancedObjectMapper();
    private DefaultKafkaProducerFactory<String, Object> relayProducerFactory;
    private KafkaTemplate<String, Object> relayKafka;
    @Autowired private KafkaListenerEndpointRegistry primaryListenerRegistry;

    private final List<ConfigurableApplicationContext> replicas = new ArrayList<>();

    @BeforeEach
    void prepareBoundary() throws Exception {
        relayProducerFactory = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class));
        relayKafka = new KafkaTemplate<>(relayProducerFactory);
        closeReplicas();
        stopPrimaryListeners();
        receiptRepository.deleteAll();
        orderRepository.deleteAll();
        createRequiredTopics();
    }

    @AfterEach
    void stopReplicas() {
        if (relayProducerFactory != null) relayProducerFactory.destroy();
        closeReplicas();
        stopPrimaryListeners();
    }

    @Test
    void kafkaPostgresReplayAndContradictoryReuseConvergeAcrossTwoOrderReplicas() throws Exception {
        Order order = pendingOrder();
        order = orderRepository.saveAndFlush(order);

        UUID eventId = UUID.randomUUID();
        String payload = payload(eventId, order.getId(), "ingress-proof");

        // The test application context is the first independently configured
        // replica. Starting only one additional context avoids retaining three
        // complete Order/Spring WebFlux application graphs in an 8 GB CI host.
        startPrimaryReplica();
        startReplica(REPLICA_GROUP);
        await("two Order replicas to own the two command partitions", () ->
                targetPartitionOwners(REPLICA_GROUP).equals(Set.of(0, 1)));

        publishRelay(0, order.getId(), payload);
        publishRelay(1, order.getId(), payload);

        long persistedOrderId = order.getId();
        await("one committed Order receipt/transition before both source offsets", () ->
                receiptRepository.count() == 1
                        && orderRepository.findById(persistedOrderId)
                        .map(value -> value.getStatus() == OrderStatus.FINDING_SHIPPER)
                        .orElse(false)
                        && committedOffsetsAtLeast(REPLICA_GROUP, 1, 1));
        assertOneFindingShipperEffect(persistedOrderId);

        publishRelay(0, order.getId(), payload);
        await("same-group exact replay to commit as an Order no-op", () ->
                committedOffsetsAtLeast(REPLICA_GROUP, 2, 1));
        assertOneFindingShipperEffect(persistedOrderId);

        closeReplicas();
        stopPrimaryListeners();
        startReplica(REPLAY_GROUP);
        await("fresh Order consumer group to replay historical offsets as no-ops", () ->
                committedOffsetsAtLeast(REPLAY_GROUP, 2, 1));
        assertOneFindingShipperEffect(persistedOrderId);

        try (KafkaConsumer<String, String> dltConsumer = freshConsumer(DLT_TOPIC)) {
            String contradictory = payload(eventId, order.getId(), "contradictory-reuse");
            publishRelay(1, order.getId(), contradictory);

            ConsumerRecord<String, String> dlt = awaitRecord(
                    dltConsumer, "contradictory Order receipt reuse to reach the owner DLT");
            assertThat(dlt.value()).isEqualTo(contradictory);
            await("contradictory Order source offset to recover only after DLT publication", () ->
                    committedOffsetsAtLeast(REPLAY_GROUP, 2, 2));
        }
        assertOneFindingShipperEffect(persistedOrderId);
    }

    private void publishRelay(int partition, long orderId, String payload) throws Exception {
        // Dispatch SagaOutboxRelay publishes readTree(): JsonSerializer adds
        // the ObjectNode type header rejected by the former shared consumer.
        relayKafka.send(TOPIC, partition, Long.toString(orderId), wireMapper.readTree(payload))
                .get(10, TimeUnit.SECONDS);
    }

    private void assertOneFindingShipperEffect(long orderId) {
        assertThat(receiptRepository.count()).isEqualTo(1);
        assertThat(orderRepository.findById(orderId)).get()
                .extracting(Order::getStatus)
                .isEqualTo(OrderStatus.FINDING_SHIPPER);
    }

    private ConfigurableApplicationContext startReplica(String groupId) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(OrderServiceApplication.class)
                .web(WebApplicationType.NONE)
                .properties(replicaProperties(groupId))
                .run();
        startListenersFor(listenerRegistry(context));
        replicas.add(context);
        return context;
    }

    private void startPrimaryReplica() {
        startListenersFor(primaryListenerRegistry);
    }

    private void startListenersFor(KafkaListenerEndpointRegistry registry) {
        registry.getListenerContainers().stream()
                .filter(container -> listensTo(container, TOPIC))
                .forEach(container -> {
                    if (!container.isRunning()) {
                        container.start();
                    }
                });
    }

    private void stopPrimaryListeners() {
        primaryListenerRegistry.getListenerContainers().stream()
                .filter(container -> listensTo(container, TOPIC))
                .forEach(container -> {
                    if (container.isRunning()) {
                        container.stop();
                    }
                });
    }

    private Map<String, Object> replicaProperties(String groupId) {
        return Map.ofEntries(
                Map.entry("spring.main.banner-mode", "off"),
                Map.entry("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers()),
                Map.entry("spring.kafka.consumer.group-id", groupId),
                Map.entry("spring.kafka.listener.auto-startup", "false"),
                Map.entry("spring.kafka.admin.auto-create", "false"),
                Map.entry("platform.kafka.dlt.suffix", ".order.DLT"),
                Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
                Map.entry("spring.datasource.username", POSTGRES.getUsername()),
                Map.entry("spring.datasource.password", POSTGRES.getPassword()),
                Map.entry("spring.datasource.driver-class-name", "org.postgresql.Driver"),
                Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
                Map.entry("spring.flyway.enabled", "true"),
                Map.entry("spring.flyway.baseline-on-migrate", "true"),
                Map.entry("app.kafka.input-topics.saga-update-order-status", TOPIC),
                Map.entry("app.outbox.relay-enabled", "false"),
                Map.entry("spring.task.scheduling.enabled", "false"),
                Map.entry("spring.cloud.discovery.enabled", "false"),
                Map.entry("eureka.client.enabled", "false"),
                Map.entry("app.order.payment-event-processing-enabled", "false"));
    }

    private KafkaListenerEndpointRegistry listenerRegistry(ConfigurableApplicationContext context) {
        return context.getBean(KafkaListenerEndpointRegistry.class);
    }

    private boolean listensTo(MessageListenerContainer container, String topic) {
        String[] topics = container.getContainerProperties().getTopics();
        return topics != null && Arrays.asList(topics).contains(topic);
    }

    private Set<Integer> targetPartitionOwners(String groupId) {
        try (AdminClient admin = adminClient()) {
            ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(groupId)).all()
                    .get(10, TimeUnit.SECONDS).get(groupId);
            return group.members().stream()
                    .flatMap(member -> member.assignment().topicPartitions().stream())
                    .filter(partition -> TOPIC.equals(partition.topic()))
                    .map(TopicPartition::partition)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private boolean committedOffsetsAtLeast(String groupId, long partitionZero, long partitionOne) {
        try (AdminClient admin = adminClient()) {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS);
            return offsets.getOrDefault(new TopicPartition(TOPIC, 0),
                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(0)).offset() >= partitionZero
                    && offsets.getOrDefault(new TopicPartition(TOPIC, 1),
                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(0)).offset() >= partitionOne;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void createRequiredTopics() throws Exception {
        try (AdminClient admin = adminClient()) {
            admin.createTopics(List.of(
                    new NewTopic(TOPIC, 2, (short) 1),
                    new NewTopic(DLT_TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException exception) {
            if (!(exception.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw exception;
            }
        }
    }

    private AdminClient adminClient() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()));
    }

    private KafkaConsumer<String, String> freshConsumer(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "order-saga-inbox-dlt-proof-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer,
                                                         String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                if (DLT_TOPIC.equals(record.topic())) {
                    return record;
                }
            }
        }
        fail("Timed out waiting for " + description);
        throw new AssertionError("unreachable");
    }

    private void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for " + description, exception);
            }
        }
        fail("Timed out waiting for " + description);
    }

    private Order pendingOrder() {
        Order order = new Order();
        order.setUserId(7L);
        order.setCreatorId(7L);
        order.setRestaurantId(8L);
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentMethod("COD");
        order.setSubtotalPrice(new BigDecimal("100000"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingFee(new BigDecimal("15000"));
        order.setTotalPrice(new BigDecimal("115000"));
        return order;
    }

    private String payload(UUID eventId, long orderId, String note) {
        String originalEvent = "{\\\"orderId\\\":" + orderId + ",\\\"deliveryId\\\":8,"
                + "\\\"notes\\\":\\\"" + note + "\\\"}";
        return "{\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
                + ",\"sagaStatus\":\"FINDING_SHIPPER\",\"originalEvent\":\""
                + originalEvent + "\"}";
    }

    private void closeReplicas() {
        for (int index = replicas.size() - 1; index >= 0; index--) {
            replicas.get(index).close();
        }
        replicas.clear();
    }
}
