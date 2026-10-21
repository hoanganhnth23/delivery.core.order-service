package com.delivery.order.application.api;
import java.util.List;
import java.util.UUID;
public interface VoucherPorts {
    List<Long> selectedIds();
    String mode();
    List<Long> autoSelect();
    UUID newId();
    void reservePromotion(UUID id, List<Long> ids);
    void reserveLegacy(UUID id, Long voucherId);
}
