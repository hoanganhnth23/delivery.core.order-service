package com.delivery.order_service.mapper;

import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.dto.response.OrderResponse;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderItem;
import com.delivery.order_service.entity.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMapperTest {

    private final OrderMapper mapper = new OrderMapper();

    @Test
    void mapsCreateEntityItemsAndResponseWithoutGeneratedCode() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRestaurantId(30L);
        request.setPaymentMethod("COD");
        request.setDeliveryAddress("Customer");
        request.setCustomerName("An");
        request.setCustomerPhone("0900000000");

        Order order = mapper.createOrderRequestToOrder(request);
        OrderItem item = new OrderItem();
        item.setId(2L);
        item.setMenuItemId(40L);
        item.setMenuItemName("Pho");
        item.setQuantity(2);
        item.setPrice(new BigDecimal("50000"));
        order.setId(1L);
        order.setItems(List.of(item));

        OrderResponse response = mapper.orderToOrderResponse(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getRestaurantId()).isEqualTo(30L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getPaymentMethod()).isEqualTo("COD");
        assertThat(response.getItems()).singleElement()
                .satisfies(mapped -> assertThat(mapped.getMenuItemName()).isEqualTo("Pho"));
    }

    @Test
    void orderResponseAndItemsHandleNullAndEmptyCollections() {
        assertThat(mapper.orderToOrderResponse(null)).isNull();
        assertThat(mapper.orderItemsToOrderItemResponses(List.of())).isEmpty();
    }
}
