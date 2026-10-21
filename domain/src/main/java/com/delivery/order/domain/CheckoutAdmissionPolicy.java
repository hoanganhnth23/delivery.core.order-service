package com.delivery.order.domain;

import java.util.*;

/** Create boundary admission. Facts contain selections only, never client prices. */
public final class CheckoutAdmissionPolicy {
    private CheckoutAdmissionPolicy() { }

    @FunctionalInterface
    public interface StackingCapability { boolean isEnabled(); }
    public record Capabilities(boolean voucherCheckoutEnabled, boolean flashSaleCheckoutEnabled,
                               boolean livestreamCheckoutEnabled, StackingCapability stacking) { }
    public record Item(Long menuItemId, Integer quantity, String notes, Long flashSaleItemId) { }
    public record Input(Long restaurantId, String deliveryAddress, String customerName,
                        String customerPhone, String paymentMethod, String notes,
                        List<Long> voucherIds, String selectionMode, List<Item> items,
                        boolean livestream, Double deliveryLat, Double deliveryLng) { }

    public static boolean customerAllowed(String role) { return "USER".equals(role); }

    /** Returns errors in the original HTTP validation order. Null input is handled by the host. */
    public static List<String> errors(Input input, Long userId, Capabilities capabilities) {
        List<String> errors = new ArrayList<>();
        validateRequiredFields(input, errors);
        validateBusinessRules(input, capabilities, errors);
        validateDeliveryCoordinates(input, errors);
        validateUserContext(input, userId, errors);
        return errors;
    }

    /**
     * Validate required fields — CHỈ những trường mà client phải gửi.
     * Thông tin nhà hàng (name, address, phone, lat, lng) sẽ lấy từ server.
     */
    private static void validateRequiredFields(Input request, List<String> errors) {
        // Restaurant ID — trường DUY NHẤT phía nhà hàng mà client cung cấp
        if (request.restaurantId() == null) {
            errors.add("Restaurant ID không được để trống");
        } else if (request.restaurantId() <= 0) {
            errors.add("Restaurant ID phải là số dương");
        }

        // Delivery info
        if (request.deliveryAddress() == null || request.deliveryAddress().trim().isEmpty()) {
            errors.add("Địa chỉ giao hàng không được để trống");
        } else if (request.deliveryAddress().length() > 500) {
            errors.add("Địa chỉ giao hàng không được vượt quá 500 ký tự");
        }

        // Customer info
        if (request.customerName() == null || request.customerName().trim().isEmpty()) {
            errors.add("Tên khách hàng không được để trống");
        } else if (request.customerName().length() > 100) {
            errors.add("Tên khách hàng không được vượt quá 100 ký tự");
        }

        if (request.customerPhone() == null || request.customerPhone().trim().isEmpty()) {
            errors.add("Số điện thoại khách hàng không được để trống");
        }

        // Payment method
        if (request.paymentMethod() == null || request.paymentMethod().trim().isEmpty()) {
            errors.add("Phương thức thanh toán không được để trống");
        } else if (!"COD".equals(request.paymentMethod())) {
            errors.add("MVP hiện chỉ hỗ trợ thanh toán COD");
        }

        // Notes (optional)
        if (request.notes() != null && request.notes().length() > 1000) {
            errors.add("Ghi chú không được vượt quá 1000 ký tự");
        }
    }

    /**
     * Validate business rules
     */
    private static void validateBusinessRules(Input request, Capabilities capabilities, List<String> errors) {
        int voucherCount = request.voucherIds() == null ? 0 : request.voucherIds().size();
        if (request.voucherIds() != null && (request.voucherIds().stream()
                .anyMatch(id -> id == null || id <= 0)
                || request.voucherIds().stream().distinct().count() != voucherCount)) {
            errors.add("Voucher ID phải là số dương và không được trùng");
        }
        if (request.selectionMode() != null && !request.selectionMode().isBlank()
                && !"AUTO".equalsIgnoreCase(request.selectionMode())
                && !"MANUAL".equalsIgnoreCase(request.selectionMode())) {
            errors.add("Selection mode chỉ hỗ trợ AUTO hoặc MANUAL");
        }
        boolean stackedSelection = (request.selectionMode() != null && !request.selectionMode().isBlank())
                || voucherCount > 1;
        if (voucherCount > 3) {
            errors.add("Mỗi đơn chỉ được dùng tối đa ba voucher");
        } else if (stackedSelection && !capabilities.stacking().isEnabled()) {
            errors.add("Voucher stacking checkout chưa được mở cho tài khoản này");
        } else if (voucherCount == 1 && !stackedSelection && !capabilities.voucherCheckoutEnabled()) {
            errors.add("Voucher checkout chưa được mở trong MVP cho tới khi có discount và compensation proof");
        }

        // Minimum order validation
        if (request.items() == null || request.items().isEmpty()) {
            errors.add("Đơn hàng phải có ít nhất một sản phẩm");
            return;
        }

        // Maximum items per order
        if (request.items().size() > 50) {
            errors.add("Đơn hàng không được vượt quá 50 sản phẩm");
        }

        // Validate each item
        Set<Long> menuItemIds = new HashSet<>();
        for (int i = 0; i < request.items().size(); i++) {
            Item item = request.items().get(i);
            if (item == null) {
                errors.add("Sản phẩm " + (i + 1) + ": dữ liệu sản phẩm không hợp lệ");
                continue;
            }
            validateOrderItem(item, i + 1, errors);
            if (item.menuItemId() != null && !menuItemIds.add(item.menuItemId())) {
                errors.add("Sản phẩm " + (i + 1) + ": Menu Item ID bị trùng");
            }
            if (item.flashSaleItemId() != null && !capabilities.flashSaleCheckoutEnabled()) {
                errors.add("Sản phẩm " + (i + 1)
                        + ": Flash Sale checkout chưa được mở trong MVP cho tới khi reservation có idempotency/compensation proof");
            }
        }

        boolean hasFlashSale = request.items().stream().filter(Objects::nonNull)
                .anyMatch(item -> item.flashSaleItemId() != null);
        if (request.livestream() && !capabilities.livestreamCheckoutEnabled()) {
            errors.add("Livestream checkout chưa được mở");
        }
        if (request.livestream() && hasFlashSale) {
            errors.add("Livestream và Flash Sale không được áp dụng cùng một đơn");
        }
        if (voucherCount > 0 && hasFlashSale) {
            errors.add("Voucher và Flash Sale không được áp dụng cùng một đơn");
        }

        // Phone number format validation (more strict)
        if (request.customerPhone() != null && !isValidVietnamesePhoneNumber(request.customerPhone())) {
            errors.add("Số điện thoại khách hàng không đúng định dạng Việt Nam");
        }
    }

    /**
     * Validate individual order item
     */
    private static void validateOrderItem(Item item, int itemIndex, List<String> errors) {
        String prefix = "Sản phẩm " + itemIndex + ": ";

        // Menu Item ID validation
        if (item.menuItemId() == null) {
            errors.add(prefix + "Menu Item ID không được để trống");
        } else if (item.menuItemId() <= 0) {
            errors.add(prefix + "Menu Item ID phải là số dương");
        }

        // Quantity validation
        if (item.quantity() == null) {
            errors.add(prefix + "Số lượng không được để trống");
        } else if (item.quantity() <= 0) {
            errors.add(prefix + "Số lượng phải lớn hơn 0");
        } else if (item.quantity() > 99) {
            errors.add(prefix + "Số lượng không được vượt quá 99");
        }

        // Notes validation (optional but with size limit)
        if (item.notes() != null && item.notes().length() > 500) {
            errors.add(prefix + "Ghi chú sản phẩm không được vượt quá 500 ký tự");
        }
    }

    /**
     * Validate delivery coordinates (client-provided) cho Vietnam region.
     * Pickup coords (restaurant) sẽ lấy từ server, không validate ở đây.
     */
    private static void validateDeliveryCoordinates(Input request, List<String> errors) {
        double MIN_LAT = 8.0, MAX_LAT = 24.0;
        double MIN_LNG = 102.0, MAX_LNG = 110.0;

        if (request.deliveryLat() == null || request.deliveryLng() == null) {
            errors.add("Tọa độ giao hàng (latitude và longitude) là bắt buộc");
        } else {
            if (request.deliveryLat() < MIN_LAT || request.deliveryLat() > MAX_LAT) {
                errors.add("Tọa độ giao hàng (latitude) phải trong phạm vi Việt Nam (8.0 - 24.0)");
            }
            if (request.deliveryLng() < MIN_LNG || request.deliveryLng() > MAX_LNG) {
                errors.add("Tọa độ giao hàng (longitude) phải trong phạm vi Việt Nam (102.0 - 110.0)");
            }
        }
    }

    /**
     * Validate user context and permissions
     */
    private static void validateUserContext(Input request, Long userId, List<String> errors) {
        if (userId == null || userId <= 0) {
            errors.add("User ID không hợp lệ");
        }

        // Additional business rules can be added here
        // E.g., user credit limit, delivery zone restrictions, etc.
    }

    /**
     * Validate Vietnamese phone number format
     */
    private static boolean isValidVietnamesePhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }

        // Vietnamese phone number patterns
        String cleanPhone = phoneNumber.replaceAll("\\s+", "");
        return cleanPhone.matches("^(\\+84|84|0)(3|5|7|8|9)[0-9]{8}$");
    }

}
