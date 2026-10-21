package com.delivery.order_service.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;

import org.junit.jupiter.api.Test;

import com.delivery.auth.resourceserver.security.AuthenticatedActor;
import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import com.delivery.order_service.exception.AccessDeniedException;
import com.delivery.order_service.service.CheckoutPreviewService;
import com.delivery.order_service.service.OrderService;

import java.util.Set;
import java.util.UUID;
import com.delivery.identity.contracts.SimulationContext;
import com.delivery.order_service.dto.request.CreateOrderRequest;

class OrderControllerAuthorizationTest {

    private final OrderService orderService = mock(OrderService.class);
    private final CheckoutPreviewService previewService = mock(CheckoutPreviewService.class);
    private final OrderController controller = new OrderController(orderService, previewService);

    @Test
    void checkoutPreviewRejectsNonCustomerBeforeCallingDependencies() {
        CheckoutPreviewRequest request = new CheckoutPreviewRequest();
        AuthenticatedActor shopActor = new AuthenticatedActor(7L, "shop@example.com", Set.of("SHOP_OWNER"));

        assertThatThrownBy(() -> controller.checkoutPreview(request, shopActor))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(orderService, previewService);
    }

    @Test
    void selfOrderListsRejectWrongActorsBeforeQuery() {
        AuthenticatedActor shipperActor = new AuthenticatedActor(7L, "shipper@example.com", Set.of("SHIPPER"));
        AuthenticatedActor userActor = new AuthenticatedActor(7L, "user@example.com", Set.of("USER"));

        assertThatThrownBy(() -> controller.getMyOrders(shipperActor, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getMyRestaurantOrders(userActor, 0, 10))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(orderService, previewService);
    }

    @Test
    void orderManagementListsRejectNonAdminBeforeQuery() {
        AuthenticatedActor ownerActor = new AuthenticatedActor(7L, "shop@example.com", Set.of("SHOP_OWNER"));

        assertThatThrownBy(() -> controller.getAllOrders(ownerActor, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getOrdersByStatus("PENDING", ownerActor, 0, 10))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(orderService, previewService);
    }

    @Test
    void createOrderPassesServerSignedSimulationContextToTheDomainService() {
        UUID runId = UUID.randomUUID();
        UUID cohortId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(9L, 7L, "fixture@example.com", Set.of("USER"),
                new SimulationContext(SimulationContext.ExecutionMode.SIMULATION, runId, cohortId, 2L));

        controller.createOrder(new CreateOrderRequest(), null, actor);

        verify(orderService).createOrder(any(CreateOrderRequest.class),
                isNull(), eq(9L), eq(7L), eq("USER"), eq(actor.getSimulationContext()));
    }
}
