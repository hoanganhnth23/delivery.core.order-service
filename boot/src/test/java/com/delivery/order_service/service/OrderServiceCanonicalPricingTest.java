package com.delivery.order_service.service;

import com.delivery.order_service.dto.internal.ValidatedOrderData;
import com.delivery.order_service.dto.event.ShipperNotFoundEvent;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.dto.response.OrderResponse;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderItem;
import com.delivery.order_service.mapper.OrderMapper;
import com.delivery.order_service.repository.OrderItemRepository;
import com.delivery.order_service.repository.OrderRepository;
import com.delivery.order_service.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class OrderServiceCanonicalPricingTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock OrderMapper orderMapper;
    @Mock OrderEventPublisher orderEventPublisher;
    @Mock OrderValidationService orderValidationService;
    @Mock ShippingFeeCalculationService shippingFeeCalculationService;
    @Mock com.delivery.order_service.metrics.BusinessMetrics businessMetrics;
    @Mock CheckoutReservationClient reservationClient;
    @Mock InventoryReservationClient inventoryReservationClient;
    @Mock LivestreamCheckoutPriceClient livestreamPriceClient;
    @Mock CheckoutQuoteService checkoutQuoteService;

    @Test
    void shipperNotFoundKeepsTerminalStatusAndPublishesRefundEligibilitySnapshot() {
        Order order = new Order();
        order.setId(101L);
        order.setUserId(21L);
        order.setRestaurantId(11L);
        order.setStatus(com.delivery.order_service.entity.OrderStatus.FINDING_SHIPPER);
        when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        ShipperNotFoundEvent event = new ShipperNotFoundEvent();
        event.setOrderId(101L);
        event.setDeliveryId(202L);
        event.setReason("No eligible shipper");
        event.setRetryAttempts(3);

        service().updateOrderStatusFromShipperNotFoundEvent(event);

        org.assertj.core.api.Assertions.assertThat(order.getStatus())
                .isEqualTo(com.delivery.order_service.entity.OrderStatus.SHIPPER_NOT_FOUND);
        verify(orderEventPublisher).publishRefundEligibilityEvent(
                order, "FINDING_SHIPPER", "No eligible shipper");
    }

    @Test
    void customerCancellationLocksOrderBeforeTransitionAndOutbox() {
        Order order = new Order();
        order.setId(101L);
        order.setUserId(21L);
        order.setStatus(com.delivery.order_service.entity.OrderStatus.PENDING);
        when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(orderMapper.orderToOrderResponse(order)).thenReturn(new OrderResponse());
        OrderServiceImpl service = new OrderServiceImpl(
                orderRepository, orderItemRepository, orderMapper, orderEventPublisher,
                orderValidationService, shippingFeeCalculationService, businessMetrics, reservationClient);

        service.cancelOrder(101L, 21L, "USER", "changed mind");

        org.mockito.InOrder mutation = org.mockito.Mockito.inOrder(orderRepository, orderEventPublisher);
        mutation.verify(orderRepository).findByIdForUpdate(101L);
        mutation.verify(orderRepository).save(order);
        mutation.verify(orderEventPublisher).publishOrderCancelledEvent(order, "PENDING", 21L,
                "CUSTOMER", "CUSTOMER_CANCELLED");
        org.assertj.core.api.Assertions.assertThat(order.getUpdatedAt()).isNotNull();
    }

    @Test
    void exactDuplicateCustomerCancellationIsIdempotentWithoutAnotherOutbox() {
        Order order = new Order();
        order.setId(101L);
        order.setUserId(21L);
        order.setStatus(com.delivery.order_service.entity.OrderStatus.PENDING);
        when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(orderMapper.orderToOrderResponse(order)).thenReturn(new OrderResponse());
        OrderServiceImpl service = service();

        service.cancelOrder(101L, 21L, "USER", "changed mind");
        service.cancelOrder(101L, 21L, "USER", "changed mind");

        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.times(2)).findByIdForUpdate(101L);
        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.times(1)).save(order);
        org.mockito.Mockito.verify(orderEventPublisher, org.mockito.Mockito.times(1))
                .publishOrderCancelledEvent(order, "PENDING", 21L,
                        "CUSTOMER", "CUSTOMER_CANCELLED");
    }

    @Test
    void duplicateCancellationWithDifferentReasonIsRejectedWithoutAnotherOutbox() {
        Order order = new Order();
        order.setId(101L);
        order.setUserId(21L);
        order.setStatus(com.delivery.order_service.entity.OrderStatus.CANCELLED);
        order.setCancelledBy(21L);
        order.setCancelReason("changed mind");
        when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));
        OrderServiceImpl service = service();

        assertThrows(IllegalStateException.class,
                () -> service.cancelOrder(101L, 21L, "USER", "different reason"));

        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(orderEventPublisher);
    }

    @Test
    void shippingFeeUsesServerValidatedPickupCoordinates() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRestaurantId(7L);
        request.setPickupLat(1.0);
        request.setPickupLng(2.0);
        request.setDeliveryLat(10.8);
        request.setDeliveryLng(106.7);

        CreateOrderRequest.OrderItemRequest itemRequest = new CreateOrderRequest.OrderItemRequest();
        itemRequest.setMenuItemId(9L);
        itemRequest.setMenuItemName("Cơm");
        itemRequest.setPrice(new BigDecimal("50000"));
        itemRequest.setQuantity(2);
        request.setItems(List.of(itemRequest));

        ValidatedOrderData validated = new ValidatedOrderData(
                11L, 12L, "Quán", "Địa chỉ", "0900000000", 10.75, 106.66,
                List.of(new ValidatedOrderData.ValidatedItemData(
                        9L, "Cơm canonical", new BigDecimal("50000"))));
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L)).thenReturn(validated);

        Order order = new Order();
        order.setId(101L);
        order.setRestaurantId(7L);
        when(orderMapper.createOrderRequestToOrder(request)).thenReturn(order);
        when(shippingFeeCalculationService.calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(orderRepository.save(order)).thenReturn(order);

        OrderItem orderItem = new OrderItem();
        when(orderMapper.orderItemRequestToOrderItem(itemRequest)).thenReturn(orderItem);
        when(orderMapper.orderToOrderResponse(order)).thenReturn(new OrderResponse());

        OrderServiceImpl service = service();

        service.createOrder(request, 21L, "USER");

        verify(shippingFeeCalculationService).calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("100000"));
        verify(orderEventPublisher).publishOrderCreatedEvent(order);
        assertEquals("Cơm canonical", orderItem.getMenuItemName());
        assertEquals(new BigDecimal("50000"), orderItem.getPrice());
        org.assertj.core.api.Assertions.assertThat(order.getItems())
                .as("Hibernate merge must be able to replace collection contents")
                .isInstanceOf(java.util.ArrayList.class);
    }

    @Test
    void voucherDiscountAndReservationIdentityAreSnapshottedIntoOrder() {
        CreateOrderRequest request = baseRequest();
        request.setVoucherIds(List.of(55L));
        ValidatedOrderData validated = validatedItem(new BigDecimal("100000"));
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L)).thenReturn(validated);
        Order order = persistedMappedOrder(request);
        when(shippingFeeCalculationService.calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        when(reservationClient.reserveVoucher(any(UUID.class), eq(101L), eq(21L), eq(55L), eq(7L),
                eq(new BigDecimal("100000")), eq(new BigDecimal("15000"))))
                .thenReturn(new CheckoutReservationClient.VoucherQuote(
                        new BigDecimal("20000"), new BigDecimal("20000"), BigDecimal.ZERO,
                        new BigDecimal("15000"), new BigDecimal("15000"), new BigDecimal("20000"),
                        BigDecimal.ZERO,
                        "[{\"voucherId\":55,\"layer\":\"PLATFORM_DISCOUNT\",\"fundingSource\":\"PLATFORM\",\"discountAmount\":20000}]",
                        List.of(Map.of("voucherId", 55L, "layer", "PLATFORM_DISCOUNT",
                                "fundingSource", "PLATFORM", "discountAmount", new BigDecimal("20000")))));
        when(orderMapper.orderItemRequestToOrderItem(request.getItems().get(0))).thenReturn(new OrderItem());
        when(orderMapper.orderToOrderResponse(order)).thenReturn(new OrderResponse());

        service().createOrder(request, 21L, "USER");

        org.assertj.core.api.Assertions.assertThat(order.getVoucherReservationId()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(order.getSubtotalPrice()).isEqualByComparingTo("100000");
        org.assertj.core.api.Assertions.assertThat(order.getDiscountAmount()).isEqualByComparingTo("20000");
        org.assertj.core.api.Assertions.assertThat(order.getShippingFee()).isEqualByComparingTo("15000");
        org.assertj.core.api.Assertions.assertThat(order.getTotalPrice()).isEqualByComparingTo("95000");
        org.assertj.core.api.Assertions.assertThat(order.getPlatformSubsidy()).isEqualByComparingTo("20000");
        org.assertj.core.api.Assertions.assertThat(order.getShopDiscount()).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThat(order.getShippingDiscount()).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThat(order.getPromotionBreakdown()).contains("PLATFORM_DISCOUNT");
        verify(orderEventPublisher).publishOrderCreatedEvent(order);
    }

    @Test
    void legacyFreeshipReservationReducesCustomerShippingWithoutReducingFood() {
        CreateOrderRequest request = baseRequest();
        request.setVoucherIds(List.of(55L));
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L))
                .thenReturn(validatedItem(new BigDecimal("100000")));
        Order order = persistedMappedOrder(request);
        when(shippingFeeCalculationService.calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        when(reservationClient.reserveVoucher(any(UUID.class), eq(101L), eq(21L), eq(55L), eq(7L),
                eq(new BigDecimal("100000")), eq(new BigDecimal("15000"))))
                .thenReturn(new CheckoutReservationClient.VoucherQuote(
                        new BigDecimal("15000"), BigDecimal.ZERO, new BigDecimal("15000"),
                        BigDecimal.ZERO, new BigDecimal("15000"), new BigDecimal("15000"),
                        BigDecimal.ZERO, "[{\"layer\":\"FREESHIP\"}]", List.of()));
        when(orderMapper.orderItemRequestToOrderItem(request.getItems().get(0))).thenReturn(new OrderItem());
        when(orderMapper.orderToOrderResponse(order)).thenReturn(new OrderResponse());

        service().createOrder(request, 21L, "USER");

        org.assertj.core.api.Assertions.assertThat(order.getItemDiscount()).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThat(order.getShippingDiscount()).isEqualByComparingTo("15000");
        org.assertj.core.api.Assertions.assertThat(order.getCustomerShippingFee()).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThat(order.getGrossShippingFee()).isEqualByComparingTo("15000");
        org.assertj.core.api.Assertions.assertThat(order.getPlatformSubsidy()).isEqualByComparingTo("15000");
        org.assertj.core.api.Assertions.assertThat(order.getTotalPrice()).isEqualByComparingTo("100000");
    }

    @Test
    void flashSalePriceFromReservationOverridesRegularMenuPriceSnapshot() {
        CreateOrderRequest request = baseRequest();
        request.getItems().get(0).setFlashSaleItemId(88L);
        ValidatedOrderData validated = validatedItem(new BigDecimal("100000"));
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L)).thenReturn(validated);
        Order order = persistedMappedOrder(request);
        when(reservationClient.reserveFlash(any(UUID.class), eq(101L), eq(21L), eq(7L), eq(request.getItems())))
                .thenReturn(new CheckoutReservationClient.FlashQuote(Map.of(88L,
                        new CheckoutReservationClient.FlashLine(88L, 9L, 1, new BigDecimal("60000")))));
        when(shippingFeeCalculationService.calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("60000")))
                .thenReturn(new BigDecimal("15000"));
        OrderItem item = new OrderItem();
        when(orderMapper.orderItemRequestToOrderItem(request.getItems().get(0))).thenReturn(item);
        when(orderMapper.orderToOrderResponse(order)).thenReturn(new OrderResponse());

        service().createOrder(request, 21L, "USER");

        org.assertj.core.api.Assertions.assertThat(order.getFlashSaleReservationId()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(item.getPrice()).isEqualByComparingTo("60000");
        org.assertj.core.api.Assertions.assertThat(order.getSubtotalPrice()).isEqualByComparingTo("60000");
        org.assertj.core.api.Assertions.assertThat(order.getTotalPrice()).isEqualByComparingTo("75000");
    }

    @Test
    void authoritativeLivestreamPriceIsPersistedIntoSubtotalAndOrderItem() {
        CreateOrderRequest request = baseRequest();
        request.setLivestreamId(UUID.randomUUID());
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L))
                .thenReturn(validatedItem(new BigDecimal("100000")));
        when(livestreamPriceClient.resolve(request.getLivestreamId(), 7L, List.of(9L)))
                .thenReturn(Map.of(9L, new BigDecimal("60000")));
        Order order = persistedMappedOrder(request);
        when(shippingFeeCalculationService.calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("60000")))
                .thenReturn(new BigDecimal("15000"));
        OrderItem item = new OrderItem();
        when(orderMapper.orderItemRequestToOrderItem(request.getItems().get(0))).thenReturn(item);
        when(orderMapper.orderToOrderResponse(order)).thenReturn(new OrderResponse());
        OrderServiceImpl service = service();
        ReflectionTestUtils.setField(service, "livestreamPriceClient", livestreamPriceClient);

        service.createOrder(request, 21L, "USER");

        org.assertj.core.api.Assertions.assertThat(item.getPrice()).isEqualByComparingTo("60000");
        org.assertj.core.api.Assertions.assertThat(order.getSubtotalPrice()).isEqualByComparingTo("60000");
        org.assertj.core.api.Assertions.assertThat(order.getTotalPrice()).isEqualByComparingTo("75000");
    }

    @Test
    void mismatchedLivestreamQuoteItemsFailBeforeOrderPersistence() {
        CreateOrderRequest request = baseRequest();
        request.setQuoteId(UUID.randomUUID());
        request.setLivestreamId(UUID.randomUUID());
        when(checkoutQuoteService.validateAndReprice(request, 21L, 21L)).thenReturn(
                com.delivery.order_service.dto.response.CheckoutPreviewResponse.builder()
                        .restaurantId(7L)
                        .items(List.of(com.delivery.order_service.dto.response.CheckoutPreviewResponse.PreviewItemDetail
                                .builder().menuItemId(99L).unitPrice(new BigDecimal("60000")).quantity(1)
                                .lineTotal(new BigDecimal("60000")).build()))
                        .build());
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L))
                .thenReturn(validatedItem(new BigDecimal("100000")));
        OrderServiceImpl service = service();
        ReflectionTestUtils.setField(service, "checkoutQuoteService", checkoutQuoteService);

        assertThrows(IllegalStateException.class, () -> service.createOrder(request, 21L, "USER"));

        verifyNoInteractions(orderRepository, orderItemRepository, orderEventPublisher);
    }

    @Test
    void enabledInventoryReservationIsCommittedAndSnapshottedWithTheOrder() {
        CreateOrderRequest request = baseRequest();
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L))
                .thenReturn(validatedItem(new BigDecimal("100000")));
        Order order = persistedMappedOrder(request);
        when(shippingFeeCalculationService.calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        when(inventoryReservationClient.reserve(any(UUID.class), eq(101L), eq(21L), eq(21L),
                eq(7L), eq(request.getItems())))
                .thenAnswer(invocation -> new InventoryReservationClient.InventoryReservation(
                        invocation.getArgument(0), 101L, "RESERVED"));
        when(inventoryReservationClient.commit(any(UUID.class), eq(101L)))
                .thenAnswer(invocation -> new InventoryReservationClient.InventoryReservation(
                        invocation.getArgument(0), 101L, "COMMITTED"));
        when(orderMapper.orderItemRequestToOrderItem(request.getItems().get(0))).thenReturn(new OrderItem());
        when(orderMapper.orderToOrderResponse(order)).thenReturn(new OrderResponse());

        OrderServiceImpl service = service();
        ReflectionTestUtils.setField(service, "inventoryReservationEnabled", true);
        ReflectionTestUtils.setField(service, "inventoryReservationClient", inventoryReservationClient);

        service.createOrder(request, 21L, "USER");

        org.assertj.core.api.Assertions.assertThat(order.getInventoryReservationId()).isNotNull();
        verify(inventoryReservationClient).reserve(eq(order.getInventoryReservationId()), eq(101L),
                eq(21L), eq(21L), eq(7L), eq(request.getItems()));
        verify(inventoryReservationClient).commit(order.getInventoryReservationId(), 101L);
        verify(orderEventPublisher).publishOrderCreatedEvent(order);
    }

    @Test
    void laterOutboxFailureReleasesSuccessfulVoucherReservation() {
        CreateOrderRequest request = baseRequest();
        request.setVoucherIds(List.of(55L));
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L))
                .thenReturn(validatedItem(new BigDecimal("100000")));
        Order order = persistedMappedOrder(request);
        when(shippingFeeCalculationService.calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        when(reservationClient.reserveVoucher(any(UUID.class), eq(101L), eq(21L), eq(55L), eq(7L),
                eq(new BigDecimal("100000")), eq(new BigDecimal("15000"))))
                .thenReturn(new CheckoutReservationClient.VoucherQuote(new BigDecimal("20000")));
        when(orderMapper.orderItemRequestToOrderItem(request.getItems().get(0))).thenReturn(new OrderItem());
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(orderEventPublisher).publishOrderCreatedEvent(order);

        assertThrows(IllegalStateException.class, () -> service().createOrder(request, 21L, "USER"));

        verify(reservationClient).releaseVoucher(order.getVoucherReservationId(), 101L);
        org.mockito.Mockito.verify(reservationClient, org.mockito.Mockito.never())
                .releaseFlash(any(UUID.class), any(Long.class));
    }

    @Test
    void ambiguousVoucherReserveTimeoutAttemptsReleaseWithTheSameStableIdentity() {
        CreateOrderRequest request = baseRequest();
        request.setVoucherIds(List.of(55L));
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L))
                .thenReturn(validatedItem(new BigDecimal("100000")));
        Order order = new Order();
        order.setId(101L); order.setRestaurantId(7L);
        when(orderMapper.createOrderRequestToOrder(request)).thenReturn(order);
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(shippingFeeCalculationService.calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        when(reservationClient.reserveVoucher(any(UUID.class), eq(101L), eq(21L), eq(55L), eq(7L),
                eq(new BigDecimal("100000")), eq(new BigDecimal("15000"))))
                .thenThrow(new IllegalStateException("reservation timeout"));

        assertThrows(IllegalStateException.class, () -> service().createOrder(request, 21L, "USER"));

        ArgumentCaptor<UUID> reservationId = ArgumentCaptor.forClass(UUID.class);
        verify(reservationClient).reserveVoucher(reservationId.capture(), eq(101L), eq(21L), eq(55L), eq(7L),
                eq(new BigDecimal("100000")), eq(new BigDecimal("15000")));
        verify(reservationClient).releaseVoucher(reservationId.getValue(), 101L);
    }

    @Test
    void ambiguousFlashReserveReleasesAttemptedIdentityAndRetainsReleaseFailure() {
        CreateOrderRequest request = baseRequest();
        request.getItems().get(0).setFlashSaleItemId(88L);
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L))
                .thenReturn(validatedItem(new BigDecimal("100000")));
        Order order = new Order();
        order.setId(101L);
        when(orderMapper.createOrderRequestToOrder(request)).thenReturn(order);
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        IllegalStateException timeout = new IllegalStateException("ambiguous flash timeout");
        IllegalStateException releaseFailure = new IllegalStateException("release unavailable");
        when(reservationClient.reserveFlash(any(UUID.class), eq(101L), eq(21L), eq(7L), eq(request.getItems())))
                .thenThrow(timeout);
        doThrow(releaseFailure).when(reservationClient).releaseFlash(any(UUID.class), eq(101L));

        org.junit.jupiter.api.Assertions.assertSame(timeout,
                assertThrows(IllegalStateException.class, () -> service().createOrder(request, 21L, "USER")));
        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        verify(reservationClient).reserveFlash(id.capture(), eq(101L), eq(21L), eq(7L), eq(request.getItems()));
        verify(reservationClient).releaseFlash(id.getValue(), 101L);
        org.assertj.core.api.Assertions.assertThat(timeout.getSuppressed()).containsExactly(releaseFailure);
        verifyNoInteractions(orderItemRepository, orderEventPublisher, shippingFeeCalculationService);
    }

    @Test
    void ambiguousPromotionReserveReleasesAttemptedIdentityWithPrincipal() {
        CreateOrderRequest request = baseRequest();
        request.setVoucherIds(List.of(55L));
        request.setSelectionMode("MANUAL");
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L))
                .thenReturn(validatedItem(new BigDecimal("100000")));
        Order order = new Order();
        order.setId(101L);
        when(orderMapper.createOrderRequestToOrder(request)).thenReturn(order);
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(shippingFeeCalculationService.calculateShippingFee(
                10.75, 106.66, 10.8, 106.7, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        IllegalStateException timeout = new IllegalStateException("ambiguous promotion timeout");
        when(reservationClient.reserveVouchers(any(UUID.class), eq(101L), eq(21L), eq(21L), eq(7L),
                eq(new BigDecimal("100000")), eq(new BigDecimal("15000")), eq(List.of(55L))))
                .thenThrow(timeout);

        org.junit.jupiter.api.Assertions.assertSame(timeout,
                assertThrows(IllegalStateException.class, () -> service().createOrder(request, 21L, "USER")));
        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        verify(reservationClient).reserveVouchers(id.capture(), eq(101L), eq(21L), eq(21L), eq(7L),
                eq(new BigDecimal("100000")), eq(new BigDecimal("15000")), eq(List.of(55L)));
        verify(reservationClient).releaseVouchers(id.getValue(), 101L, 21L);
        verifyNoInteractions(orderItemRepository, orderEventPublisher);
    }

    @Test
    void fractionalCanonicalMoneyIsNotRoundedAndClientPriceIsIgnored() {
        CreateOrderRequest request = baseRequest();
        request.getItems().get(0).setQuantity(3);
        request.getItems().get(0).setPrice(new BigDecimal("999999"));
        BigDecimal unitPrice = new BigDecimal("12345.67891");
        BigDecimal subtotal = new BigDecimal("37037.03673");
        when(orderValidationService.validateCreateOrderRequest(request, 21L, 21L))
                .thenReturn(validatedItem(unitPrice));
        Order order = persistedMappedOrder(request);
        when(shippingFeeCalculationService.calculateShippingFee(10.75,106.66,10.8,106.7,subtotal))
                .thenReturn(new BigDecimal("15000"));
        OrderItem item = new OrderItem();
        when(orderMapper.orderItemRequestToOrderItem(request.getItems().get(0))).thenReturn(item);
        when(orderMapper.orderToOrderResponse(order)).thenReturn(new OrderResponse());

        service().createOrder(request, 21L, "USER");

        assertEquals(subtotal, order.getSubtotalPrice());
        assertEquals(new BigDecimal("52037.03673"), order.getTotalPrice());
        assertEquals(unitPrice, item.getPrice());
        verify(orderEventPublisher).publishOrderCreatedEvent(order);
    }

    private CreateOrderRequest baseRequest() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRestaurantId(7L); request.setDeliveryLat(10.8); request.setDeliveryLng(106.7);
        CreateOrderRequest.OrderItemRequest item = new CreateOrderRequest.OrderItemRequest();
        item.setMenuItemId(9L); item.setQuantity(1); request.setItems(List.of(item));
        return request;
    }

    private ValidatedOrderData validatedItem(BigDecimal price) {
        return new ValidatedOrderData(11L, 12L, "Quán", "Địa chỉ", "0900000000", 10.75, 106.66,
                List.of(new ValidatedOrderData.ValidatedItemData(9L, "Cơm", price)));
    }

    private Order persistedMappedOrder(CreateOrderRequest request) {
        Order order = new Order(); order.setId(101L); order.setRestaurantId(7L);
        when(orderMapper.createOrderRequestToOrder(request)).thenReturn(order);
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(orderRepository.save(order)).thenReturn(order);
        return order;
    }

    @Test
    void nonCustomerRoleCannotCreateOrderOrReachCanonicalValidation() {
        CreateOrderRequest request = new CreateOrderRequest();

        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service().createOrder(request, 21L, "SHIPPER"));

        verifyNoInteractions(orderValidationService, shippingFeeCalculationService,
                orderRepository, orderItemRepository, orderEventPublisher,
                orderMapper);
    }

    @Test
    void orderDetailRequiresTheMatchingActorRoleNotOnlyMatchingUserId() {
        Order order = new Order();
        order.setId(101L);
        order.setUserId(21L);
        order.setCreatorId(31L);
        order.setShipperId(41L);
        when(orderRepository.findById(101L)).thenReturn(java.util.Optional.of(order));

        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service().getOrderById(101L, 21L, null));
        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service().getOrderById(101L, 21L, "SHIPPER"));

        verifyNoInteractions(orderMapper, orderEventPublisher);
    }

    @Test
    void customerCancellationRequiresCustomerRoleNotOnlyMatchingUserId() {
        Order order = new Order();
        order.setId(101L);
        order.setUserId(21L);
        order.setStatus(com.delivery.order_service.entity.OrderStatus.PENDING);
        when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));

        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service().cancelOrder(101L, 21L, null, "changed mind"));
        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service().cancelOrder(101L, 21L, "SHIPPER", "changed mind"));

        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.never()).save(any());
        verifyNoInteractions(orderEventPublisher);
    }

    @Test
    void shipperCannotCancelOrderThroughCustomerOrderEndpoint() {
        Order order = new Order();
        order.setId(101L);
        order.setUserId(21L);
        order.setShipperId(33L);
        order.setStatus(com.delivery.order_service.entity.OrderStatus.ASSIGNED);
        when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));

        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service().cancelOrder(101L, 33L, "SHIPPER", "cannot deliver"));

        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(orderEventPublisher);
    }

    @Test
    void adminCancellationRetainsTransitionTableForEveryNonCancelledState() {
        for (var status : com.delivery.order_service.entity.OrderStatus.values()) {
            if (status == com.delivery.order_service.entity.OrderStatus.CANCELLED) continue;
            Order order = new Order();
            order.setId(101L);
            order.setStatus(status);
            when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));
            if (status.canTransitionTo(com.delivery.order_service.entity.OrderStatus.CANCELLED)) {
                when(orderRepository.save(order)).thenReturn(order);
                service().cancelOrder(101L, 901L, 21L, "ADMIN", "admin reason");
                verify(orderEventPublisher).publishOrderCancelledEvent(order, status.name(), 21L,
                        "ADMIN", "ADMIN_CANCELLED");
            } else {
                assertEquals("Invalid order transition: " + status + " -> CANCELLED",
                        assertThrows(IllegalStateException.class,
                                () -> service().cancelOrder(101L, 901L, 21L, "ADMIN", "admin reason")).getMessage());
                org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.never()).save(order);
            }
        }
    }

    @Test
    void shipperReadUsesLegacyUserIdEvenWhenPrincipalEnforcementIsEnabled() {
        Order order = new Order();
        order.setId(101L);
        order.setShipperId(41L);
        when(orderRepository.findById(101L)).thenReturn(java.util.Optional.of(order));
        var service = service();
        ReflectionTestUtils.setField(service, "principalOwnershipEnforced", true);
        service.getOrderById(101L, 999L, 41L, "SHIPPER");
        verify(orderMapper).orderToOrderResponse(order);
        assertEquals("Bạn không có quyền xem đơn hàng này",
                assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                        () -> service.getOrderById(101L, 41L, 999L, "SHIPPER")).getMessage());
    }

    @Test
    void permissionPrecedesCancellationReplayAndConditionsAndMissingOrderPrecedesPermission() {
        var service = service();
        assertEquals("Không tìm thấy đơn hàng với ID: 101",
                assertThrows(com.delivery.order_service.exception.ResourceNotFoundException.class,
                        () -> service.cancelOrder(101L, 21L, null, "reason")).getMessage());
        for (var status : new com.delivery.order_service.entity.OrderStatus[]{
                com.delivery.order_service.entity.OrderStatus.CANCELLED,
                com.delivery.order_service.entity.OrderStatus.PICKED_UP}) {
            Order order = new Order();
            order.setUserId(22L);
            order.setStatus(status);
            when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));
            assertEquals("Bạn không có quyền hủy đơn hàng này",
                    assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                            () -> service.cancelOrder(101L, 21L, "USER", "reason")).getMessage());
        }
        verifyNoInteractions(orderEventPublisher, orderMapper);
        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void principalOwnershipPreventsLegacyFallbackAndReplayRetainsMetrics() {
        Order order = new Order();
        order.setId(101L);
        order.setUserId(21L);
        order.setUserPrincipalId(901L);
        order.setStatus(com.delivery.order_service.entity.OrderStatus.CANCELLED);
        order.setCancelledByPrincipalId(901L);
        order.setCancelledBy(21L);
        order.setCancelReason(null);
        when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));
        when(orderRepository.findById(101L)).thenReturn(java.util.Optional.of(order));
        var service = service();
        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service.getOrderById(101L, 902L, 21L, "USER"));
        service.cancelOrder(101L, 901L, 99L, "USER", null);
        verifyNoInteractions(businessMetrics, orderEventPublisher);
        order.setUserPrincipalId(null);
        order.setCancelledByPrincipalId(null);
        service.cancelOrder(101L, 901L, 21L, "USER", null);
        verify(businessMetrics).identityLegacyFallback("customer_cancel");
        verify(businessMetrics).identityLegacyFallback("cancel_replay");
        ReflectionTestUtils.setField(service, "principalOwnershipEnforced", true);
        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service.cancelOrder(101L, 901L, 21L, "USER", null));
        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void readListAdmissionRetainsPrecedenceBeforeQueriesAndStatusParsing() {
        var service = service();
        var pageable = org.springframework.data.domain.Pageable.unpaged();
        assertEquals("Bạn không có quyền xem đơn hàng của chủ nhà hàng",
                assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                        () -> service.getOrdersByRestaurantOwner(null, null, "USER", pageable)).getMessage());
        assertEquals("Missing authenticated identity",
                assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                        () -> service.getOrdersByRestaurantOwner(null, null, "ADMIN", pageable)).getMessage());
        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service.getOrdersByPrincipal(null, 1L, "ADMIN", pageable));
        assertEquals("Chỉ admin được lọc toàn hệ thống theo trạng thái",
                assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                        () -> service.getOrdersByStatus("invalid", 1L, "USER", pageable)).getMessage());
        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service.getAllOrders(1L, "USER", pageable));
        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service.getOrdersByUser(1L, 2L, "USER", pageable));
        verifyNoInteractions(orderRepository);
    }

    @Test
    void shopOwnerWireRoleRetainsOwnershipFallbackAndTypedCancellation() {
        Order order = new Order();
        order.setId(101L);
        order.setCreatorId(31L);
        order.setStatus(com.delivery.order_service.entity.OrderStatus.ASSIGNED);
        when(orderRepository.findById(101L)).thenReturn(java.util.Optional.of(order));
        when(orderRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        var service = service();
        service.getOrderById(101L, 901L, 31L, "SHOP_OWNER");
        service.cancelOrder(101L, 901L, 31L, "SHOP_OWNER", "closed");
        verify(businessMetrics).identityLegacyFallback("restaurant_owner_read");
        verify(businessMetrics).identityLegacyFallback("restaurant_owner_cancel");
        verify(orderEventPublisher).publishOrderCancelledEvent(order, "ASSIGNED", 31L,
                "RESTAURANT", "RESTAURANT_CANCELLED");
        assertThrows(com.delivery.order_service.exception.AccessDeniedException.class,
                () -> service.getOrderById(101L, 901L, 31L, "RESTAURANT_OWNER"));
    }

    private OrderServiceImpl service() {
        return new OrderServiceImpl(
                orderRepository, orderItemRepository, orderMapper, orderEventPublisher,
                orderValidationService, shippingFeeCalculationService, businessMetrics, reservationClient);
    }
}
