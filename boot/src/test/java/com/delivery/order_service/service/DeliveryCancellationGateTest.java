package com.delivery.order_service.service;

import com.delivery.order_service.exception.OrderDependencyUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryCancellationGateTest {

    private final AtomicReference<String> calledUrl = new AtomicReference<>();
    private final AtomicReference<String> sentToken = new AtomicReference<>();

    private DeliveryCancellationGate gate(HttpStatus status, String body, boolean enabled) {
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            calledUrl.set(request.url().toString());
            sentToken.set(request.headers().getFirst("Internal-Token"));
            return Mono.just(ClientResponse.create(status).header("Content-Type", "application/json")
                    .body(body).build());
        }).build();
        return new DeliveryCancellationGate(webClient, "http://delivery-service:8085", "secret", 2000, enabled);
    }

    @Test
    void deliveryAllowsTheCancellation() {
        assertThatCode(() -> gate(HttpStatus.OK, "{\"status\":1}", true).requireCancellable(20L))
                .doesNotThrowAnyException();
        assertThat(calledUrl.get())
                .isEqualTo("http://delivery-service:8085/api/deliveries/internal/orders/20/cancellation-hold");
        assertThat(sentToken.get()).isEqualTo("secret");
    }

    @Test
    void deliveryRefusalIsReportedWithItsReason() {
        assertThatThrownBy(() -> gate(HttpStatus.CONFLICT,
                "{\"status\":0,\"message\":\"Shipper đã lấy hàng, không thể huỷ đơn. Trạng thái giao hàng: PICKED_UP\"}",
                true).requireCancellable(20L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PICKED_UP");
        assertThatThrownBy(() -> gate(HttpStatus.CONFLICT, "not json", true).requireCancellable(20L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DeliveryCancellationGate.DEFAULT_REFUSAL);
    }

    @Test
    void unknownAnswersAndOutagesFailClosedAsRetryable() {
        assertThatThrownBy(() -> gate(HttpStatus.FORBIDDEN, "{}", true).requireCancellable(20L))
                .isInstanceOf(OrderDependencyUnavailableException.class);
        assertThatThrownBy(() -> gate(HttpStatus.INTERNAL_SERVER_ERROR, "{}", true).requireCancellable(20L))
                .isInstanceOf(OrderDependencyUnavailableException.class);
        WebClient down = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new ConnectException("refused"))).build();
        assertThatThrownBy(() -> new DeliveryCancellationGate(down, "http://d", "s", 2000, true).requireCancellable(20L))
                .isInstanceOf(OrderDependencyUnavailableException.class);
    }

    @Test
    void emergencySwitchSkipsTheCall() {
        gate(HttpStatus.CONFLICT, "{}", false).requireCancellable(20L);
        assertThat(calledUrl.get()).isNull();
    }
}
