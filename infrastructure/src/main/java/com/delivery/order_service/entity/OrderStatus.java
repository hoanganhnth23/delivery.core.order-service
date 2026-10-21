package com.delivery.order_service.entity;


/** Canonical public and persistence vocabulary for the COD order lifecycle. */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FINDING_SHIPPER,
    WAIT_SHIPPER_CONFIRM,
    ASSIGNED,
    PICKED_UP,
    DELIVERING,
    DELIVERED,
    CANCELLED,
    SHIPPER_NOT_FOUND;

    public static OrderStatus fromExternal(String value) {
        try {
            return valueOf(com.delivery.order.domain.OrderStatus.fromExternal(value).name());
        } catch (IllegalArgumentException invalid) {
            // Enum.valueOf includes the Java class name in its error message.
            // Preserve the host's public error vocabulary after extraction.
            String domainPrefix = "No enum constant " + com.delivery.order.domain.OrderStatus.class.getName() + ".";
            if (invalid.getMessage().startsWith(domainPrefix)) {
                throw new IllegalArgumentException("No enum constant " + OrderStatus.class.getName() + "."
                        + invalid.getMessage().substring(domainPrefix.length()));
            }
            throw invalid;
        }
    }

    public com.delivery.order.domain.OrderStatus toDomain() {
        return com.delivery.order.domain.OrderStatus.valueOf(name());
    }

    public boolean isTerminal() {
        return toDomain().isTerminal();
    }

    public boolean canTransitionTo(OrderStatus target) {
        return toDomain().canTransitionTo(target == null ? null : target.toDomain());
    }

    public void requireTransitionTo(OrderStatus target) {
        toDomain().requireTransitionTo(target == null ? null : target.toDomain());
    }
}
