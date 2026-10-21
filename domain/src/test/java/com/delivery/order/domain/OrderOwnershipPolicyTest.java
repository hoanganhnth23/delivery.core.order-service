package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.delivery.order.domain.OrderOwnershipPolicy.*;

class OrderOwnershipPolicyTest {
    @Test
    void exhaustiveRolePrincipalLegacyEnforcementAndOperationMatrix() {
        Long[] ids = {null, 1L, 2L};
        for (String role : new String[]{null, "", "user", "OTHER", "ADMIN", "USER", "SHOP_OWNER", "SHIPPER"}) {
            for (Long stored : ids) for (Long principal : ids) for (Long legacy : ids) {
                for (boolean enforced : new boolean[]{false, true}) for (boolean cancel : new boolean[]{false, true}) {
                    var owners = new Owners(stored, 1L, stored, 1L, 1L);
                    Access expected = Access.DENIED;
                    if ("ADMIN".equals(role)) expected = Access.ALLOWED;
                    else if ("USER".equals(role) || "SHOP_OWNER".equals(role)) {
                        if (stored != null && stored.equals(principal)) expected = Access.ALLOWED;
                        else if (!enforced && stored == null && Long.valueOf(1).equals(legacy)) {
                            expected = "USER".equals(role) ? Access.CUSTOMER_LEGACY : Access.RESTAURANT_LEGACY;
                        }
                    } else if (!cancel && "SHIPPER".equals(role) && Long.valueOf(1).equals(legacy)) expected = Access.ALLOWED;
                    assertEquals(expected, access(owners, principal, legacy, role, enforced, cancel));
                }
            }
        }
    }

    @Test
    void ownerFieldsRemainIndependentAndPersistedNullLegacyIdsRetainFailure() {
        var owners = new Owners(1L, 2L, 3L, 4L, 5L);
        assertEquals(Access.DENIED, access(owners, 1L, 4L, "SHOP_OWNER", false, false));
        assertEquals(Access.ALLOWED, access(owners, 3L, 2L, "SHOP_OWNER", true, true));
        assertEquals(Access.ALLOWED, access(owners, 99L, 5L, "SHIPPER", true, false));
        assertEquals(Access.DENIED, access(owners, 5L, 99L, "SHIPPER", false, false));
        var malformed = new Owners(null, null, null, null, null);
        assertThrows(NullPointerException.class, () -> access(malformed, null, null, "USER", false, false));
        assertThrows(NullPointerException.class, () -> access(malformed, null, null, "SHOP_OWNER", false, true));
    }

    @Test
    void listAdmissionMessagesAndPrecedence() {
        assertNull(userListDenial(null, null, "ADMIN"));
        assertNull(userListDenial(1L, 1L, "USER"));
        assertEquals("Bạn chỉ có thể xem đơn hàng của chính mình", userListDenial(1L, 2L, null));
        assertThrows(NullPointerException.class, () -> userListDenial(null, 1L, "USER"));
        for (Long principal : new Long[]{null, 1L}) for (Long legacy : new Long[]{null, 2L}) {
            String missing = principal == null || legacy == null ? "Missing authenticated identity" : null;
            assertEquals(missing, principalListDenial(principal, legacy));
            assertEquals(missing, restaurantOwnerListDenial(principal, legacy, "ADMIN"));
            assertEquals(missing, restaurantOwnerListDenial(principal, legacy, "SHOP_OWNER"));
            assertEquals("Bạn không có quyền xem đơn hàng của chủ nhà hàng", restaurantOwnerListDenial(principal, legacy, null));
        }
        assertNull(globalListDenial("ADMIN", true));
        assertNull(globalListDenial("ADMIN", false));
        assertEquals("Chỉ admin được lọc toàn hệ thống theo trạng thái", globalListDenial(null, true));
        assertEquals("Bạn không có quyền xem tất cả đơn hàng", globalListDenial("USER", false));
    }
}
