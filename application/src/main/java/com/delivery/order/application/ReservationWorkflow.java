package com.delivery.order.application;
import com.delivery.order.application.api.ReservationIds;
import com.delivery.order.application.api.ReservationPorts;
public final class ReservationWorkflow {
    private ReservationWorkflow() {}
    public static <R> R execute(ReservationPorts<R> ports) {
        ReservationIds ids = new ReservationIds();
        try {
            if (ports.inventoryEnabled()) {
                ids.inventory = ports.newId();
                ports.reserveInventory(ids.inventory);
            }
            if (ports.hasFlash()) {
                ids.flash = ports.newId();
                ports.reserveFlash(ids.flash);
            }
            ports.priceAndReserveVouchers(ids);
            ports.snapshot();
            if (ids.inventory != null) ports.commitInventory(ids.inventory);
            if (ports.hasQuote()) ports.consumeQuote();
            if (ports.hasReceipt()) ports.completeReceipt();
            ports.publishCreated();
            return ports.response();
        } catch (RuntimeException failure) {
            if (ids.voucher != null) release(() -> ports.releaseVoucher(ids.voucher), failure);
            if (ids.promotion != null) release(() -> ports.releasePromotion(ids.promotion), failure);
            if (ids.flash != null) release(() -> ports.releaseFlash(ids.flash), failure);
            if (ids.inventory != null) release(() -> ports.releaseInventory(ids.inventory), failure);
            throw failure;
        }
    }
    private static void release(Runnable operation, RuntimeException failure) {
        try { operation.run(); }
        catch (RuntimeException releaseFailure) { failure.addSuppressed(releaseFailure); }
    }
}
