package com.delivery.order_service.controller;

import com.delivery.auth.resourceserver.security.AuthenticatedActor;
import com.delivery.order_service.dto.request.*;
import com.delivery.order_service.dto.response.*;
import com.delivery.order_service.exception.ValidationException;
import com.delivery.order_service.service.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderControllerBehaviorTest {
    final OrderService orders = mock(OrderService.class);
    final CheckoutPreviewService previews = mock(CheckoutPreviewService.class);
    final CheckoutQuoteService quotes = mock(CheckoutQuoteService.class);
    final OrderController controller = new OrderController(orders,previews,quotes);
    final AuthenticatedActor user = new AuthenticatedActor(10L,100L,"user@example.com",Set.of("USER"));
    final AuthenticatedActor admin = new AuthenticatedActor(20L,200L,"admin@example.com",Set.of("ADMIN"));
    final AuthenticatedActor owner = new AuthenticatedActor(30L,300L,"owner@example.com",Set.of("SHOP_OWNER"));

    @Test void previewUsesDurableQuoteWithBothIdentityIds() {
        var request = new CheckoutPreviewRequest(); var result = CheckoutPreviewResponse.builder().build();
        when(quotes.issue(request,10L,100L)).thenReturn(result);
        var response = controller.checkoutPreview(request,user);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getData()).isSameAs(result);
        assertThat(response.getBody().getStatus()).isEqualTo(1);
        assertThat(response.getBody().getMessage()).isEqualTo("Checkout preview thành công");
        verifyNoInteractions(previews,orders);
    }

    @Test void createAcceptsPairedQuoteAndTrimmedUuidAndRejectsMalformedPairs() {
        var request = new CreateOrderRequest(); UUID key=UUID.randomUUID(); request.setQuoteId(UUID.randomUUID());
        var result = new OrderResponse();
        when(orders.createOrder(request,key,10L,100L,"USER",user.getSimulationContext())).thenReturn(result);
        assertThat(controller.createOrder(request,"  " + key + "  ",user).getBody().getData()).isSameAs(result);
        assertThatThrownBy(() -> controller.createOrder(request,"invalid",user)).isInstanceOf(ValidationException.class)
                .hasMessage("Idempotency-Key phải là UUID hợp lệ");
        assertThatThrownBy(() -> controller.createOrder(request,null,user)).hasMessage("Idempotency-Key và quoteId phải được gửi cùng nhau");
        request.setQuoteId(null);
        assertThatThrownBy(() -> controller.createOrder(request,key.toString(),user)).hasMessage("Idempotency-Key và quoteId phải được gửi cùng nhau");
        verify(orders,times(1)).createOrder(any(),any(),any(),any(),any(),any());
    }

    @Test void enforcementRequiresKeyAndQuoteBeforeWriting() {
        ReflectionTestUtils.setField(controller,"quoteEnforcementEnabled",true);
        var request = new CreateOrderRequest();
        assertThatThrownBy(() -> controller.createOrder(request," ",user)).hasMessage("Idempotency-Key là bắt buộc khi đặt đơn");
        assertThatThrownBy(() -> controller.createOrder(request,UUID.randomUUID().toString(),user)).hasMessage("quoteId là bắt buộc khi đặt đơn");
        verifyNoInteractions(orders);
    }

    @Test void historyRoutesRespectOwnerAdminAndRestaurantFilterWithPagination() {
        var dto = new OrderResponse(); var page = new PageImpl<>(List.of(dto),PageRequest.of(1,10),25);
        when(orders.getOrdersByPrincipal(10L,100L,"USER",PageRequest.of(1,10))).thenReturn(page);
        when(orders.getOrdersByRestaurantOwner(30L,300L,"SHOP_OWNER",PageRequest.of(1,10))).thenReturn(page);
        when(orders.getOrdersByRestaurant(9L,PageRequest.of(1,10))).thenReturn(page);
        when(orders.getAllOrders(200L,"ADMIN",PageRequest.of(1,10))).thenReturn(page);
        when(orders.getOrdersByStatus("PENDING",200L,"ADMIN",PageRequest.of(1,10))).thenReturn(page);
        var response = controller.getMyOrders(user,1,10).getBody().getData();
        assertThat(response.items()).containsExactly(dto); assertThat(response.totalItems()).isEqualTo(25);
        assertThat(response.page()).isEqualTo(1); assertThat(response.size()).isEqualTo(10); assertThat(response.hasNext()).isTrue();
        assertThat(controller.getMyRestaurantOrders(owner,1,10,9L).getBody().getData().items()).containsExactly(dto);
        assertThat(controller.getMyRestaurantOrders(admin,1,10,9L).getBody().getData().items()).containsExactly(dto);
        assertThat(controller.getMyRestaurantOrders(admin,1,10).getBody().getData().items()).containsExactly(dto);
        assertThat(controller.getAllOrders(admin,1,10).getBody().getData().items()).containsExactly(dto);
        assertThat(controller.getOrdersByStatus("PENDING",admin,1,10).getBody().getData().items()).containsExactly(dto);
        verify(orders).getOrdersByRestaurantOwner(30L,300L,"SHOP_OWNER",PageRequest.of(1,10));
        verify(orders).getOrdersByRestaurant(9L,PageRequest.of(1,10));
    }

    @Test void invalidPaginationAndMissingActorNeverQuery() {
        assertThatThrownBy(() -> controller.getMyOrders(user,-1,10)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> controller.getMyOrders(user,0,0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> controller.getMyOrders(user,0,101)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> controller.getOrderById(1L,null)).hasMessage("Yêu cầu đăng nhập");
        verifyNoInteractions(orders);
    }

    @Test void readAndCancelPassIdentityAndOptionalReason() {
        var dto = new OrderResponse();
        when(orders.getOrderById(1L,10L,100L,"USER")).thenReturn(dto);
        when(orders.cancelOrder(1L,10L,100L,"USER",null)).thenReturn(dto);
        when(orders.cancelOrder(1L,10L,100L,"USER","changed mind")).thenReturn(dto);
        assertThat(controller.getOrderById(1L,user).getBody().getData()).isSameAs(dto);
        assertThat(controller.cancelOrder(1L,user,null).getBody().getData()).isSameAs(dto);
        var request = new CancelOrderRequest(); request.setReason("changed mind");
        assertThat(controller.cancelOrder(1L,user,request).getBody().getData()).isSameAs(dto);
        verify(orders).cancelOrder(1L,10L,100L,"USER","changed mind");
    }
}
