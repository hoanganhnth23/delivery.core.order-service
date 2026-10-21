package com.delivery.order.application;
import com.delivery.order.application.api.ReservationIds;
import com.delivery.order.application.api.VoucherPorts;
import com.delivery.order.domain.CheckoutReservationPolicy;
import java.util.List;
public final class VoucherWorkflow {
    private VoucherWorkflow() {}
    public static void reserve(VoucherPorts ports, ReservationIds journal) {
        List<Long> ids = ports.selectedIds();
        if (CheckoutReservationPolicy.needsAuto(ports.mode(), ids)) ids = ports.autoSelect();
        switch (CheckoutReservationPolicy.rail(ports.mode(), ids)) {
            case PROMOTION -> {
                journal.promotion = ports.newId();
                ports.reservePromotion(journal.promotion, ids);
            }
            case LEGACY -> {
                journal.voucher = ports.newId();
                ports.reserveLegacy(journal.voucher, ids.get(0));
            }
            case NONE -> { }
        }
    }
}
