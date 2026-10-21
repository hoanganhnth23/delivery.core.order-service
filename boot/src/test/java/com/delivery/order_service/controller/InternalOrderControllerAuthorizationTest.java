package com.delivery.order_service.controller;

import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderStatus;
import com.delivery.order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalOrderControllerAuthorizationTest {


    @Mock OrderRepository orderRepository;

    private InternalOrderController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalOrderController(orderRepository);
        ReflectionTestUtils.setField(controller, "internalSecret", "test-secret");
    }

    @Test
    void missingCredentialIsRejectedBeforeRepositoryAccess() {
        var response = controller.isRatingEligible(1L, 2L, 3L, null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(0, response.getBody().getStatus());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void onlyMatchingDeliveredOrderIsEligible() {
        Order order = new Order();
        order.setId(1L);
        order.setUserId(2L);
        order.setRestaurantId(3L);
        order.setStatus(OrderStatus.DELIVERED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertTrue(controller.isRatingEligible(1L, 2L, 3L, "test-secret").getBody().getData());
        assertFalse(controller.isRatingEligible(1L, 999L, 3L, "test-secret").getBody().getData());
    }

    @Test
    void restaurantDecisionRequiresMatchingPendingOrder() {
        Order order = new Order();
        order.setId(1L);
        order.setRestaurantId(3L);
        order.setStatus(OrderStatus.PENDING);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertTrue(controller.isRestaurantDecisionEligible(1L, 3L, "test-secret").getBody().getData());
        assertFalse(controller.isRestaurantDecisionEligible(1L, 4L, "test-secret").getBody().getData());

        order.setStatus(OrderStatus.CONFIRMED);
        assertFalse(controller.isRestaurantDecisionEligible(1L, 3L, "test-secret").getBody().getData());
        verify(orderRepository, org.mockito.Mockito.times(3)).findByIdForUpdate(1L);
    }

    @Test
    void restaurantDecisionRejectsMissingCredentialBeforeRepositoryAccess() {
        var response = controller.isRestaurantDecisionEligible(1L, 3L, null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(0, response.getBody().getStatus());
        verifyNoInteractions(orderRepository);
    }
}
