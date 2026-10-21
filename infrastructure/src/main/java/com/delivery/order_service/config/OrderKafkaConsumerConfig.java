package com.delivery.order_service.config;

import com.delivery.platform.kafka.CommonKafkaErrorHandler;
import com.delivery.platform.kafka.CommonKafkaProducerConfig;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.kafka.support.mapping.Jackson2JavaTypeMapper.TypePrecedence;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/** Order owns payload binding; producer Java type headers are not its contract. */
@Configuration
public class OrderKafkaConsumerConfig {

    @Bean(name = {"commonKafkaConsumerFactory", "consumerFactory"})
    public ConsumerFactory<String, String> consumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:delivery-kafka-consumer}") String groupId) {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
    }

    @Bean
    public StringJsonMessageConverter orderMessageConverter() {
        StringJsonMessageConverter converter = new StringJsonMessageConverter() {
            @Override
            protected Object extractAndConvertValue(ConsumerRecord<?, ?> record, Type type) {
                // Saga validates the complete raw JSON itself. Do not ask
                // Jackson to deserialize a JSON object into a Java String.
                return type == String.class && record.value() != null
                        ? record.value() : super.extractAndConvertValue(record, type);
            }
        };
        converter.getTypeMapper().setTypePrecedence(TypePrecedence.INFERRED);
        return converter;
    }

    @Bean(name = {"commonKafkaListenerContainerFactory", "kafkaListenerContainerFactory"})
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            @Qualifier("commonKafkaConsumerFactory") ConsumerFactory<String, String> consumerFactory,
            CommonKafkaErrorHandler errorHandler,
            StringJsonMessageConverter orderMessageConverter,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setAutoStartup(autoStartup);
        // Preserve the shared factory's manual acknowledgment/commit behavior.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(errorHandler);
        factory.setRecordMessageConverter(orderMessageConverter);
        return factory;
    }

    @Bean(name = {"commonKafkaProducerFactory", "producerFactory"})
    public ProducerFactory<String, Object> recoveryProducerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        // Retry/DLT recovery now receives raw strings. Preserve their JSON bytes
        // instead of JsonSerializer quoting them; typed values still use JSON.
        Map<Class<?>, Serializer<?>> serializers = new LinkedHashMap<>();
        serializers.put(String.class, new StringSerializer());
        serializers.put(Object.class, new JsonSerializer<>());
        return new DefaultKafkaProducerFactory<>(
                new CommonKafkaProducerConfig(bootstrapServers).producerFactory().getConfigurationProperties(),
                new StringSerializer(), new DelegatingByTypeSerializer(serializers, true));
    }

    @Bean(name = {"commonKafkaTemplate", "kafkaTemplate"})
    public KafkaTemplate<String, Object> recoveryKafkaTemplate(
            @Qualifier("commonKafkaProducerFactory") ProducerFactory<String, Object> producerFactory) {
        // The shared auto-configuration calls its own factory method directly;
        // explicitly use our factory for both retry-topic and DLT publication.
        return new KafkaTemplate<>(producerFactory);
    }
}
