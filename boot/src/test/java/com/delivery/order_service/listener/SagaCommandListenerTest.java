package com.delivery.order_service.listener;

import com.delivery.order_service.service.SagaOrderCommandProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import com.delivery.identity.contracts.SimulationContext;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class SagaCommandListenerTest {

    @Mock SagaOrderCommandProcessor commandProcessor;
    @Mock Acknowledgment acknowledgment;

    private SagaCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new SagaCommandListener(commandProcessor);
    }

    @Test
    void invalidCommandIsNotAcknowledged() {
        assertThrows(IllegalArgumentException.class,
                () -> listener.handleUpdateOrderStatusCommand(
                        "{\"sagaStatus\":\"DELIVERED\"}", acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void serviceFailureIsNotAcknowledged() {
        org.mockito.Mockito.doThrow(new IllegalStateException("invalid transition"))
                .when(commandProcessor).applyDeliveryStatus(any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class,
                () -> listener.handleUpdateOrderStatusCommand(
                        command("DELIVERED", 1L),
                        acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void unknownStatusIsNotAcknowledgedAsSuccess() {
        assertThrows(IllegalArgumentException.class,
                () -> listener.handleUpdateOrderStatusCommand(
                        command("TYPO_STATUS", 1L),
                        acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void nonPositiveOrderIdentityIsNotAcknowledged() {
        assertThrows(IllegalArgumentException.class,
                () -> listener.handleUpdateOrderStatusCommand(
                        command("DELIVERED", 0L),
                        acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void successfulCommandIsAcknowledgedOnce() {
        when(commandProcessor.applyDeliveryStatus(any(), any(), any(), any(), any())).thenReturn(true);
        listener.handleUpdateOrderStatusCommand(
                command("FINDING_SHIPPER", 1L,
                        "{\"orderId\":1,\"deliveryId\":8,\"timestamp\":1785044842775}"),
                acknowledgment);

        verify(commandProcessor).applyDeliveryStatus(any(), any(), any(), any(), argThat(event ->
                Long.valueOf(1L).equals(event.getOrderId())
                        && Long.valueOf(8L).equals(event.getDeliveryId())
                        && "FINDING_SHIPPER".equals(event.getStatus())));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void deliveryStatusCommandPreservesSimulationContext() {
        when(commandProcessor.applyDeliveryStatus(any(), any(), any(), any(), any())).thenReturn(true);
        String context = "{\"mode\":\"SIMULATION\",\"runId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"cohortId\":\"22222222-2222-2222-2222-222222222222\",\"bindingVersion\":3}";

        listener.handleUpdateOrderStatusCommand(
                command("PICKED_UP", 1L,
                        "{\"orderId\":1,\"deliveryId\":8,\"simulationContext\":" + context + "}"),
                acknowledgment);

        verify(commandProcessor).applyDeliveryStatus(any(), any(), any(), any(), argThat(event -> {
            SimulationContext actual = event.getSimulationContext();
            return actual != null
                    && actual.isSimulation()
                    && actual.runId().equals(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                    && actual.bindingVersion() == 3L;
        }));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void exactReplayAcknowledgesWithoutRepeatingOrderMutation() {
        when(commandProcessor.applyDeliveryStatus(any(), any(), any(), any(), any())).thenReturn(false);

        listener.handleUpdateOrderStatusCommand(
                command("FINDING_SHIPPER", 1L, "{\"orderId\":1,\"deliveryId\":8}"),
                acknowledgment);

        verify(commandProcessor).applyDeliveryStatus(any(), any(), any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void malformedShipperNotFoundCorrelationIsNotAcknowledged() {
        assertThrows(IllegalArgumentException.class,
                () -> listener.handleUpdateOrderStatusCommand(
                        command("SHIPPER_NOT_FOUND", 1L, "not-json"),
                        acknowledgment));

        verify(commandProcessor, never()).applyShipperNotFound(any(), any(), any(), any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void shipperNotFoundWithoutDeliveryIdentityIsNotAcknowledged() {
        assertThrows(IllegalArgumentException.class,
                () -> listener.handleUpdateOrderStatusCommand(
                        command("SHIPPER_NOT_FOUND", 1L, "{\"orderId\":1}"),
                        acknowledgment));

        verify(commandProcessor, never()).applyShipperNotFound(any(), any(), any(), any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void contradictoryOriginalOrderIdentityIsNotAcknowledged() {
        assertThrows(IllegalArgumentException.class,
                () -> listener.handleUpdateOrderStatusCommand(
                        command("FINDING_SHIPPER", 1L,
                                "{\"orderId\":2,\"deliveryId\":8}"),
                        acknowledgment));

        verify(commandProcessor, never()).applyDeliveryStatus(any(), any(), any(), any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void correlatedShipperNotFoundIsAcknowledged() {
        when(commandProcessor.applyShipperNotFound(any(), any(), any(), any(), any())).thenReturn(true);
        listener.handleUpdateOrderStatusCommand(
                command("SHIPPER_NOT_FOUND", 1L,
                        "{\"orderId\":1,\"deliveryId\":8,\"retryAttempts\":5}"),
                acknowledgment);

        verify(commandProcessor).applyShipperNotFound(any(), any(), any(), any(), argThat(event ->
                Long.valueOf(1L).equals(event.getOrderId())
                        && Long.valueOf(8L).equals(event.getDeliveryId())
                        && Integer.valueOf(5).equals(event.getRetryAttempts())));
        verify(acknowledgment).acknowledge();
    }

    private String command(String status, Long orderId) {
        return command(status, orderId, "{}");
    }

    private String command(String status, Long orderId, String originalEvent) {
        String escapedOriginalEvent = originalEvent.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"eventId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"orderId\":" + orderId + ",\"sagaStatus\":\"" + status
                + "\",\"originalEvent\":\"" + escapedOriginalEvent + "\"}";
    }
}
