package com.delivery.order.domain;

import java.math.BigDecimal;
import java.util.*;

/** Preview-specific admission and canonical catalog policies, preserving legacy coercion and precedence. */
public final class CheckoutPreviewPolicy {
    private CheckoutPreviewPolicy() { }
    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }
    public record Item(Long menuItemId, Integer quantity, Long flashSaleItemId) { }
    public record Input(List<Item> items, String couponCode, Long voucherId,
                        List<Long> selectedVoucherIds, String selectionMode, UUID livestreamId) { }
    public record Capabilities(boolean voucher, boolean flash, boolean reservations,
                               boolean livestream, CheckoutAdmissionPolicy.StackingCapability stacking) { }
    public record Admission(boolean hasFlashSale, boolean hasVoucherSelection, List<Long> selectedVoucherIds) { }
    public static Admission admit(Input request, Capabilities capabilities) {
        if (request == null) {
            throw new ValidationException("Dữ liệu checkout không được để trống");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new ValidationException("Checkout phải có ít nhất một sản phẩm");
        }
        if (request.items().size() > 50) {
            throw new ValidationException("Checkout không được vượt quá 50 sản phẩm");
        }
        validateDuplicateItems(request);

        // Coupon codes are collected through Promotion first; the checkout
        // quote only accepts stable wallet IDs and never trusts a client code.
        if (request.couponCode() != null && !request.couponCode().isBlank()) {
            throw new ValidationException("Hãy lưu mã voucher vào ví trước khi báo giá checkout");
        }
        boolean hasFlashSale = request.items().stream().anyMatch(item -> item.flashSaleItemId() != null);
        List<Long> selectedVoucherIds = selectedVoucherIds(request);
        if (request.voucherId() != null && request.selectedVoucherIds() != null) {
            throw new ValidationException("Không được gửi đồng thời voucherId và selectedVoucherIds");
        }
        if (request.selectionMode() != null && !request.selectionMode().isBlank()
                && !"AUTO".equalsIgnoreCase(request.selectionMode())
                && !"MANUAL".equalsIgnoreCase(request.selectionMode())) {
            throw new ValidationException("Selection mode chỉ hỗ trợ AUTO hoặc MANUAL");
        }
        if (request.selectedVoucherIds() != null
                && request.selectedVoucherIds().size() == 1
                && (request.selectionMode() == null || request.selectionMode().isBlank())) {
            throw new ValidationException(
                    "Một voucher trong selectedVoucherIds phải đi kèm selectionMode rõ ràng");
        }
        if ("MANUAL".equalsIgnoreCase(request.selectionMode()) && selectedVoucherIds.isEmpty()) {
            throw new ValidationException("Manual voucher mode requires selected voucher IDs");
        }
        boolean hasVoucherSelection = !selectedVoucherIds.isEmpty()
                || request.selectionMode() != null || request.voucherId() != null;
        boolean stackedSelection = (request.selectionMode() != null && !request.selectionMode().isBlank())
                || (request.selectedVoucherIds() != null && !request.selectedVoucherIds().isEmpty());
        if (hasVoucherSelection && stackedSelection && !capabilities.stacking().isEnabled())
            throw new ValidationException("Voucher stacking checkout is not enabled for this account");
        if (hasVoucherSelection && !stackedSelection && !capabilities.voucher())
            throw new ValidationException("Voucher checkout is disabled");
        if (hasFlashSale && !capabilities.flash())
            throw new ValidationException("Flash-sale checkout is disabled");
        if (request.livestreamId() != null && hasFlashSale)
            throw new ValidationException("Livestream và Flash Sale không được áp dụng cùng một đơn");
        if (hasVoucherSelection && hasFlashSale)
            throw new ValidationException("Voucher và Flash Sale không được áp dụng cùng một đơn");
        if ((hasVoucherSelection || hasFlashSale) && !capabilities.reservations())
            throw new ValidationException("Checkout reservation capability is unavailable");
        if (request.livestreamId() != null && !capabilities.livestream())
            throw new ValidationException("Livestream checkout capability is unavailable");
        return new Admission(hasFlashSale, hasVoucherSelection, selectedVoucherIds);
    }

    public static List<Long> selectedVoucherIds(Input request) {
        List<Long> ids = request.selectedVoucherIds() == null
                ? new ArrayList<>() : new ArrayList<>(request.selectedVoucherIds());
        if (request.voucherId() != null && ids.isEmpty()) ids.add(request.voucherId());
        if (ids.size() > 3 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || ids.stream().distinct().count() != ids.size()) {
            throw new ValidationException("Tối đa 3 voucher khác nhau, mỗi lớp một voucher");
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    public static Map<Long, ValidatedPreviewItem> parseValidatedItems(Map<String, Object> validationData) {
        Object rawItems = validationData.get("itemValidations");
        if (!(rawItems instanceof List<?> itemValidations)) {
            throw new ValidationException("Restaurant service không trả dữ liệu canonical của món ăn");
        }

        Map<Long, ValidatedPreviewItem> items = new HashMap<>();
        for (Object rawItem : itemValidations) {
            if (!(rawItem instanceof Map<?, ?> item)) {
                throw new ValidationException("Restaurant service trả item validation không hợp lệ");
            }
            Long menuItemId = getLongValue(item.get("menuItemId"));
            String name = getStringValue(item.get("menuItemName"));
            BigDecimal price = getBigDecimalValue(item.get("actualPrice"));
            boolean available = Boolean.TRUE.equals(getBooleanValue(item.get("isAvailable")))
                    && !Boolean.FALSE.equals(getBooleanValue(item.get("hasEnoughStock")))
                    && name != null
                    && !name.isBlank()
                    && price != null
                    && price.signum() > 0;
            if (menuItemId != null) {
                items.put(menuItemId, new ValidatedPreviewItem(menuItemId, name, price, available));
            }
        }
        return items;
    }

    public static void validateDuplicateItems(Input request) {
        Set<Long> menuItemIds = new HashSet<>();
        Set<Long> flashSaleItemIds = new HashSet<>();
        for (Item item : request.items()) {
            if (item == null || item.menuItemId() == null) {
                throw new ValidationException("Checkout chứa sản phẩm không hợp lệ");
            }
            if (item.quantity() == null || item.quantity() < 1 || item.quantity() > 99
                    || (item.flashSaleItemId() != null && item.flashSaleItemId() <= 0)) {
                throw new ValidationException("Checkout chứa số lượng hoặc flash-sale item không hợp lệ");
            }
            if (!menuItemIds.add(item.menuItemId())) {
                throw new ValidationException("Menu Item ID bị trùng trong checkout preview");
            }
            if (item.flashSaleItemId() != null && !flashSaleItemIds.add(item.flashSaleItemId()))
                throw new ValidationException("Flash Sale Item ID bị trùng trong checkout preview");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> requireMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ValidationException(message);
        }
        return (Map<String, Object>) map;
    }

    public static String requireNonBlankString(Object value, String message) {
        String result = getStringValue(value);
        if (result == null || result.isBlank()) {
            throw new ValidationException(message);
        }
        return result;
    }

    public static Double requireCoordinate(Object value, double min, double max, String message) {
        Double result = getDoubleValue(value);
        if (result == null || !Double.isFinite(result) || result < min || result > max) {
            throw new ValidationException(message);
        }
        return result;
    }

    public static String validationMessage(Map<String, Object> data) {
        Object errors = data.get("errors");
        if (!(errors instanceof List<?> validationErrors) || validationErrors.isEmpty()) {
            return "Restaurant/menu item validation thất bại";
        }
        List<String> messages = new ArrayList<>();
        for (Object rawError : validationErrors) {
            if (rawError instanceof Map<?, ?> error) {
                String message = getStringValue(error.get("message"));
                if (message != null && !message.isBlank()) {
                    messages.add(message);
                }
            }
        }
        return messages.isEmpty()
                ? "Restaurant/menu item validation thất bại"
                : String.join(", ", messages);
    }

    public static String getStringValue(Object val) {
        return val != null ? val.toString() : null;
    }

    public static Double getDoubleValue(Object val) {
        if (val == null) return null;
        try { return Double.valueOf(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    public static Integer getIntegerValue(Object val) {
        if (val == null) return null;
        try { return Integer.valueOf(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    public static Long getLongValue(Object val) {
        if (val == null) return null;
        try { return Long.valueOf(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    public static BigDecimal getBigDecimalValue(Object val) {
        if (val == null) return null;
        try { return new BigDecimal(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    public record EtaWindow(int minMinutes, int maxMinutes, String source) {
    }

    public static Boolean getBooleanValue(Object val) {
        if (val == null) return null;
        if (val instanceof Boolean bool) return bool;
        return Boolean.valueOf(val.toString());
    }

    public record ValidatedPreviewItem(
            Long menuItemId,
            String name,
            BigDecimal price,
            boolean available) {
    }

    /** Adapter supplies the decoded canonical response only when restaurant facts are needed. */
    @FunctionalInterface
    public interface CatalogFactsPort { Map<String, Object> fetch(); }
    public record Catalog(Map<String, Object> data, Map<String, Object> restaurant,
                          String name, double pickupLat, double pickupLng, int prepMinutes) { }
    public static Catalog catalog(CatalogFactsPort port, boolean enforceServiceability, boolean etaEnabled) {
        Map<String, Object> data = port.fetch();
        Map<String, Object> restaurant = requireMap(data.get("restaurantInfo"),
                "Restaurant service không trả restaurantInfo canonical");
        String name = requireNonBlankString(restaurant.get("restaurantName"),
                "Restaurant service thiếu tên nhà hàng canonical");
        double lat = requireCoordinate(restaurant.get("latitude"), 8.0, 24.0,
                "Restaurant service thiếu tọa độ pickup latitude canonical");
        double lng = requireCoordinate(restaurant.get("longitude"), 102.0, 110.0,
                "Restaurant service thiếu tọa độ pickup longitude canonical");
        serviceability(restaurant, enforceServiceability);
        int prep = prepMinutes(restaurant.get("defaultPrepTimeMinutes"), etaEnabled);
        return new Catalog(data, restaurant, name, lat, lng, prep);
    }
    public static void requireCatalogAccepted(int status, Map<String, Object> data) {
        if (status != 1) throw new ValidationException("Dữ liệu checkout không hợp lệ: " + validationMessage(data));
    }

    public static int prepMinutes(Object raw, boolean etaEnabled) {
        Integer prep = getIntegerValue(raw);
        if (prep == null) prep = 30;
        if (etaEnabled && (prep < 1 || prep > 240))
            throw new ValidationException("Restaurant service thiếu prep time canonical");
        return prep;
    }
    public static void serviceability(Map<String, Object> restaurant, boolean enforced) {
        if (enforced) {
            Boolean enabled = getBooleanValue(restaurant.get("serviceabilityEnabled"));
            Boolean serviceable = getBooleanValue(restaurant.get("serviceable"));
            if (!Boolean.TRUE.equals(enabled) || !Boolean.TRUE.equals(serviceable))
                throw new ValidationException("Địa chỉ giao hàng hiện nằm ngoài vùng phục vụ");
        }
    }
    @FunctionalInterface
    public interface EtaClient { EtaWindow fetch(); }
    public static EtaWindow eta(EtaClient client) {
        EtaWindow response = client.fetch();
        if (response == null) throw new IllegalStateException("empty ETA response");
        if (response.minMinutes() < 1 || response.maxMinutes() < response.minMinutes()
                || response.source() == null || response.source().isBlank())
            throw new IllegalStateException("invalid ETA response");
        return response;
    }
    public static boolean unavailable(ValidatedPreviewItem item) { return item == null || !item.available(); }
    public static void requireAvailable(List<Long> unavailableIds) {
        if (!unavailableIds.isEmpty())
            throw new ValidationException("Checkout chứa món không khả dụng: " + unavailableIds);
    }
}
