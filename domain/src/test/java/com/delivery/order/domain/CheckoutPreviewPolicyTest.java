package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.delivery.order.domain.CheckoutPreviewPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutPreviewPolicyTest {
    private final Capabilities all = new Capabilities(true, true, true, true, () -> true);
    private final Item item = new Item(1L, 1, null);
    private Input input(List<Item> items, String coupon, Long voucher, List<Long> ids, String mode, UUID live) {
        return new Input(items, coupon, voucher, ids, mode, live);
    }
    private Input simple(List<Item> items) { return input(items, null, null, null, null, null); }
    private void rejected(Input input, Capabilities caps, String message) {
        assertEquals(message, assertThrows(ValidationException.class, () -> admit(input, caps)).getMessage());
    }
    @Test void itemAdmissionBoundariesAndPrecedence() {
        rejected(null, all, "Dữ liệu checkout không được để trống");
        for (List<Item> items : Arrays.asList(null, List.<Item>of()))
            rejected(simple(items), all, "Checkout phải có ít nhất một sản phẩm");
        rejected(simple(Collections.nCopies(51, item)), all, "Checkout không được vượt quá 50 sản phẩm");
        for (Item invalid : Arrays.asList(null, new Item(null, 1, null)))
            rejected(simple(Collections.singletonList(invalid)), all, "Checkout chứa sản phẩm không hợp lệ");
        for (Integer quantity : Arrays.asList(null, 0, -1, 100))
            rejected(simple(List.of(new Item(1L, quantity, null))), all, "Checkout chứa số lượng hoặc flash-sale item không hợp lệ");
        for (long flash : new long[]{0, -1})
            rejected(simple(List.of(new Item(1L, 1, flash))), all, "Checkout chứa số lượng hoặc flash-sale item không hợp lệ");
        rejected(simple(List.of(item, item)), all, "Menu Item ID bị trùng trong checkout preview");
        rejected(simple(List.of(new Item(1L, 1, 5L), new Item(2L, 1, 5L))), all,
                "Flash Sale Item ID bị trùng trong checkout preview");
        for (int quantity : new int[]{1, 99}) assertFalse(admit(simple(List.of(new Item(-1L, quantity, null))), all).hasFlashSale());
        assertEquals(50, java.util.stream.LongStream.range(1, 51).mapToObj(id -> new Item(id, 1, null)).toList().size());
        admit(simple(java.util.stream.LongStream.range(1, 51).mapToObj(id -> new Item(id, 1, null)).toList()), all);
        rejected(input(List.of(item, item), "bad", -1L, null, "bad", null), all, "Menu Item ID bị trùng trong checkout preview");
    }
    @Test void voucherAdmissionAndLazyCapability() {
        rejected(input(List.of(item), "CODE", -1L, null, "bad", null), all, "Hãy lưu mã voucher vào ví trước khi báo giá checkout");
        for (List<Long> ids : Arrays.asList(Arrays.asList((Long)null), List.of(0L), List.of(-1L), List.of(1L, 1L), List.of(1L, 2L, 3L, 4L)))
            rejected(input(List.of(item), null, null, ids, "AUTO", null), all, "Tối đa 3 voucher khác nhau, mỗi lớp một voucher");
        for (List<Long> conflicting : List.of(List.<Long>of(), List.of(2L)))
            rejected(input(List.of(item), null, 1L, conflicting, "bad", null), all, "Không được gửi đồng thời voucherId và selectedVoucherIds");
        rejected(input(List.of(item), null, null, null, "bad", null), all, "Selection mode chỉ hỗ trợ AUTO hoặc MANUAL");
        for (String mode : Arrays.asList(null, "", " "))
            rejected(input(List.of(item), null, null, List.of(1L), mode, null), all, "Một voucher trong selectedVoucherIds phải đi kèm selectionMode rõ ràng");
        rejected(input(List.of(item), null, null, null, "manual", null), all, "Manual voucher mode requires selected voucher IDs");
        Capabilities noStack = new Capabilities(true, true, true, true, () -> false);
        rejected(input(List.of(item), null, null, null, "auto", null), noStack, "Voucher stacking checkout is not enabled for this account");
        Capabilities noVoucher = new Capabilities(false, true, true, true, () -> { throw new AssertionError("unexpected stacking call"); });
        rejected(input(List.of(item), null, 1L, null, null, null), noVoucher, "Voucher checkout is disabled");
        rejected(input(List.of(item), null, null, List.of(), "", null), noVoucher, "Voucher checkout is disabled");
        assertFalse(admit(simple(List.of(item)), noVoucher).hasVoucherSelection());
        assertEquals(List.of(1L), admit(input(List.of(item), " ", 1L, null, null, null), all).selectedVoucherIds());
        for (String mode : new String[]{"AUTO", "auto", "MANUAL", "manual"})
            assertTrue(admit(input(List.of(item), null, null, List.of(1L), mode, null), all).hasVoucherSelection());
        admit(input(List.of(item), null, null, List.of(1L, 2L, 3L), null, null), all);
        admit(input(List.of(item), null, null, List.of(), null, null), all);
    }
    @Test void featureIncompatibilitiesAndCapabilityPrecedence() {
        List<Item> flash = List.of(new Item(1L, 1, 3L));
        UUID live = UUID.randomUUID();
        rejected(input(flash, null, null, null, null, live), new Capabilities(true, false, false, false, () -> true), "Flash-sale checkout is disabled");
        rejected(input(flash, null, 1L, null, null, live), all, "Livestream và Flash Sale không được áp dụng cùng một đơn");
        rejected(input(flash, null, 1L, null, null, null), all, "Voucher và Flash Sale không được áp dụng cùng một đơn");
        Capabilities noReservations = new Capabilities(true, true, false, false, () -> true);
        rejected(simple(flash), noReservations, "Checkout reservation capability is unavailable");
        rejected(input(List.of(item), null, 1L, null, null, live), noReservations, "Checkout reservation capability is unavailable");
        rejected(input(List.of(item), null, null, null, null, live), noReservations, "Livestream checkout capability is unavailable");
        admit(simple(List.of(item)), noReservations);
        assertTrue(admit(simple(flash), all).hasFlashSale());
        admit(input(List.of(item), null, null, null, null, live), all);
    }
    @Test void catalogCoercionAvailabilityAndDuplicateLastWins() {
        assertThrows(ValidationException.class, () -> parseValidatedItems(Map.of()));
        assertThrows(ValidationException.class, () -> parseValidatedItems(Map.of("itemValidations", List.of("bad"))));
        for (Object price : Arrays.asList(null, "bad", "NaN", "Infinity", "0", "-1", "1.23400")) {
            for (Object name : Arrays.asList(null, "", " ", "Food")) {
                for (Object available : Arrays.asList(null, false, "false", true, "TRUE")) {
                    for (Object stock : Arrays.asList(null, false, "false", true, "unknown")) {
                        Map<String,Object> raw = new HashMap<>();
                        raw.put("menuItemId", "1"); raw.put("actualPrice", price); raw.put("menuItemName", name);
                        raw.put("isAvailable", available); raw.put("hasEnoughStock", stock);
                        ValidatedPreviewItem parsed = parseValidatedItems(Map.of("itemValidations", List.of(raw))).get(1L);
                        boolean expected = "1.23400".equals(price) && "Food".equals(name)
                                && (Boolean.TRUE.equals(available) || "TRUE".equals(available))
                                && !Boolean.FALSE.equals(stock) && !"false".equals(stock) && !"unknown".equals(stock);
                        assertEquals(expected, parsed.available(), raw.toString());
                        assertEquals(!expected, unavailable(parsed));
                        if (expected) assertEquals(new BigDecimal("1.23400"), parsed.price());
                    }
                }
            }
        }
        assertTrue(unavailable(null));
        assertTrue(parseValidatedItems(Map.of("itemValidations", List.of(Map.of("menuItemId", "bad"), Map.of()))).isEmpty());
        Map<String,Object> first = Map.of("menuItemId", 1, "menuItemName", "First");
        Map<String,Object> last = Map.of("menuItemId", 1, "menuItemName", "Last");
        assertEquals("Last", parseValidatedItems(Map.of("itemValidations", List.of(first,last))).get(1L).name());
        requireAvailable(List.of());
        assertEquals("Checkout chứa món không khả dụng: [2, 1]", assertThrows(ValidationException.class, () -> requireAvailable(List.of(2L,1L))).getMessage());
    }
    @Test void canonicalRestaurantCoordinatesAndErrorMessages() {
        assertEquals(Map.of(), requireMap(Map.of(), "map"));
        assertEquals("map", assertThrows(ValidationException.class, () -> requireMap(null,"map")).getMessage());
        for (Object name : Arrays.asList(null,"", " ")) assertThrows(ValidationException.class, () -> requireNonBlankString(name,"name"));
        assertEquals("123", requireNonBlankString(123,"name"));
        for (Object coordinate : Arrays.asList(null,"bad", Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 7.999, 24.001))
            assertThrows(ValidationException.class, () -> requireCoordinate(coordinate,8,24,"coordinate"));
        for (double edge : new double[]{8,24,Math.nextUp(8),Math.nextDown(24)}) assertEquals(edge,requireCoordinate(edge,8,24,"coordinate"));
        for (double edge : new double[]{102,110}) assertEquals(edge,requireCoordinate(edge,102,110,"coordinate"));
        for (double outside : new double[]{Math.nextDown(102),Math.nextUp(110)}) assertThrows(ValidationException.class, () -> requireCoordinate(outside,102,110,"coordinate"));
        String fallback = "Restaurant/menu item validation thất bại";
        for (Object errors : List.of("bad", List.of(), List.of("bad", Map.of(), Map.of("message"," "))))
            assertEquals(fallback, validationMessage(Map.of("errors",errors)));
        assertEquals(fallback, validationMessage(Map.of()));
        assertEquals("one, two", validationMessage(Map.of("errors",List.of(Map.of("message","one"),"bad", Map.of("message","two")))));
        assertNull(getIntegerValue(null)); assertNull(getIntegerValue("1.0")); assertEquals(1,getIntegerValue("1"));
        assertNull(getLongValue(null)); assertNull(getLongValue("1.0")); assertEquals(1L,getLongValue("1"));
        assertNull(getBigDecimalValue(null)); assertNull(getBigDecimalValue("NaN"));
    }
    @Test void serviceabilityPrepAndEtaBoundaryMatrix() {
        for (Object enabled : Arrays.asList(null,false,true,"TRUE","bad")) {
            for (Object serviceable : Arrays.asList(null,false,true,"TRUE","bad")) {
                Map<String,Object> facts = new HashMap<>(); facts.put("serviceabilityEnabled",enabled); facts.put("serviceable",serviceable);
                serviceability(facts,false);
                if ((Boolean.TRUE.equals(enabled)||"TRUE".equals(enabled)) && (Boolean.TRUE.equals(serviceable)||"TRUE".equals(serviceable))) serviceability(facts,true);
                else assertThrows(ValidationException.class, () -> serviceability(facts,true));
            }
        }
        IllegalArgumentException decodeFailure = new IllegalArgumentException("serviceable decoding");
        Map<String,Object> malformed = Map.of("serviceabilityEnabled", false, "serviceable", new Object() {
            @Override public String toString() { throw decodeFailure; }
        });
        assertSame(decodeFailure, assertThrows(IllegalArgumentException.class, () -> serviceability(malformed,true)));
        serviceability(malformed,false);
        for (Object prep : Arrays.asList(null,"bad","1.5")) assertEquals(30,prepMinutes(prep,true));
        for (int prep : new int[]{Integer.MIN_VALUE,0,241,Integer.MAX_VALUE}) {
            assertEquals(prep,prepMinutes(prep,false)); assertThrows(ValidationException.class, () -> prepMinutes(prep,true));
        }
        for (int prep : new int[]{1,30,240}) assertEquals(prep,prepMinutes(prep,true));
        assertEquals("empty ETA response", assertThrows(IllegalStateException.class, () -> eta(() -> null)).getMessage());
        for (EtaWindow invalid : List.of(new EtaWindow(0,1,"a"),new EtaWindow(2,1,"a"),new EtaWindow(1,1,null),new EtaWindow(1,1,""),new EtaWindow(1,1," ")))
            assertEquals("invalid ETA response",assertThrows(IllegalStateException.class, () -> eta(() -> invalid)).getMessage());
        EtaWindow valid = new EtaWindow(1,Integer.MAX_VALUE," source "); assertSame(valid,eta(() -> valid));
        IllegalArgumentException remote = new IllegalArgumentException("remote"); assertSame(remote,assertThrows(IllegalArgumentException.class, () -> eta(() -> { throw remote; })));
    }
    @Test void catalogPortAndCanonicalFailurePrecedence() {
        Map<String,Object> restaurant = new HashMap<>();
        Map<String,Object> data = new HashMap<>();
        assertEquals("Restaurant service không trả restaurantInfo canonical",
                assertThrows(ValidationException.class, () -> catalog(() -> data,true,true)).getMessage());
        data.put("restaurantInfo",restaurant);
        assertEquals("Restaurant service thiếu tên nhà hàng canonical",
                assertThrows(ValidationException.class, () -> catalog(() -> data,true,true)).getMessage());
        restaurant.put("restaurantName","name");
        assertEquals("Restaurant service thiếu tọa độ pickup latitude canonical",
                assertThrows(ValidationException.class, () -> catalog(() -> data,true,true)).getMessage());
        restaurant.put("latitude",8);
        assertEquals("Restaurant service thiếu tọa độ pickup longitude canonical",
                assertThrows(ValidationException.class, () -> catalog(() -> data,true,true)).getMessage());
        restaurant.put("longitude",102);
        assertEquals("Địa chỉ giao hàng hiện nằm ngoài vùng phục vụ",
                assertThrows(ValidationException.class, () -> catalog(() -> data,true,true)).getMessage());
        restaurant.put("serviceabilityEnabled",true); restaurant.put("serviceable",true);
        restaurant.put("defaultPrepTimeMinutes",0);
        assertEquals("Restaurant service thiếu prep time canonical",
                assertThrows(ValidationException.class, () -> catalog(() -> data,true,true)).getMessage());
        restaurant.put("defaultPrepTimeMinutes",240);
        Catalog result = catalog(() -> data,true,true);
        assertSame(data,result.data()); assertSame(restaurant,result.restaurant());
        assertEquals("name",result.name()); assertEquals(8,result.pickupLat()); assertEquals(102,result.pickupLng()); assertEquals(240,result.prepMinutes());
        requireCatalogAccepted(1,data);
        for (int status : new int[]{0,-1,2,Integer.MAX_VALUE})
            assertEquals("Dữ liệu checkout không hợp lệ: Restaurant/menu item validation thất bại",
                    assertThrows(ValidationException.class, () -> requireCatalogAccepted(status,data)).getMessage());
        IllegalStateException failure = new IllegalStateException("remote");
        assertSame(failure,assertThrows(IllegalStateException.class, () -> catalog(() -> { throw failure; },true,true)));
    }
}
