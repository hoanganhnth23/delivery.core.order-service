package com.delivery.order.application.api;
import java.util.UUID;
/** Mutable request-local journal including reservations whose outcome is unknown. */
public final class ReservationIds {
    public UUID voucher;
    public UUID promotion;
    public UUID flash;
    public UUID inventory;
}
