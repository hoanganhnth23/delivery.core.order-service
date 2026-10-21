package com.delivery.order_service.service.impl;

import com.delivery.order.domain.CheckoutAdmissionPolicy;
import com.delivery.order.domain.CheckoutPricingPolicy;

import com.delivery.order_service.common.constants.RoleConstants;
import com.delivery.order.domain.OrderOwnershipPolicy;
import com.delivery.order.domain.OrderCancellationPolicy;
import com.delivery.order_service.dto.internal.ValidatedOrderData;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.dto.response.OrderResponse;
import com.delivery.order_service.dto.event.ShipperNotFoundEvent;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderItem;
import com.delivery.order_service.entity.OrderStatus;
import com.delivery.order_service.exception.AccessDeniedException;
import com.delivery.order_service.exception.OrderApiException;
import com.delivery.order_service.exception.ResourceNotFoundException;
import com.delivery.order_service.mapper.OrderMapper;
import com.delivery.order_service.repository.OrderItemRepository;
import com.delivery.order_service.repository.OrderRepository;
import com.delivery.order_service.service.OrderEventPublisher;
import com.delivery.order_service.service.OrderService;
import com.delivery.order_service.service.OrderValidationService;
import com.delivery.order_service.service.ShippingFeeCalculationService;
import com.delivery.order_service.service.CheckoutReservationClient;
import com.delivery.order_service.service.CheckoutQuoteService;
import com.delivery.order_service.service.CheckoutFingerprintService;
import com.delivery.order_service.service.OrderCreateIdempotencyService;
import com.delivery.order_service.service.LivestreamCheckoutPriceClient;
import com.delivery.order_service.dto.response.CheckoutPreviewResponse;
import com.delivery.order_service.config.OrderCreateAdmission;
import com.delivery.order_service.entity.OrderCreateIdempotencyReceipt;
import com.delivery.identity.contracts.SimulationContext;
import com.delivery.order_service.metrics.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper orderMapper;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderValidationService orderValidationService;
    private final ShippingFeeCalculationService shippingFeeCalculationService;
    private final BusinessMetrics businessMetrics;
    private final CheckoutReservationClient reservationClient;
    private final OrderCreateAdmission createAdmission;

    @Autowired(required = false)
    private com.delivery.order_service.service.InventoryReservationClient inventoryReservationClient;

    @Value("${app.order.inventory-reservation-enabled:false}")
    private boolean inventoryReservationEnabled;

    @Autowired(required = false)
    private CheckoutQuoteService checkoutQuoteService;

    @Autowired(required = false)
    private OrderCreateIdempotencyService idempotencyService;

    @Autowired(required = false)
    private CheckoutFingerprintService checkoutFingerprintService;

    @Autowired(required = false)
    private LivestreamCheckoutPriceClient livestreamPriceClient;

    /**
     * Explicit transaction boundary for create-order.  The old implementation
     * opened a write transaction before calling remote dependencies.  Keeping
     * this optional preserves source-compatible focused unit tests; production
     * Spring wiring always supplies the template from the service DB manager.
     */
    @Autowired(required = false)
    private TransactionTemplate transactionTemplate;

    @Value("${app.identity.principal-ownership.enforced:false}")
    private boolean principalOwnershipEnforced;

    @Autowired
    public OrderServiceImpl(OrderRepository orderRepository,
                           OrderItemRepository orderItemRepository,
                           OrderMapper orderMapper,
                           OrderEventPublisher orderEventPublisher,
                           OrderValidationService orderValidationService,
                           ShippingFeeCalculationService shippingFeeCalculationService,
                           BusinessMetrics businessMetrics,
                           CheckoutReservationClient reservationClient,
                           OrderCreateAdmission createAdmission) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderMapper = orderMapper;
        this.orderEventPublisher = orderEventPublisher;
        this.orderValidationService = orderValidationService;
        this.shippingFeeCalculationService = shippingFeeCalculationService;
        this.businessMetrics = businessMetrics;
        this.reservationClient = reservationClient;
        this.createAdmission = createAdmission;
    }

    /** Source-compatible constructor for focused legacy tests/callers. */
    public OrderServiceImpl(OrderRepository orderRepository,
                           OrderItemRepository orderItemRepository,
                           OrderMapper orderMapper,
                           OrderEventPublisher orderEventPublisher,
                           OrderValidationService orderValidationService,
                           ShippingFeeCalculationService shippingFeeCalculationService,
                           BusinessMetrics businessMetrics,
                           CheckoutReservationClient reservationClient) {
        this(orderRepository, orderItemRepository, orderMapper, orderEventPublisher,
                orderValidationService, shippingFeeCalculationService, businessMetrics,
                reservationClient, null);
    }

    @Override
    public OrderResponse createOrder(CreateOrderRequest request, Long userId, String role) {
        return createOrder(request, userId, userId, role);
    }

    @Override
    public OrderResponse createOrder(CreateOrderRequest request, Long principalId, Long userId, String role) {
        return createOrder(request, null, principalId, userId, role);
    }

    @Override
    public OrderResponse createOrder(CreateOrderRequest request, UUID idempotencyKey,
                                     Long principalId, Long userId, String role) {
        return createOrder(request, idempotencyKey, principalId, userId, role, SimulationContext.real());
    }

    @Override
    public OrderResponse createOrder(CreateOrderRequest request, UUID idempotencyKey,
                                     Long principalId, Long userId, String role,
                                     SimulationContext simulationContext) {
        // Reject unauthorized traffic before consuming an admission permit.
        if (!CheckoutAdmissionPolicy.customerAllowed(role)) {
            throw new AccessDeniedException("Chỉ khách hàng được tạo đơn hàng");
        }
        if (createAdmission == null) {
            return createOrderInternal(request, idempotencyKey, principalId, userId, role,
                    SimulationContext.orReal(simulationContext));
        }
        return createAdmission.execute(() -> createOrderInternal(
                request, idempotencyKey, principalId, userId, role,
                SimulationContext.orReal(simulationContext)));
    }

    private OrderResponse createOrderInternal(CreateOrderRequest request, UUID idempotencyKey,
                                              Long principalId, Long userId, String role,
                                              SimulationContext simulationContext) {
        return com.delivery.order.application.CreateOrderWorkflow.execute(
                new com.delivery.order.application.api.CreateOrderPorts<OrderResponse, PreparedOrderData, OrderCreateIdempotencyReceipt>() {
            public void admit() {
                if (!CheckoutAdmissionPolicy.customerAllowed(role))
                    throw new AccessDeniedException("Chỉ khách hàng được tạo đơn hàng");
                if (request == null) throw new IllegalArgumentException("Dữ liệu đơn hàng không được để trống");
            }
            public boolean hasIdempotencyKey() { return idempotencyKey != null; }
            public String fingerprint() {
                if (idempotencyService == null || checkoutFingerprintService == null)
                    throw new IllegalStateException("Create-order idempotency is unavailable");
                return checkoutFingerprintService.createCommand(request);
            }
            public UUID newToken() { return UUID.randomUUID(); }
            public OrderCreateIdempotencyReceipt acquire(String fingerprint, UUID token) {
                return idempotencyService.acquire(principalId, idempotencyKey, fingerprint, token);
            }
            public Long completedOrder(OrderCreateIdempotencyReceipt receipt) { return receipt.getOrderId(); }
            public OrderResponse replay(Long id) { return loadOrderResponse(id, principalId, userId, role); }
            public PreparedOrderData prepare() { return prepareCreateOrder(request, principalId, userId); }
            public OrderResponse transaction(Supplier<OrderResponse> operation) { return executeWriteTransaction(operation); }
            public OrderResponse persist(PreparedOrderData prepared, String fingerprint, UUID token) {
                return persistCreateOrder(request, idempotencyKey, fingerprint, token, principalId, userId,
                        role, prepared, simulationContext);
            }
            public void release(OrderCreateIdempotencyReceipt receipt, UUID token) {
                idempotencyService.release(receipt.getId(), token);
            }
        });
    }

    private PreparedOrderData prepareCreateOrder(CreateOrderRequest request, Long principalId, Long userId) {
        return com.delivery.order.application.PrepareOrderWorkflow.execute(
                new com.delivery.order.application.api.PrepareOrderPorts<CheckoutPreviewResponse, ValidatedOrderData, PreparedOrderData>() {
            public void admitSelections() {
                boolean hasFlash = request.getItems() != null && request.getItems().stream()
                        .filter(Objects::nonNull).anyMatch(item -> item.getFlashSaleItemId() != null);
                if (request.getLivestreamId() != null && hasFlash)
                    throw new com.delivery.order_service.exception.ValidationException(
                            "Livestream và Flash Sale không được áp dụng cùng một đơn");
            }
            public boolean hasQuote() { return request.getQuoteId() != null; }
            public CheckoutPreviewResponse validateQuote() {
                if (checkoutQuoteService == null) throw new IllegalStateException("Checkout quote service is unavailable");
                return checkoutQuoteService.validateAndReprice(request, principalId, userId);
            }
            public ValidatedOrderData canonicalFacts() {
                return orderValidationService.validateCreateOrderRequest(request, principalId, userId);
            }
            public void requireCanonical(ValidatedOrderData validated) {
                if (validated == null || validated.creatorId() == null)
                    throw new ResourceNotFoundException("Không thể lấy thông tin nhà hàng. Restaurant ID: " + request.getRestaurantId());
            }
            public PreparedOrderData prepared(ValidatedOrderData validated, CheckoutPreviewResponse quote) {
                return new PreparedOrderData(validated, resolveLivestreamPrices(request, quote));
            }
        });
    }

    private Map<Long, BigDecimal> resolveLivestreamPrices(CreateOrderRequest request,
                                                          CheckoutPreviewResponse currentQuote) {
        if (request.getLivestreamId() == null) return Map.of();
        if (currentQuote != null) {
            if (!request.getRestaurantId().equals(currentQuote.getRestaurantId())
                    || currentQuote.getItems() == null) {
                throw new IllegalStateException("Checkout quote is missing livestream prices");
            }
            Map<Long, BigDecimal> prices = new LinkedHashMap<>();
            for (CheckoutPreviewResponse.PreviewItemDetail item : currentQuote.getItems()) {
                if (item == null || item.getMenuItemId() == null || item.getUnitPrice() == null
                        || item.getUnitPrice().signum() <= 0
                        || prices.putIfAbsent(item.getMenuItemId(), item.getUnitPrice()) != null) {
                    throw new IllegalStateException("Checkout quote is missing livestream prices");
                }
            }
            var requestedIds = request.getItems().stream()
                    .map(CreateOrderRequest.OrderItemRequest::getMenuItemId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!prices.keySet().equals(requestedIds)) {
                throw new IllegalStateException("Checkout quote is missing livestream prices");
            }
            return Map.copyOf(prices);
        }
        if (livestreamPriceClient == null) {
            throw new com.delivery.order_service.exception.ValidationException(
                    "Livestream checkout capability is unavailable");
        }
        return livestreamPriceClient.resolve(request.getLivestreamId(), request.getRestaurantId(),
                request.getItems().stream().map(CreateOrderRequest.OrderItemRequest::getMenuItemId).toList());
    }

    private record PreparedOrderData(ValidatedOrderData validated, Map<Long, BigDecimal> livestreamPrices) {
    }

    private OrderResponse persistCreateOrder(CreateOrderRequest request, UUID idempotencyKey,
                                              String requestFingerprint, UUID processingToken,
                                              Long principalId, Long userId,
                                              String role, PreparedOrderData prepared,
                                              SimulationContext simulationContext) {
        return com.delivery.order.application.CreateWriteWorkflow.execute(
                new com.delivery.order.application.api.CreateWritePorts<OrderCreateIdempotencyReceipt, CreateShell, OrderResponse>() {
            public boolean hasKey() { return idempotencyKey != null; }
            public OrderCreateIdempotencyReceipt claim() {
                if (idempotencyService == null || checkoutFingerprintService == null)
                    throw new IllegalStateException("Create-order idempotency is unavailable");
                return idempotencyService.claim(principalId, idempotencyKey, requestFingerprint, processingToken);
            }
            public Long completedOrder(OrderCreateIdempotencyReceipt receipt) { return receipt.getOrderId(); }
            public OrderResponse replay(Long id) { return loadOrderResponse(id, principalId, userId, role); }
            public CreateShell flushShell() { return createShell(request, principalId, userId, prepared.validated(), simulationContext); }
            public OrderResponse reserveAndSnapshot(CreateShell shell, OrderCreateIdempotencyReceipt receipt) {
                return reserveAndSnapshotOrder(request, principalId, userId, prepared, shell, receipt);
            }
        });
    }

    private record CreateShell(Order order, Map<Long, ValidatedOrderData.ValidatedItemData> canonicalItems) {}

    private CreateShell createShell(CreateOrderRequest request, Long principalId, Long userId,
                              ValidatedOrderData validated, SimulationContext simulationContext) {
        log.info("✅ Restaurant validated from server. creatorId={}, name={}",
                validated.creatorId(), validated.restaurantName());

        // ✅ Mapper chỉ copy: restaurantId, deliveryAddress, deliveryLat/Lng,
        //   customerName, customerPhone, paymentMethod, notes.
        //   Các trường nhà hàng sẽ được set rõ ràng từ ValidatedOrderData bên dưới.
        Order order = orderMapper.createOrderRequestToOrder(request);
        order.setSimulationContext(simulationContext);
        order.setUserId(userId);
        order.setUserPrincipalId(principalId);

        // ✅ Set canonical restaurant data từ server — không dùng bất cứ dữ liệu nào từ client
        order.setCreatorId(validated.creatorId());
        order.setCreatorPrincipalId(validated.creatorPrincipalId());
        order.setRestaurantName(validated.restaurantName());
        order.setRestaurantAddress(validated.restaurantAddress());
        order.setRestaurantPhone(validated.restaurantPhone());
        order.setPickupLat(validated.pickupLat());
        order.setPickupLng(validated.pickupLng());

        Map<Long, ValidatedOrderData.ValidatedItemData> canonicalItems = validated.items().stream()
                .collect(Collectors.toMap(
                        ValidatedOrderData.ValidatedItemData::menuItemId,
                        Function.identity()));
        if (canonicalItems.size() != request.getItems().size()) {
            throw new IllegalStateException("Restaurant service không trả đủ dữ liệu canonical của món ăn");
        }

        order.setSubtotalPrice(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingFee(BigDecimal.ZERO);
        order.setTotalPrice(BigDecimal.ZERO);
        order.setItemDiscount(BigDecimal.ZERO);
        order.setShippingDiscount(BigDecimal.ZERO);
        order.setCustomerShippingFee(BigDecimal.ZERO);
        order.setGrossShippingFee(BigDecimal.ZERO);
        order.setPlatformSubsidy(BigDecimal.ZERO);
        order.setShopDiscount(BigDecimal.ZERO);
        return new CreateShell(orderRepository.saveAndFlush(order), canonicalItems);
    }

    private OrderResponse reserveAndSnapshotOrder(CreateOrderRequest request, Long principalId, Long userId,
                                                  PreparedOrderData prepared, CreateShell shell,
                                                  OrderCreateIdempotencyReceipt finalReceipt) {
        ValidatedOrderData validated = prepared.validated();
        Map<Long, BigDecimal> livestreamPrices = prepared.livestreamPrices();
        Order savedOrder = shell.order();
        Map<Long, ValidatedOrderData.ValidatedItemData> canonicalItems = shell.canonicalItems();
        return com.delivery.order.application.ReservationWorkflow.execute(
                new com.delivery.order.application.api.ReservationPorts<OrderResponse>() {
            private CheckoutReservationClient.FlashQuote flashQuote;
            private BigDecimal subtotal;
            private BigDecimal shippingFee;
            private BigDecimal discount;
            private CheckoutReservationClient.PromotionQuote promotionQuote;
            private CheckoutReservationClient.VoucherQuote legacyQuote;
            public boolean inventoryEnabled() { return inventoryReservationEnabled; }
            public boolean hasFlash() {
                return request.getItems().stream().anyMatch(item -> item.getFlashSaleItemId() != null);
            }
            public UUID newId() { return UUID.randomUUID(); }
            public void reserveInventory(UUID id) {
                OrderServiceImpl.this.reserveInventory(id, savedOrder.getId(), userId, principalId,
                        request.getRestaurantId(), request.getItems());
                savedOrder.setInventoryReservationId(id);
            }
            public void reserveFlash(UUID id) {
                flashQuote = OrderServiceImpl.this.reserveFlash(id, savedOrder.getId(), userId, principalId,
                        request.getRestaurantId(), request.getItems());
                savedOrder.setFlashSaleReservationId(id);
            }
            public void priceAndReserveVouchers(com.delivery.order.application.api.ReservationIds ids) {
            subtotal = CheckoutPricingPolicy.subtotal(request.getItems().stream().map(item -> {
                BigDecimal unitPrice = CheckoutPricingPolicy.regularOrLivestream(item.getMenuItemId(),
                        id -> requireCanonicalItem(canonicalItems, id).price(), livestreamPrices);
                if (item.getFlashSaleItemId() != null) {
                    CheckoutReservationClient.FlashLine line = flashQuote.byFlashSaleItemId()
                            .get(item.getFlashSaleItemId());
                    if (!CheckoutPricingPolicy.flashMatches(item.getMenuItemId(), item.getQuantity(),
                            line == null ? null : new CheckoutPricingPolicy.FlashPrice(
                                    line.menuItemId(), line.quantity(), line.unitPrice())))
                        throw new IllegalStateException("Flash-sale canonical item mismatch");
                    unitPrice = line.unitPrice();
                }
                return new CheckoutPricingPolicy.Line(unitPrice, item.getQuantity());
            }));

            shippingFee = shippingFeeCalculationService.calculateShippingFee(
                    validated.pickupLat(), validated.pickupLng(), request.getDeliveryLat(),
                    request.getDeliveryLng(), subtotal);
            discount = BigDecimal.ZERO;
            com.delivery.order.application.VoucherWorkflow.reserve(new com.delivery.order.application.api.VoucherPorts() {
                public List<Long> selectedIds() { return normalizedVoucherIds(request); }
                public String mode() { return request.getSelectionMode(); }
                public List<Long> autoSelect() {
                    return reservationClient.quoteVouchers(userId, principalId, request.getRestaurantId(),
                            subtotal, shippingFee, List.of(), "AUTO").selectedVoucherIds();
                }
                public UUID newId() { return UUID.randomUUID(); }
                public void reservePromotion(UUID id, List<Long> selectedVoucherIds) {
                promotionQuote = reserveVouchers(id, savedOrder.getId(), userId, principalId,
                        request.getRestaurantId(), subtotal, shippingFee, selectedVoucherIds);
                savedOrder.setPromotionReservationId(id);
                discount = promotionQuote.totalDiscount();
                savedOrder.setItemDiscount(promotionQuote.itemDiscount());
                savedOrder.setShippingDiscount(promotionQuote.shippingDiscount());
                savedOrder.setCustomerShippingFee(promotionQuote.customerShippingFee());
                savedOrder.setGrossShippingFee(shippingFee);
                savedOrder.setPlatformSubsidy(promotionQuote.platformSubsidy());
                savedOrder.setShopDiscount(promotionQuote.shopDiscount());
                savedOrder.setPromotionBreakdown(promotionQuote.breakdownJson());
                }
                public void reserveLegacy(UUID id, Long voucherId) {
                legacyQuote = reserveVoucher(id, savedOrder.getId(), userId, principalId,
                        voucherId, request.getRestaurantId(), subtotal, shippingFee);
                discount = legacyQuote.discountAmount();
                savedOrder.setVoucherReservationId(id);
                savedOrder.setItemDiscount(legacyQuote.itemDiscount());
                savedOrder.setShippingDiscount(legacyQuote.shippingDiscount());
                savedOrder.setCustomerShippingFee(CheckoutPricingPolicy.customerShipping(shippingFee, legacyQuote.shippingDiscount(),
                        legacyQuote.customerShippingFee()));
                savedOrder.setGrossShippingFee(shippingFee);
                savedOrder.setPlatformSubsidy(legacyQuote.platformSubsidy());
                savedOrder.setShopDiscount(legacyQuote.shopDiscount());
                savedOrder.setPromotionBreakdown(legacyQuote.breakdownJson());
                }
            }, ids);
            if (!CheckoutPricingPolicy.validDiscount(discount, subtotal, shippingFee))
                throw new IllegalStateException("Reservation service returned an invalid discount");

            savedOrder.setSubtotalPrice(subtotal);
            savedOrder.setShippingFee(shippingFee);
            savedOrder.setDiscountAmount(discount);
            if (promotionQuote == null && legacyQuote == null) {
                // No voucher: customer pays the canonical gross shipping fee.
                savedOrder.setItemDiscount(BigDecimal.ZERO);
                savedOrder.setShippingDiscount(BigDecimal.ZERO);
                savedOrder.setCustomerShippingFee(shippingFee);
                savedOrder.setGrossShippingFee(shippingFee);
                savedOrder.setPlatformSubsidy(BigDecimal.ZERO);
                savedOrder.setShopDiscount(BigDecimal.ZERO);
            }
            savedOrder.setTotalPrice(CheckoutPricingPolicy.total(subtotal, savedOrder.getItemDiscount(),
                    savedOrder.getCustomerShippingFee()));
            if (!CheckoutPricingPolicy.positivePayableFood(savedOrder.getTotalPrice(), shippingFee)) {
                throw new IllegalStateException("Voucher must leave a positive payable food amount");
            }

            }
            public void snapshot() {
            List<OrderItem> orderItems = request.getItems().stream().map(itemRequest -> {
                ValidatedOrderData.ValidatedItemData canonical = requireCanonicalItem(canonicalItems,
                        itemRequest.getMenuItemId());
                OrderItem item = orderMapper.orderItemRequestToOrderItem(itemRequest);
                item.setMenuItemName(canonical.menuItemName());
                item.setPrice(itemRequest.getFlashSaleItemId() == null
                        ? CheckoutPricingPolicy.regularOrLivestream(itemRequest.getMenuItemId(), id -> canonical.price(), livestreamPrices)
                        : flashQuote.byFlashSaleItemId().get(itemRequest.getFlashSaleItemId()).unitPrice());
                item.setOrder(savedOrder);
                return item;
            }).collect(Collectors.toCollection(ArrayList::new));
            orderItemRepository.saveAll(orderItems);
            savedOrder.setItems(orderItems);
            orderRepository.save(savedOrder);
            }
            public void commitInventory(UUID id) { OrderServiceImpl.this.commitInventory(id, savedOrder.getId()); }
            public boolean hasQuote() { return request.getQuoteId() != null; }
            public void consumeQuote() { checkoutQuoteService.consume(request.getQuoteId(), principalId, savedOrder.getId()); }
            public boolean hasReceipt() { return finalReceipt != null; }
            public void completeReceipt() { idempotencyService.complete(finalReceipt, savedOrder.getId()); }
            public void publishCreated() { orderEventPublisher.publishOrderCreatedEvent(savedOrder); }
            public OrderResponse response() {
            businessMetrics.record("order_created");
            log.info("Order created id={}, subtotal={}, discount={}, shipping={}, total={}", savedOrder.getId(),
                    subtotal, discount, shippingFee, savedOrder.getTotalPrice());
            return orderMapper.orderToOrderResponse(savedOrder);
            }
            public void releaseVoucher(UUID id) { reservationClient.releaseVoucher(id, savedOrder.getId()); }
            public void releasePromotion(UUID id) { reservationClient.releaseVouchers(id, savedOrder.getId(), principalId); }
            public void releaseFlash(UUID id) { reservationClient.releaseFlash(id, savedOrder.getId()); }
            public void releaseInventory(UUID id) { requireInventoryClient().release(id, savedOrder.getId()); }
        });
    }

    private OrderResponse loadOrderResponse(Long orderId, Long principalId, Long userId, String role) {
        if (transactionTemplate == null) {
            Order order = findOrderById(orderId);
            validateViewPermission(order, principalId, userId, role);
            return orderMapper.orderToOrderResponse(order);
        }
        return requireTransactionResult(transactionTemplate.execute(status -> {
            Order order = findOrderById(orderId);
            validateViewPermission(order, principalId, userId, role);
            return orderMapper.orderToOrderResponse(order);
        }));
    }

    private OrderResponse executeWriteTransaction(Supplier<OrderResponse> operation) {
        if (transactionTemplate == null) {
            return operation.get();
        }
        return requireTransactionResult(transactionTemplate.execute(status -> operation.get()));
    }

    private OrderResponse requireTransactionResult(OrderResponse response) {
        if (response == null) {
            throw new IllegalStateException("Order transaction returned no response");
        }
        return response;
    }

    private void reserveInventory(UUID reservationId, Long orderId, Long userId, Long principalId,
                                  Long restaurantId, List<CreateOrderRequest.OrderItemRequest> items) {
        requireInventoryClient().reserve(reservationId, orderId, userId, principalId, restaurantId, items);
    }

    private void commitInventory(UUID reservationId, Long orderId) {
        requireInventoryClient().commit(reservationId, orderId);
    }

    private com.delivery.order_service.service.InventoryReservationClient requireInventoryClient() {
        if (inventoryReservationClient == null) {
            throw new com.delivery.order_service.exception.OrderDependencyUnavailableException(
                    "restaurant-service", "Inventory reservation client is unavailable", null, 30);
        }
        return inventoryReservationClient;
    }

    /**
     * Preserve the legacy reservation payload during the identity migration
     * compatibility window. New principal/legacy pairs carry the stable
     * principal ID to downstream reservation services.
     */
    private CheckoutReservationClient.VoucherQuote reserveVoucher(
            UUID reservationId, Long orderId, Long userId, Long principalId,
            Long voucherId, Long restaurantId, BigDecimal subtotal, BigDecimal shippingFee) {
        if (principalId == null || Objects.equals(principalId, userId)) {
            return reservationClient.reserveVoucher(reservationId, orderId, userId, voucherId,
                    restaurantId, subtotal, shippingFee);
        }
        return reservationClient.reserveVoucher(reservationId, orderId, userId, principalId, voucherId,
                restaurantId, subtotal, shippingFee);
    }

    private CheckoutReservationClient.FlashQuote reserveFlash(
            UUID reservationId, Long orderId, Long userId, Long principalId,
            Long restaurantId, List<CreateOrderRequest.OrderItemRequest> requestItems) {
        if (principalId == null || Objects.equals(principalId, userId)) {
            return reservationClient.reserveFlash(reservationId, orderId, userId, restaurantId, requestItems);
        }
        return reservationClient.reserveFlash(reservationId, orderId, userId, principalId, restaurantId, requestItems);
    }

    private CheckoutReservationClient.PromotionQuote reserveVouchers(
            UUID reservationId, Long orderId, Long userId, Long principalId, Long restaurantId,
            BigDecimal subtotal, BigDecimal shippingFee, List<Long> voucherIds) {
        return reservationClient.reserveVouchers(reservationId, orderId, userId, principalId, restaurantId,
                subtotal, shippingFee, voucherIds);
    }

    private List<Long> normalizedVoucherIds(CreateOrderRequest request) {
        return com.delivery.order.domain.CheckoutReservationPolicy.selectedIds(request.getVoucherIds());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id, Long userId, String role) {
        return getOrderById(id, userId, userId, role);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id, Long principalId, Long legacyUserId, String role) {
        Order order = findOrderById(id);

        // Kiểm tra quyền xem
        validateViewPermission(order, principalId, legacyUserId, role);

        return orderMapper.orderToOrderResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersByUser(Long userId, Long requesterId, String role, Pageable pageable) {
        requireReadAdmission(OrderOwnershipPolicy.userListDenial(userId, requesterId, role));
        Page<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return orders.map(orderMapper::orderToOrderResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersByPrincipal(Long principalId, Long legacyUserId, String role, Pageable pageable) {
        requireReadAdmission(OrderOwnershipPolicy.principalListDenial(principalId, legacyUserId));
        Page<Order> orders = principalOwnershipEnforced
                ? orderRepository.findByUserPrincipalIdOrderByCreatedAtDesc(principalId, pageable)
                : orderRepository.findByPrincipalOrUnmigratedLegacyUserOrderByCreatedAtDesc(
                        principalId, legacyUserId, pageable);
        orders.forEach(order -> {
            if (order.getUserPrincipalId() == null) businessMetrics.identityLegacyFallback("customer_list");
        });
        return orders.map(orderMapper::orderToOrderResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersByRestaurantOwner(Long principalId, Long legacyOwnerId, String role,
            Pageable pageable) {
        requireReadAdmission(OrderOwnershipPolicy.restaurantOwnerListDenial(principalId, legacyOwnerId, role));

        // No hot-path lookup into Restaurant/Auth. New rows use the stable
        // principal; legacy rows remain readable only while their principal is absent.
        log.info("📋 Getting orders for restaurant owner principal={}", principalId);

        Page<Order> orders = principalOwnershipEnforced
                ? orderRepository.findByCreatorPrincipalIdOrderByCreatedAtDesc(principalId, pageable)
                : orderRepository.findByRestaurantOwnerPrincipalOrUnmigratedLegacyOrderByCreatedAtDesc(
                        principalId, legacyOwnerId, pageable);
        orders.forEach(order -> {
            if (order.getCreatorPrincipalId() == null) businessMetrics.identityLegacyFallback("restaurant_owner_list");
        });
        log.info("✅ Found {} orders for restaurant owner principal {}", orders.getTotalElements(), principalId);

        return orders.map(orderMapper::orderToOrderResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersByRestaurant(Long restaurantId, Pageable pageable) {
        log.info("📋 Getting orders for restaurant id={}", restaurantId);
        Page<Order> orders = orderRepository.findByRestaurantIdOrderByCreatedAtDesc(restaurantId, pageable);
        return orders.map(orderMapper::orderToOrderResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersByStatus(String status, Long userId, String role, Pageable pageable) {
        requireReadAdmission(OrderOwnershipPolicy.globalListDenial(role, true));
        Page<Order> orders = orderRepository.findByStatusOrderByCreatedAtDesc(
                OrderStatus.fromExternal(status), pageable);
        return orders.map(orderMapper::orderToOrderResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getAllOrders(Long userId, String role, Pageable pageable) {
        // Chỉ admin mới được xem tất cả đơn hàng
        requireReadAdmission(OrderOwnershipPolicy.globalListDenial(role, false));

        Page<Order> orders = orderRepository.findAllByOrderByCreatedAtDesc(pageable);
        return orders.map(orderMapper::orderToOrderResponse);
    }

    private Order findOrderById(Long id) {
        return orderRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng với ID: " + id));
    }

    private Order findOrderByIdForUpdate(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng với ID: " + id));
    }

    private ValidatedOrderData.ValidatedItemData requireCanonicalItem(
            Map<Long, ValidatedOrderData.ValidatedItemData> canonicalItems,
            Long menuItemId) {
        ValidatedOrderData.ValidatedItemData item = canonicalItems.get(menuItemId);
        if (item == null) {
            throw new IllegalStateException("Thiếu dữ liệu canonical cho menu item " + menuItemId);
        }
        return item;
    }

    private void requireReadAdmission(String denial) {
        if (denial != null) throw new AccessDeniedException(denial);
    }

    private void validateViewPermission(Order order, Long principalId, Long legacyUserId, String role) {
        validateOwnership(order, principalId, legacyUserId, role, false);
    }

    private void validateOwnership(Order order, Long principalId, Long legacyUserId, String role,
                                   boolean cancellation) {
        var owners = new OrderOwnershipPolicy.Owners(order.getUserPrincipalId(), order.getUserId(),
                order.getCreatorPrincipalId(), order.getCreatorId(), order.getShipperId());
        var access = OrderOwnershipPolicy.access(owners, principalId, legacyUserId, role,
                principalOwnershipEnforced, cancellation);
        switch (access) {
            case ALLOWED -> { }
            case CUSTOMER_LEGACY -> businessMetrics.identityLegacyFallback(cancellation ? "customer_cancel" : "customer_read");
            case RESTAURANT_LEGACY -> businessMetrics.identityLegacyFallback(cancellation ? "restaurant_owner_cancel" : "restaurant_owner_read");
            case DENIED -> throw new AccessDeniedException(cancellation
                    ? "Bạn không có quyền hủy đơn hàng này" : "Bạn không có quyền xem đơn hàng này");
        }
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId, Long userId, String role, String reason) {
        return cancelOrder(orderId, userId, userId, role, reason);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId, Long principalId, Long userId, String role, String reason) {
        // Lấy thông tin đơn hàng
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng với ID: " + orderId));

        // Kiểm tra quyền hủy đơn hàng
        validateCancelOrderPermission(order, principalId, userId, role);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            requireExactCancellationReplay(order, principalId, userId, reason);
            return orderMapper.orderToOrderResponse(order);
        }

        // Kiểm tra điều kiện hủy đơn hàng
        validateCancelOrderConditions(order, userId, role);

        // Lưu trạng thái cũ để gửi event
        String previousStatus = order.getStatus().name();

        // Cập nhật trạng thái thành CANCELLED
        order.getStatus().requireTransitionTo(OrderStatus.CANCELLED);
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelReason(reason);
        order.setCancelledBy(userId);
        order.setCancelledByPrincipalId(principalId);
        order.setUpdatedAt(LocalDateTime.now());
        order = orderRepository.save(order);
        businessMetrics.record("order_cancelled");

        // ✅ Publish the cancellation for Delivery and the refund boundary.  The
        // source is part of the durable event so a future provider rollout cannot
        // mistake an admin/customer exception for an automatic refund trigger.
        var intent = OrderCancellationPolicy.actorIntent(role);
        orderEventPublisher.publishOrderCancelledEvent(order, previousStatus, userId,
                intent.source(), intent.reasonCode());

        return orderMapper.orderToOrderResponse(order);
    }

    private void validateCancelOrderPermission(Order order, Long principalId, Long legacyUserId, String role) {
        validateOwnership(order, principalId, legacyUserId, role, true);
    }

    private void requireExactCancellationReplay(Order order, Long principalId, Long legacyUserId, String reason) {
        boolean legacy = OrderCancellationPolicy.requireExactReplay(order.getCancelledByPrincipalId(),
                order.getCancelledBy(), order.getCancelReason(), principalId, legacyUserId, reason,
                principalOwnershipEnforced);
        if (legacy) businessMetrics.identityLegacyFallback("cancel_replay");
        log.info("Order {} cancellation already applied by principal {}, skipping exact replay",
                order.getId(), principalId);
    }

    private void validateCancelOrderConditions(Order order, Long userId, String role) {
        OrderCancellationPolicy.requireCancellable(order.getStatus() == null ? null : order.getStatus().toDomain(), role);
    }

    /**
     * ✅ Cập nhật order status khi không tìm được shipper
     */
    @Override
    @Transactional
    public void updateOrderStatusFromShipperNotFoundEvent(ShipperNotFoundEvent event) {
        try {
            log.info("🔄 Processing ShipperNotFoundEvent for order: {}, delivery: {}",
                    event.getOrderId(), event.getDeliveryId());

            // Tìm order theo orderId
            Order order = orderRepository.findByIdForUpdate(event.getOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Order not found with id: " + event.getOrderId()));

            // Chỉ cập nhật nếu order đang ở trạng thái phù hợp (PENDING, CONFIRMED)
            if (!com.delivery.order.domain.SagaStatusPolicy.appliesShipperNotFound(order.getStatus().toDomain())) {
                log.warn("⚠️ Order {} not in a matching status, current status: {}",
                        order.getId(), order.getStatus());
                return;
            }

            // Cập nhật status và note về việc không tìm được shipper
            OrderStatus previousStatus = order.getStatus();
            order.getStatus().requireTransitionTo(OrderStatus.SHIPPER_NOT_FOUND);
            order.setStatus(OrderStatus.SHIPPER_NOT_FOUND);
            order.setNotes("Không tìm được shipper sau " + event.getRetryAttempts() + " lần thử");

            orderRepository.save(order);

            // SHIPPER_NOT_FOUND is deliberately not rewritten to CANCELLED, but
            // it is still a deterministic pre-pickup compensation/refund trigger.
            orderEventPublisher.publishRefundEligibilityEvent(order, previousStatus.name(),
                    event.getReason());

            log.info("✅ Updated order {} status from {} to SHIPPER_NOT_FOUND after {} retry attempts",
                    order.getId(), previousStatus, event.getRetryAttempts());

            // Customer notification is derived from Delivery's canonical
            // delivery.status-updated outbox event. Order must not publish a
            // second notification for the same terminal matching outcome.

        } catch (Exception e) {
            log.error("💥 Error updating order status from ShipperNotFoundEvent for order: {}: {}",
                     event.getOrderId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to apply shipper-not-found status", e);
        }
    }

}
