package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static com.delivery.order.domain.CheckoutAdmissionPolicy.*;

class CheckoutAdmissionPolicyTest {
    private static class Cart {
        Long restaurant = 1L, user = 2L;
        String address = "Address", name = "Name", phone = "0901234567", payment = "COD", notes, mode;
        List<Long> vouchers;
        List<Item> items = List.of(new Item(1L, 1, null, null));
        boolean live, voucherEnabled = true, flashEnabled = true, liveEnabled = true, stacking = true;
        Double lat = 10.0, lng = 106.0;
        List<String> errors() {
            return CheckoutAdmissionPolicy.errors(new Input(restaurant, address, name, phone, payment, notes,
                    vouchers, mode, items, live, lat, lng), user,
                    new Capabilities(voucherEnabled, flashEnabled, liveEnabled, () -> stacking));
        }
    }

    @Test void requiredFieldsAndBoundaryLengths() {
        assertTrue(new Cart().errors().isEmpty());
        for (Long id : Arrays.asList(null, 0L, -1L)) {
            Cart c = new Cart(); c.restaurant = id; assertTrue(c.errors().get(0).startsWith("Restaurant ID"));
            c = new Cart(); c.user = id; assertEquals(List.of("User ID không hợp lệ"), c.errors());
        }
        for (String value : Arrays.asList(null, "", "  ")) {
            Cart c = new Cart(); c.address = value; assertTrue(c.errors().get(0).startsWith("Địa chỉ"));
            c = new Cart(); c.name = value; assertTrue(c.errors().get(0).startsWith("Tên khách"));
            c = new Cart(); c.phone = value; assertTrue(c.errors().get(0).contains("không được để trống"));
            c = new Cart(); c.payment = value; assertTrue(c.errors().get(0).startsWith("Phương thức"));
        }
        Cart c = new Cart(); c.address = "a".repeat(500); c.name = "n".repeat(100); c.notes = "x".repeat(1000);
        assertTrue(c.errors().isEmpty());
        c.address += "a"; c.name += "n"; c.notes += "x";
        assertEquals(3, c.errors().size());
        for (String method : List.of("cod", "ONLINE", " COD")) {
            c = new Cart(); c.payment = method;
            assertEquals(List.of("MVP hiện chỉ hỗ trợ thanh toán COD"), c.errors());
        }
        for (String phone : List.of("+84901234567", "84901234567", "090 123 4567")) {
            c = new Cart(); c.phone = phone; assertTrue(c.errors().isEmpty());
        }
        c.phone = "0123456789";
        assertEquals(List.of("Số điện thoại khách hàng không đúng định dạng Việt Nam"), c.errors());
        assertTrue(customerAllowed("USER"));
        for (String role : Arrays.asList(null, "user", "ADMIN", "SHOP_OWNER", "SHIPPER", "")) assertFalse(customerAllowed(role));
    }

    @Test void itemLimitsNullsDuplicatesAndValidationOrder() {
        for (List<Item> items : Arrays.asList(null, List.<Item>of())) {
            Cart c = new Cart(); c.items = items; c.phone = "bad"; c.live = true; c.liveEnabled = false;
            // Existing early return skips phone/livestream validation if there are no items.
            assertEquals(List.of("Đơn hàng phải có ít nhất một sản phẩm"), c.errors());
        }
        Cart c = new Cart(); c.items = IntStream.rangeClosed(1, 50).mapToObj(i -> new Item((long)i, 99, "n".repeat(500), null)).toList();
        assertTrue(c.errors().isEmpty());
        c.items = IntStream.rangeClosed(1, 51).mapToObj(i -> new Item((long)i, 1, null, null)).toList();
        assertEquals(List.of("Đơn hàng không được vượt quá 50 sản phẩm"), c.errors());
        for (Long id : Arrays.asList(null, 0L, -1L, 1L)) {
            for (Integer qty : Arrays.asList(null, -1, 0, 1, 99, 100)) {
                c = new Cart(); c.items = List.of(new Item(id, qty, null, null));
                assertEquals((id == null || id <= 0 ? 1 : 0) + (qty == null || qty <= 0 || qty > 99 ? 1 : 0), c.errors().size());
            }
        }
        c = new Cart(); c.items = Arrays.asList(null, new Item(null, null, "n".repeat(501), null),
                new Item(1L, 0, null, null), new Item(1L, 100, null, null));
        assertEquals(List.of("Sản phẩm 1: dữ liệu sản phẩm không hợp lệ", "Sản phẩm 2: Menu Item ID không được để trống",
                "Sản phẩm 2: Số lượng không được để trống", "Sản phẩm 2: Ghi chú sản phẩm không được vượt quá 500 ký tự",
                "Sản phẩm 3: Số lượng phải lớn hơn 0", "Sản phẩm 4: Số lượng không được vượt quá 99",
                "Sản phẩm 4: Menu Item ID bị trùng"), c.errors());
    }

    @Test void voucherCapabilityMatrixAndInvalidSelections() {
        for (String mode : Arrays.asList(null, "", " ", "AUTO", "manual", "bad")) {
            for (int count = 0; count <= 4; count++) {
                for (boolean stacking : List.of(false, true)) for (boolean legacy : List.of(false, true)) {
                    Cart c = new Cart(); c.mode = mode; c.stacking = stacking; c.voucherEnabled = legacy;
                    c.vouchers = IntStream.rangeClosed(1, count).mapToObj(i -> (long)i).toList();
                    List<String> expected = new ArrayList<>();
                    boolean modePresent = mode != null && !mode.isBlank();
                    if ("bad".equals(mode)) expected.add("Selection mode chỉ hỗ trợ AUTO hoặc MANUAL");
                    if (count > 3) expected.add("Mỗi đơn chỉ được dùng tối đa ba voucher");
                    else if ((modePresent || count > 1) && !stacking) expected.add("Voucher stacking checkout chưa được mở cho tài khoản này");
                    else if (count == 1 && !modePresent && !legacy) expected.add("Voucher checkout chưa được mở trong MVP cho tới khi có discount và compensation proof");
                    assertEquals(expected, c.errors());
                }
            }
        }
        for (List<Long> ids : List.of(Arrays.asList((Long)null), List.of(0L), List.of(-1L), List.of(1L,1L))) {
            Cart c = new Cart(); c.vouchers = ids;
            assertEquals(List.of("Voucher ID phải là số dương và không được trùng"), c.errors());
        }
        Input noStack = new Input(1L,"a","n","0901234567","COD",null,null,null,List.of(new Item(1L,1,null,null)),false,10.0,106.0);
        assertTrue(CheckoutAdmissionPolicy.errors(noStack, 1L, new Capabilities(false,false,false, () -> { throw new AssertionError("unexpected capability call"); })).isEmpty());
    }

    @Test void featureCombinationsAccumulateInOriginalOrder() {
        for (boolean live : List.of(false,true)) for (boolean flash : List.of(false,true))
            for (boolean voucher : List.of(false,true)) for (boolean liveEnabled : List.of(false,true))
                for (boolean flashEnabled : List.of(false,true)) {
                    Cart c = new Cart(); c.live = live; c.liveEnabled = liveEnabled; c.flashEnabled = flashEnabled;
                    c.items = List.of(new Item(1L,1,null,flash ? 5L : null)); c.vouchers = voucher ? List.of(3L) : null;
                    List<String> expected = new ArrayList<>();
                    if (flash && !flashEnabled) expected.add("Sản phẩm 1: Flash Sale checkout chưa được mở trong MVP cho tới khi reservation có idempotency/compensation proof");
                    if (live && !liveEnabled) expected.add("Livestream checkout chưa được mở");
                    if (live && flash) expected.add("Livestream và Flash Sale không được áp dụng cùng một đơn");
                    if (voucher && flash) expected.add("Voucher và Flash Sale không được áp dụng cùng một đơn");
                    assertEquals(expected, c.errors());
                }
    }

    @Test void coordinatesAndMultiErrorPrecedenceIncludingExistingNanAdmission() {
        for (Double lat : Arrays.asList(null,7.99,8.0,24.0,24.01,Double.NaN,Double.POSITIVE_INFINITY))
            for (Double lng : Arrays.asList(null,101.99,102.0,110.0,110.01,Double.NaN,Double.NEGATIVE_INFINITY)) {
                Cart c = new Cart(); c.lat = lat; c.lng = lng;
                int count = lat == null || lng == null ? 1 : (lat < 8 || lat > 24 ? 1 : 0) + (lng < 102 || lng > 110 ? 1 : 0);
                assertEquals(count,c.errors().size());
            }
        Cart c = new Cart(); c.restaurant = null; c.items = List.of(new Item(null,null,null,null)); c.lat = null; c.user = null;
        assertEquals(List.of("Restaurant ID không được để trống", "Sản phẩm 1: Menu Item ID không được để trống",
                "Sản phẩm 1: Số lượng không được để trống", "Tọa độ giao hàng (latitude và longitude) là bắt buộc",
                "User ID không hợp lệ"),c.errors());
    }
}
