package com.delivery.order_service.config;

import com.delivery.order_service.listener.SagaCommandListener;
import com.delivery.order_service.listener.RestaurantEventListener;
import com.delivery.order_service.listener.PaymentEventListener;
import com.delivery.order.contracts.PaymentEvent;
import com.delivery.platform.kafka.CommonKafkaConsumerConfig;
import com.delivery.platform.kafka.CommonKafkaErrorHandler;
import com.delivery.platform.kafka.CommonKafkaProducerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.kafka.support.mapping.DefaultJackson2JavaTypeMapper;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.JsonSerializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderKafkaConsumerConfigTest {

    private final OrderKafkaConsumerConfig config = new OrderKafkaConsumerConfig();
    private final ObjectMapper mapper = JacksonUtils.enhancedObjectMapper();

    @ParameterizedTest
    @MethodSource("listenerCases")
    void everyListenerBindsProducerHeadersAndHeaderlessJson(Class<?> listener, String methodName,
                                                           String json, String producerType) throws Exception {
        for (boolean withHeader : new boolean[] {true, false}) {
            Object value = producerType.equals("com.fasterxml.jackson.databind.node.ObjectNode")
                    ? mapper.readTree(json) : mapper.readValue(json, PaymentEvent.class);
            ConsumerRecord<String, String> record = wireRecord(value, withHeader, producerType);
            Object result = convert(record, listener, methodName);
            Type type = payloadType(listener, methodName);
            assertThat(result).isEqualTo(type == String.class ? json : mapper.readValue(json, (Class<?>) type));
        }
    }

    static Stream<Arguments> listenerCases() {
        String restaurant = "{\"eventId\":\"f498df81-2c52-4d59-a2b4-b8b77c2a8024\","
                + "\"orderId\":3,\"restaurantId\":8,\"status\":\"CONFIRMED\",\"estimatedPrepTime\":15}";
        String payment = "{\"paymentId\":2,\"orderId\":3,\"userId\":7,\"status\":\"COMPLETED\",\"amount\":115000.0}";
        String nodeType = "com.fasterxml.jackson.databind.node.ObjectNode";
        String paymentType = "com.delivery.settlement_service.dto.event.PaymentEvent";
        return Stream.of(
                Arguments.of(SagaCommandListener.class, "handleUpdateOrderStatusCommand",
                        "{\"orderId\":3,\"sagaStatus\":\"FINDING_SHIPPER\"}", nodeType),
                Arguments.of(RestaurantEventListener.class, "handleRestaurantConfirmed", restaurant, nodeType),
                Arguments.of(RestaurantEventListener.class, "handleRestaurantRejected", restaurant.replace("CONFIRMED", "REJECTED"), nodeType),
                Arguments.of(PaymentEventListener.class, "handlePaymentCompleted", payment, paymentType),
                Arguments.of(PaymentEventListener.class, "handlePaymentFailed", payment.replace("COMPLETED", "FAILED"), paymentType));
    }

    @Test
    void malformedTypedPayloadStillFailsConversionWhileSagaReceivesRawJson() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("proof", 0, 0, "key", "{broken");
        assertThatThrownBy(() -> convert(record, RestaurantEventListener.class, "handleRestaurantConfirmed"))
                .isInstanceOf(ConversionException.class);
        assertThatThrownBy(() -> convert(record, PaymentEventListener.class, "handlePaymentCompleted"))
                .isInstanceOf(ConversionException.class);
        assertThat(convert(record, SagaCommandListener.class, "handleUpdateOrderStatusCommand"))
                .isEqualTo("{broken");
    }

    @Test
    void serviceOverridesSharedWiringAndPreservesRecoveryJsonBytes() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonKafkaProducerConfig.class,
                        CommonKafkaConsumerConfig.class, KafkaAutoConfiguration.class))
                .withUserConfiguration(OrderKafkaConsumerConfig.class)
                .withPropertyValues("spring.kafka.listener.auto-startup=false",
                        "spring.kafka.consumer.group-id=order-service-group")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConsumerFactory.class)
                            .hasSingleBean(CommonKafkaErrorHandler.class).hasSingleBean(KafkaTemplate.class);
                    assertThat(context.getBean(CommonKafkaErrorHandler.class).dltSuffix()).isEqualTo(".DLT");
                    ConsumerFactory<?, ?> consumer = context.getBean(ConsumerFactory.class);
                    assertThat(consumer.getConfigurationProperties())
                            .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "order-service-group")
                            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                    ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                            context.getBean(ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(factory.getContainerProperties().getAckMode())
                            .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
                    assertThat(factory.createContainer("proof").isAutoStartup()).isFalse();
                    DefaultKafkaProducerFactory<?, ?> producer = context.getBean(DefaultKafkaProducerFactory.class);
                    assertThat(context.getBean(KafkaTemplate.class).getProducerFactory()).isSameAs(producer);
                    @SuppressWarnings("unchecked")
                    org.apache.kafka.common.serialization.Serializer<Object> serializer =
                            (org.apache.kafka.common.serialization.Serializer<Object>) producer.getValueSerializer();
                    String rawJson = "{\"orderId\":3}";
                    assertThat(serializer.serialize("proof.DLT", new RecordHeaders(), rawJson))
                            .isEqualTo(rawJson.getBytes(StandardCharsets.UTF_8));
                    assertThat(serializer.serialize("proof.DLT", new RecordHeaders(), mapper.readTree(rawJson)))
                            .isEqualTo(rawJson.getBytes(StandardCharsets.UTF_8));
                });
    }

    private ConsumerRecord<String, String> wireRecord(Object producerValue, boolean withHeader, String producerType) {
        RecordHeaders headers = new RecordHeaders();
        byte[] bytes;
        try (JsonSerializer<Object> serializer = new JsonSerializer<>()) {
            // Settlement's DTO is outside Order's dependencies. Emit its exact
            // wire type id with the identical contract fields, without importing it.
            var typeMapper = new DefaultJackson2JavaTypeMapper();
            typeMapper.setIdClassMapping(Map.of(producerType, producerValue.getClass()));
            serializer.setTypeMapper(typeMapper);
            bytes = serializer.serialize("proof", headers, producerValue);
        }
        assertThat(new String(headers.lastHeader("__TypeId__").value(), StandardCharsets.UTF_8))
                .isEqualTo(producerType);
        if (!withHeader) headers.remove("__TypeId__");
        try (StringDeserializer deserializer = new StringDeserializer()) {
            ConsumerRecord<String, String> record = new ConsumerRecord<>("proof", 0, 0, "key",
                    deserializer.deserialize("proof", headers, bytes));
            headers.forEach(record.headers()::add);
            return record;
        }
    }

    private Object convert(ConsumerRecord<String, String> record, Class<?> listener, String methodName) {
        Type payloadType = payloadType(listener, methodName);
        return config.orderMessageConverter().toMessage(record, null, null, payloadType).getPayload();
    }

    private Type payloadType(Class<?> listener, String methodName) {
        return Arrays.stream(listener.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName)).findFirst().orElseThrow()
                .getGenericParameterTypes()[0];
    }
}
