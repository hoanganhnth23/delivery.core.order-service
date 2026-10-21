package com.delivery.order_service.service;

import com.delivery.order_service.dto.internal.ValidatedOrderData;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction/lease/outbox adapters, with only remote facts and inventory mocked. */
@SpringBootTest(properties = "app.order.inventory-reservation-enabled=true")
@ActiveProfiles("test")
class OrderCreateTransactionIntegrationTest {
    @Autowired OrderService service;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository items;
    @Autowired OutboxEventRepository outbox;
    @Autowired OrderCreateIdempotencyReceiptRepository receipts;
    @Autowired RestaurantDecisionReceiptRepository restaurantReceipts;
    @Autowired SagaCommandReceiptRepository sagaReceipts;
    @MockitoBean OrderValidationService validation;
    @MockitoBean InventoryReservationClient inventory;
    @MockitoSpyBean OrderEventPublisher publisher;

    @BeforeEach void clean() {
        outbox.deleteAll(); restaurantReceipts.deleteAll(); sagaReceipts.deleteAll();
        items.deleteAll(); orders.deleteAll(); receipts.deleteAll();
        when(validation.validateCreateOrderRequest(any(), eq(77L), eq(21L)))
                .thenReturn(new ValidatedOrderData(31L, 88L, "Canonical restaurant", "Pickup", "0901234567",
                        10.75, 106.66, List.of(new ValidatedOrderData.ValidatedItemData(99L,"Canonical item",new BigDecimal("50000")))));
    }
    @Test void createCommitsSnapshotsReceiptInventoryAndOneOutboxAndExactReplaySkipsRemote() {
        UUID key=UUID.randomUUID(); CreateOrderRequest request=request();
        var response=service.createOrder(request,key,77L,21L,"USER");
        var replay=service.createOrder(request,key,77L,21L,"USER");
        assertThat(replay.getId()).isEqualTo(response.getId());
        assertThat(orders.count()).isEqualTo(1); assertThat(items.count()).isEqualTo(1); assertThat(outbox.count()).isEqualTo(1);
        Order stored=orders.findById(response.getId()).orElseThrow();
        assertThat(stored.getSubtotalPrice()).isEqualByComparingTo("100000");
        assertThat(stored.getUserPrincipalId()).isEqualTo(77L);
        assertThat(receipts.findByPrincipalIdAndIdempotencyKey(77L,key).orElseThrow().getOrderId()).isEqualTo(response.getId());
        verify(validation,times(1)).validateCreateOrderRequest(any(),eq(77L),eq(21L));
        verify(inventory,times(1)).reserve(eq(stored.getInventoryReservationId()),eq(response.getId()),eq(21L),eq(77L),eq(7L),anyList());
        verify(inventory,times(1)).commit(stored.getInventoryReservationId(),response.getId());
        verify(inventory,never()).release(any(),any());
        request.setNotes("changed command");
        assertThatThrownBy(()->service.createOrder(request,key,77L,21L,"USER"))
                .isInstanceOfSatisfying(com.delivery.order_service.exception.OrderApiException.class,
                        error->assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }
    @Test void preflightFailureReleasesLeaseAndSameCommandCanRetry() {
        UUID key=UUID.randomUUID(); CreateOrderRequest request=request();
        when(validation.validateCreateOrderRequest(any(),eq(77L),eq(21L)))
                .thenThrow(new IllegalStateException("remote unavailable"));
        assertThatThrownBy(()->service.createOrder(request,key,77L,21L,"USER")).hasMessage("remote unavailable");
        assertThat(receipts.findByPrincipalIdAndIdempotencyKey(77L,key).orElseThrow().getProcessingToken()).isNull();
        assertThat(orders.count()).isZero(); verifyNoInteractions(inventory);
        when(validation.validateCreateOrderRequest(any(),eq(77L),eq(21L)))
                .thenReturn(new ValidatedOrderData(31L,88L,"Canonical restaurant","Pickup","0901234567",
                        10.75,106.66,List.of(new ValidatedOrderData.ValidatedItemData(99L,"Canonical item",new BigDecimal("50000")))));
        assertThat(service.createOrder(request,key,77L,21L,"USER").getId()).isPositive();
    }
    @Test void ambiguousReserveFailureRollsBackShellAndReleasesSameIdsAndLease() {
        UUID key=UUID.randomUUID();CreateOrderRequest request=request();
        when(inventory.reserve(any(),anyLong(),eq(21L),eq(77L),eq(7L),anyList()))
                .thenThrow(new IllegalStateException("ambiguous inventory timeout"));
        assertThatThrownBy(()->service.createOrder(request,key,77L,21L,"USER")).hasMessage("ambiguous inventory timeout");
        ArgumentCaptor<UUID> id=ArgumentCaptor.forClass(UUID.class);ArgumentCaptor<Long> orderId=ArgumentCaptor.forClass(Long.class);
        verify(inventory).reserve(id.capture(),orderId.capture(),eq(21L),eq(77L),eq(7L),anyList());
        verify(inventory).release(id.getValue(),orderId.getValue());
        assertThat(orders.count()).isZero();assertThat(items.count()).isZero();assertThat(outbox.count()).isZero();
        assertThat(receipts.findByPrincipalIdAndIdempotencyKey(77L,key).orElseThrow().getProcessingToken()).isNull();
    }
    @Test void failureAfterReceiptCompletionRollsBackAllLocalWritesAndReleasesLeaseForRetry() {
        UUID key=UUID.randomUUID();CreateOrderRequest request=request();
        doThrow(new IllegalStateException("outbox unavailable")).when(publisher).publishOrderCreatedEvent(any());
        assertThatThrownBy(()->service.createOrder(request,key,77L,21L,"USER")).hasMessage("outbox unavailable");
        assertThat(orders.count()).isZero();assertThat(items.count()).isZero();assertThat(outbox.count()).isZero();
        var receipt=receipts.findByPrincipalIdAndIdempotencyKey(77L,key).orElseThrow();
        assertThat(receipt.getOrderId()).isNull();
        // Acquire returns a detached handle; the final transaction completes another instance.
        // Rollback restores the incomplete receipt, then the outer catch releases its lease.
        assertThat(receipt.getProcessingToken()).isNull();
        ArgumentCaptor<UUID> id=ArgumentCaptor.forClass(UUID.class);ArgumentCaptor<Long> orderId=ArgumentCaptor.forClass(Long.class);
        verify(inventory).commit(id.capture(),orderId.capture());verify(inventory).release(id.getValue(),orderId.getValue());
        doCallRealMethod().when(publisher).publishOrderCreatedEvent(any());
        var retried = service.createOrder(request,key,77L,21L,"USER");
        assertThat(orders.count()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(receipts.findByPrincipalIdAndIdempotencyKey(77L,key).orElseThrow().getOrderId())
                .isEqualTo(retried.getId());
    }
    private CreateOrderRequest request() {
        CreateOrderRequest r=new CreateOrderRequest();r.setRestaurantId(7L);r.setPaymentMethod("COD");
        r.setDeliveryAddress("Delivery");r.setDeliveryLat(10.8);r.setDeliveryLng(106.7);
        r.setCustomerName("Customer");r.setCustomerPhone("0901234567");
        CreateOrderRequest.OrderItemRequest item=new CreateOrderRequest.OrderItemRequest();
        item.setMenuItemId(99L);item.setQuantity(2);item.setPrice(new BigDecimal("1"));r.setItems(List.of(item));return r;
    }
}
