package com.delivery.order_service.controller;

import com.delivery.order_service.common.constants.ApiPathConstants;
import com.delivery.order_service.common.constants.RoleConstants;
import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import com.delivery.order_service.dto.request.CancelOrderRequest;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.dto.response.CheckoutPreviewResponse;
import com.delivery.order_service.dto.response.OrderResponse;
import com.delivery.order_service.payload.BaseResponse;
import com.delivery.order_service.payload.PageResponse;
import com.delivery.order_service.service.CheckoutPreviewService;
import com.delivery.order_service.service.CheckoutQuoteService;
import com.delivery.order_service.service.OrderService;
import com.delivery.order_service.exception.ValidationException;
import com.delivery.auth.resourceserver.security.AuthenticatedActor;
import com.delivery.identity.contracts.SimulationContext;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@RestController
@RequestMapping(ApiPathConstants.ORDERS)
public class OrderController {

    private final OrderService orderService;
    private final CheckoutPreviewService checkoutPreviewService;
    private final CheckoutQuoteService checkoutQuoteService;

    @Value("${app.order.quote.enforcement-enabled:false}")
    private boolean quoteEnforcementEnabled;

    @Autowired
    public OrderController(OrderService orderService, CheckoutPreviewService checkoutPreviewService,
                           CheckoutQuoteService checkoutQuoteService) {
        this.orderService = orderService;
        this.checkoutPreviewService = checkoutPreviewService;
        this.checkoutQuoteService = checkoutQuoteService;
    }

    /** Source-compatible constructor for focused authorization tests/callers. */
    public OrderController(OrderService orderService, CheckoutPreviewService checkoutPreviewService) {
        this(orderService, checkoutPreviewService, null);
    }

    @PostMapping("/checkout-preview")
    public ResponseEntity<BaseResponse<CheckoutPreviewResponse>> checkoutPreview(
            @Valid @RequestBody CheckoutPreviewRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireCustomerRole(actor);
        // The two-argument constructor remains available to focused legacy
        // callers/tests during the rollout. A fully wired application always
        // uses CheckoutQuoteService and therefore returns a durable quote.
        CheckoutPreviewResponse preview = checkoutQuoteService != null
                ? checkoutQuoteService.issue(request, actor.getPrincipalId(), actor.getUserId())
                : checkoutPreviewService.calculatePreview(request, actor.getUserId());
        return ResponseEntity.ok(new BaseResponse<>(1, preview, "Checkout preview thành công"));
    }

    @PostMapping
    public ResponseEntity<BaseResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireCustomerRole(actor);
        boolean hasKey = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank();
        boolean hasQuote = request.getQuoteId() != null;
        if (quoteEnforcementEnabled && (!hasKey || !hasQuote)) {
            throw new ValidationException(!hasKey
                    ? "Idempotency-Key là bắt buộc khi đặt đơn"
                    : "quoteId là bắt buộc khi đặt đơn");
        }
        if (hasKey != hasQuote) {
            throw new ValidationException("Idempotency-Key và quoteId phải được gửi cùng nhau");
        }
        OrderResponse response;
        if (!hasKey) {
            response = orderService.createOrder(request, null, actor.getPrincipalId(), actor.getLegacyUserId(),
                    getPrimaryRole(actor), actor.getSimulationContext());
        } else {
            UUID idempotencyKey;
            try {
                idempotencyKey = UUID.fromString(idempotencyKeyHeader.trim());
            } catch (IllegalArgumentException invalid) {
                throw new ValidationException("Idempotency-Key phải là UUID hợp lệ");
            }
            response = orderService.createOrder(request, idempotencyKey, actor.getPrincipalId(),
                    actor.getLegacyUserId(), getPrimaryRole(actor), actor.getSimulationContext());
        }
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Tạo đơn hàng thành công"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BaseResponse<OrderResponse>> getOrderById(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        OrderResponse response = orderService.getOrderById(
                id, actor.getPrincipalId(), actor.getLegacyUserId(), getPrimaryRole(actor));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Lấy thông tin đơn hàng thành công"));
    }

    @GetMapping("/my-orders")
    public ResponseEntity<BaseResponse<PageResponse<OrderResponse>>> getMyOrders(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        requireCustomerRole(actor);
        Pageable pageable = boundedPageable(page, size);
        Page<OrderResponse> response = orderService.getOrdersByPrincipal(actor.getPrincipalId(), actor.getLegacyUserId(), getPrimaryRole(actor), pageable);
        return ResponseEntity.ok(new BaseResponse<>(1, PageResponse.from(response), "Lấy danh sách đơn hàng của tôi thành công"));
    }

    @GetMapping("/my-restaurant-orders")
    public ResponseEntity<BaseResponse<PageResponse<OrderResponse>>> getMyRestaurantOrders(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long restaurantId) {
        requireRestaurantOwnerOrAdminRole(actor);
        Pageable pageable = boundedPageable(page, size);
        Page<OrderResponse> response;
        if (actor.isAdmin()) {
            response = restaurantId != null
                    ? orderService.getOrdersByRestaurant(restaurantId, pageable)
                    : orderService.getAllOrders(actor.getUserId(), getPrimaryRole(actor), pageable);
        } else {
            response = orderService.getOrdersByRestaurantOwner(
                    actor.getPrincipalId(), actor.getLegacyUserId(), getPrimaryRole(actor), pageable);
        }
        return ResponseEntity.ok(new BaseResponse<>(1, PageResponse.from(response), "Lấy danh sách đơn hàng của nhà hàng thành công"));
    }

    public ResponseEntity<BaseResponse<PageResponse<OrderResponse>>> getMyRestaurantOrders(
            AuthenticatedActor actor,
            int page,
            int size) {
        return getMyRestaurantOrders(actor, page, size, null);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<BaseResponse<PageResponse<OrderResponse>>> getOrdersByStatus(
            @PathVariable String status,
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        requireAdminRole(actor);
        Pageable pageable = boundedPageable(page, size);
        Page<OrderResponse> response = orderService.getOrdersByStatus(status, actor.getUserId(), getPrimaryRole(actor), pageable);
        return ResponseEntity.ok(new BaseResponse<>(1, PageResponse.from(response), "Lấy danh sách đơn hàng theo trạng thái thành công"));
    }

    @GetMapping("/all")
    public ResponseEntity<BaseResponse<PageResponse<OrderResponse>>> getAllOrders(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        requireAdminRole(actor);
        Pageable pageable = boundedPageable(page, size);
        Page<OrderResponse> response = orderService.getAllOrders(actor.getUserId(), getPrimaryRole(actor), pageable);
        return ResponseEntity.ok(new BaseResponse<>(1, PageResponse.from(response), "Lấy tất cả đơn hàng thành công"));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<BaseResponse<OrderResponse>> cancelOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedActor actor,
            @Valid @RequestBody(required = false) CancelOrderRequest cancelRequest) {
        requireActor(actor);
        String reason = cancelRequest != null ? cancelRequest.getReason() : null;
        OrderResponse response = orderService.cancelOrder(id, actor.getPrincipalId(), actor.getLegacyUserId(), getPrimaryRole(actor), reason);
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Hủy đơn hàng thành công"));
    }

    private Pageable boundedPageable(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ValidationException("Pagination requires page >= 0 and size between 1 and 100");
        }
        return PageRequest.of(page, size);
    }

    private void requireActor(AuthenticatedActor actor) {
        if (actor == null || actor.getPrincipalId() == null || actor.getLegacyUserId() == null) {
            throw new com.delivery.order_service.exception.AccessDeniedException("Yêu cầu đăng nhập");
        }
    }

    private void requireCustomerRole(AuthenticatedActor actor) {
        requireActor(actor);
        if (!actor.isUser()) {
            throw new com.delivery.order_service.exception.AccessDeniedException(
                    "Chỉ khách hàng được tạo hoặc preview đơn hàng");
        }
    }

    private void requireRestaurantOwnerOrAdminRole(AuthenticatedActor actor) {
        requireActor(actor);
        if (!actor.isShopOwner() && !actor.isAdmin()) {
            throw new com.delivery.order_service.exception.AccessDeniedException(
                    "Chỉ chủ nhà hàng hoặc admin được xem đơn hàng của nhà hàng");
        }
    }

    private void requireAdminRole(AuthenticatedActor actor) {
        requireActor(actor);
        if (!actor.isAdmin()) {
            throw new com.delivery.order_service.exception.AccessDeniedException("Yêu cầu quyền ADMIN");
        }
    }

    private String getPrimaryRole(AuthenticatedActor actor) {
        if (actor == null) return null;
        if (actor.isAdmin()) return RoleConstants.ADMIN;
        if (actor.isShopOwner()) return RoleConstants.RESTAURANT_OWNER;
        if (actor.isShipper()) return RoleConstants.SHIPPER;
        return RoleConstants.USER;
    }
}
