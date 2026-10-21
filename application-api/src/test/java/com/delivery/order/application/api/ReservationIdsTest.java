package com.delivery.order.application.api;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReservationIdsTest {
    @Test void journalStartsWithoutAttemptedReservations() {
        ReservationIds ids = new ReservationIds();
        assertNull(ids.voucher); assertNull(ids.promotion); assertNull(ids.flash); assertNull(ids.inventory);
    }
}
