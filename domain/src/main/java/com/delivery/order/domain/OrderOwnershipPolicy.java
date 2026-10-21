package com.delivery.order.domain;

/** Principal ownership with the existing, explicitly gated unmigrated-row fallback. */
public final class OrderOwnershipPolicy {
    private OrderOwnershipPolicy() { }

    public enum Access { ALLOWED, CUSTOMER_LEGACY, RESTAURANT_LEGACY, DENIED }
    public record Owners(Long userPrincipalId, Long userId, Long creatorPrincipalId,
                         Long creatorId, Long shipperId) { }

    public static Access access(Owners owners, Long principalId, Long legacyUserId,
                               String role, boolean enforced, boolean cancellation) {
        if ("ADMIN".equals(role)) return Access.ALLOWED;
        if ("USER".equals(role)) {
            if (owners.userPrincipalId != null && principalId != null
                    && owners.userPrincipalId.equals(principalId)) return Access.ALLOWED;
            if (!enforced && owners.userPrincipalId == null && owners.userId.equals(legacyUserId)) {
                return Access.CUSTOMER_LEGACY;
            }
        }
        if ("SHOP_OWNER".equals(role)) {
            if (owners.creatorPrincipalId != null && principalId != null
                    && owners.creatorPrincipalId.equals(principalId)) return Access.ALLOWED;
            if (!enforced && owners.creatorPrincipalId == null && owners.creatorId.equals(legacyUserId)) {
                return Access.RESTAURANT_LEGACY;
            }
        }
        // Deliberately retain legacy user ID rather than canonical shipper identity.
        if (!cancellation && "SHIPPER".equals(role) && legacyUserId != null
                && legacyUserId.equals(owners.shipperId)) return Access.ALLOWED;
        return Access.DENIED;
    }

    /** Null means admitted; the host maps denial text to its existing exception type. */
    public static String userListDenial(Long userId, Long requesterId, String role) {
        return !"ADMIN".equals(role) && !userId.equals(requesterId)
                ? "Bạn chỉ có thể xem đơn hàng của chính mình" : null;
    }

    public static String principalListDenial(Long principalId, Long legacyId) {
        return principalId == null || legacyId == null ? "Missing authenticated identity" : null;
    }

    public static String restaurantOwnerListDenial(Long principalId, Long legacyId, String role) {
        if (!"ADMIN".equals(role) && !"SHOP_OWNER".equals(role)) {
            return "Bạn không có quyền xem đơn hàng của chủ nhà hàng";
        }
        return principalListDenial(principalId, legacyId);
    }

    public static String globalListDenial(String role, boolean statusFilter) {
        if ("ADMIN".equals(role)) return null;
        return statusFilter ? "Chỉ admin được lọc toàn hệ thống theo trạng thái"
                : "Bạn không có quyền xem tất cả đơn hàng";
    }
}
