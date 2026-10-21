package com.delivery.order_service.dto.response;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderItemResponse {

    private Long id;
    private Long menuItemId;
    private String menuItemName;
    private Integer quantity;
    private BigDecimal price;
    private String notes;

}
