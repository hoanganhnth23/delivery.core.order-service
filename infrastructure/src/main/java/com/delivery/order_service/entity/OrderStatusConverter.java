package com.delivery.order_service.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Reads legacy rows as canonical states and writes only canonical names. */
@Converter
public class OrderStatusConverter implements AttributeConverter<OrderStatus, String> {

    @Override
    public String convertToDatabaseColumn(OrderStatus attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public OrderStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : OrderStatus.fromExternal(dbData);
    }
}
