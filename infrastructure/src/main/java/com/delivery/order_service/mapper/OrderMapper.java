package com.delivery.order_service.mapper;

import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.dto.response.OrderItemResponse;
import com.delivery.order_service.dto.response.OrderResponse;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderItem;
import com.delivery.order_service.entity.OrderStatus;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Component
public class OrderMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Order createOrderRequestToOrder(CreateOrderRequest request) {
        if (request == null) {
            return null;
        }

        Order order = new Order();
        order.setRestaurantId(request.getRestaurantId());
        order.setPaymentMethod(request.getPaymentMethod());
        order.setDeliveryAddress(request.getDeliveryAddress());
        order.setDeliveryLat(request.getDeliveryLat());
        order.setDeliveryLng(request.getDeliveryLng());
        order.setCustomerName(request.getCustomerName());
        order.setCustomerPhone(request.getCustomerPhone());
        order.setNotes(request.getNotes());
        order.setStatus(OrderStatus.PENDING);
        return order;
    }

    public OrderResponse orderToOrderResponse(Order order) {
        if (order == null) {
            return null;
        }

        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setUserId(order.getUserId());
        response.setRestaurantId(order.getRestaurantId());
        response.setRestaurantName(order.getRestaurantName());
        response.setRestaurantAddress(order.getRestaurantAddress());
        response.setRestaurantPhone(order.getRestaurantPhone());
        response.setShipperId(order.getShipperId());
        response.setSubtotalPrice(order.getSubtotalPrice());
        response.setDiscountAmount(order.getDiscountAmount());
        response.setShippingFee(order.getShippingFee());
        response.setTotalPrice(order.getTotalPrice());
        response.setItemDiscount(order.getItemDiscount());
        response.setShippingDiscount(order.getShippingDiscount());
        response.setCustomerShippingFee(order.getCustomerShippingFee());
        response.setGrossShippingFee(order.getGrossShippingFee());
        response.setPlatformSubsidy(order.getPlatformSubsidy());
        response.setShopDiscount(order.getShopDiscount());
        response.setPromotionReservationId(order.getPromotionReservationId());
        if (order.getPromotionBreakdown() != null && !order.getPromotionBreakdown().isBlank()) {
            try {
                response.setAppliedVouchers(objectMapper.readValue(order.getPromotionBreakdown(),
                        new TypeReference<List<OrderResponse.PromotionBreakdown>>() {}));
            } catch (Exception ignored) {
                response.setAppliedVouchers(List.of());
            }
        }
        response.setStatus(order.getStatus() == null ? null : order.getStatus().name());
        response.setPaymentMethod(order.getPaymentMethod());
        response.setDeliveryAddress(order.getDeliveryAddress());
        response.setDeliveryLat(order.getDeliveryLat());
        response.setDeliveryLng(order.getDeliveryLng());
        response.setPickupLat(order.getPickupLat());
        response.setPickupLng(order.getPickupLng());
        response.setRestaurantLat(order.getPickupLat());
        response.setRestaurantLng(order.getPickupLng());
        response.setCustomerName(order.getCustomerName());
        response.setCustomerPhone(order.getCustomerPhone());
        response.setNotes(order.getNotes());
        response.setCreatedAt(order.getCreatedAt());
        response.setUpdatedAt(order.getUpdatedAt());
        response.setCreatorId(order.getCreatorId());
        response.setCancelReason(order.getCancelReason());
        response.setItems(orderItemsToOrderItemResponses(order.getItems()));
        return response;
    }

    public OrderItem orderItemRequestToOrderItem(CreateOrderRequest.OrderItemRequest request) {
        if (request == null) {
            return null;
        }
        OrderItem item = new OrderItem();
        item.setFlashSaleItemId(request.getFlashSaleItemId());
        item.setMenuItemId(request.getMenuItemId());
        item.setMenuItemName(request.getMenuItemName());
        item.setQuantity(request.getQuantity());
        item.setPrice(request.getPrice());
        item.setNotes(request.getNotes());
        return item;
    }

    public OrderItemResponse orderItemToOrderItemResponse(OrderItem item) {
        if (item == null) {
            return null;
        }
        OrderItemResponse response = new OrderItemResponse();
        response.setId(item.getId());
        response.setMenuItemId(item.getMenuItemId());
        response.setMenuItemName(item.getMenuItemName());
        response.setQuantity(item.getQuantity());
        response.setPrice(item.getPrice());
        response.setNotes(item.getNotes());
        return response;
    }

    public List<OrderItemResponse> orderItemsToOrderItemResponses(List<OrderItem> items) {
        if (items == null) {
            return null;
        }
        List<OrderItemResponse> responses = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            responses.add(orderItemToOrderItemResponse(item));
        }
        return responses;
    }
}
