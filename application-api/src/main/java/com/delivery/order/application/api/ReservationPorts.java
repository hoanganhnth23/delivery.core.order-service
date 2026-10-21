package com.delivery.order.application.api;
import java.util.UUID;
/** IDs are allocated by the workflow before any potentially ambiguous remote call. */
public interface ReservationPorts<R> {
    boolean inventoryEnabled();
    boolean hasFlash();
    UUID newId();
    void reserveInventory(UUID id);
    void reserveFlash(UUID id);
    void priceAndReserveVouchers(ReservationIds ids);
    void snapshot();
    void commitInventory(UUID id);
    boolean hasQuote();
    void consumeQuote();
    boolean hasReceipt();
    void completeReceipt();
    void publishCreated();
    R response();
    void releaseVoucher(UUID id);
    void releasePromotion(UUID id);
    void releaseFlash(UUID id);
    void releaseInventory(UUID id);
}
