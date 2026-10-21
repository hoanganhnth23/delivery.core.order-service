package com.delivery.order_service.listener;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaListenerTopicConfigurationTest {

    @Test
    void activeOrderInputsKeepCanonicalDefaultsAndUseFactoryGroup() {
        assertListener(RestaurantEventListener.class, "handleRestaurantConfirmed",
                "${app.kafka.input-topics.restaurant-confirmed:restaurant.order-confirmed}");
        assertListener(RestaurantEventListener.class, "handleRestaurantRejected",
                "${app.kafka.input-topics.restaurant-rejected:restaurant.order-rejected}");
        assertListener(SagaCommandListener.class, "handleUpdateOrderStatusCommand",
                "${app.kafka.input-topics.saga-update-order-status:saga.command.update-order-status}");
    }

    @Test
    void restaurantRetryTopologyIsOwnedByOrderService() {
        RetryableTopic retry = RestaurantEventListener.class.getAnnotation(RetryableTopic.class);

        assertThat(retry).isNotNull();
        assertThat(retry.retryTopicSuffix()).isEqualTo("-retry-order");
        assertThat(retry.dltTopicSuffix()).isEqualTo(".order.DLT");
        assertThat(retry.autoCreateTopics()).isEqualTo("${app.kafka.retry.auto-create-topics:false}");
    }

    @Test
    void sagaCommandRetryTopologyIsAlsoOwnedByOrderService() {
        RetryableTopic retry = SagaCommandListener.class.getAnnotation(RetryableTopic.class);

        assertThat(retry).isNotNull();
        assertThat(retry.retryTopicSuffix()).isEqualTo("-retry-order");
        assertThat(retry.dltTopicSuffix()).isEqualTo(".order.DLT");
        assertThat(retry.autoCreateTopics()).isEqualTo("${app.kafka.retry.auto-create-topics:false}");
    }

    @Test
    void sagaCommandConsumesTheUnmodifiedRawKafkaValueForReceiptFingerprinting() throws Exception {
        Method method = SagaCommandListener.class.getDeclaredMethod(
                "handleUpdateOrderStatusCommand", String.class,
                org.springframework.kafka.support.Acknowledgment.class);

        assertThat(method.getParameters()[0].getType()).isEqualTo(String.class);
    }

    private void assertListener(Class<?> listenerType, String methodName, String topic) {
        Method method = Arrays.stream(listenerType.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        KafkaListener annotation = method.getAnnotation(KafkaListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.topics()).containsExactly(topic);
        assertThat(annotation.groupId()).isEmpty();
    }
}
