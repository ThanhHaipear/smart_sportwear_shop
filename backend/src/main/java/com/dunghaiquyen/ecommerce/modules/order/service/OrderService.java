package com.dunghaiquyen.ecommerce.modules.order.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.common.response.PageMeta;
import com.dunghaiquyen.ecommerce.common.time.AppTimeZone;
import com.dunghaiquyen.ecommerce.config.AppShippingProperties;
import com.dunghaiquyen.ecommerce.config.CacheConfig;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.cart.entity.Cart;
import com.dunghaiquyen.ecommerce.modules.cart.entity.CartItem;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartItemRepository;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartRepository;
import com.dunghaiquyen.ecommerce.modules.inventory.service.InventoryService;
import com.dunghaiquyen.ecommerce.modules.order.dto.AdminOrderListQuery;
import com.dunghaiquyen.ecommerce.modules.order.dto.AdminOrderResponse;
import com.dunghaiquyen.ecommerce.modules.order.dto.CreateOrderRequest;
import com.dunghaiquyen.ecommerce.modules.order.dto.OrderItemResponse;
import com.dunghaiquyen.ecommerce.modules.order.dto.OrderListQuery;
import com.dunghaiquyen.ecommerce.modules.order.dto.OrderResponse;
import com.dunghaiquyen.ecommerce.modules.order.dto.UpdateOrderStatusRequest;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderItem;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatus;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatusHistory;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.order.mapper.OrderMapper;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderItemRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderStatusHistoryRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.spec.OrderSpecifications;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.product.entity.VariantStatus;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductVariantRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.util.ThumbnailResolver;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.dunghaiquyen.ecommerce.modules.payment.entity.Payment;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentProvider;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentRepository;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.dunghaiquyen.ecommerce.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    /** Vietnamese business tax code: 10 digits, optionally with a 3-digit branch suffix (e.g. 0123456789-001). */
    private static final java.util.regex.Pattern TAX_CODE_PATTERN =
            java.util.regex.Pattern.compile("^\\d{10}(-\\d{3})?$");

    private static final java.util.Set<OrderStatus> INVOICE_BLOCKED_STATUSES =
            java.util.Set.of(OrderStatus.CANCELLED);

    /** Staff-editable operational states; cancellation uses its dedicated endpoint. */
    private static final java.util.Set<OrderStatus> ADMIN_EDITABLE_STATUSES = java.util.Set.of(
            OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED, OrderStatus.PACKING,
            OrderStatus.SHIPPING, OrderStatus.DELIVERED);

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final AddressRepository addressRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final PaymentRepository paymentRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageRepository productImageRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final com.dunghaiquyen.ecommerce.modules.inventory.repository.InventoryTransactionRepository
            inventoryTransactionRepository;
    private final OrderMapper orderMapper;
    private final AppShippingProperties shippingProperties;

    public OrderService(
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            AddressRepository addressRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            PaymentRepository paymentRepository,
            ProductVariantRepository variantRepository,
            ProductImageRepository productImageRepository,
            UserRepository userRepository,
            InventoryService inventoryService,
            com.dunghaiquyen.ecommerce.modules.inventory.repository.InventoryTransactionRepository
                    inventoryTransactionRepository,
            OrderMapper orderMapper,
            AppShippingProperties shippingProperties) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.addressRepository = addressRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.paymentRepository = paymentRepository;
        this.variantRepository = variantRepository;
        this.productImageRepository = productImageRepository;
        this.userRepository = userRepository;
        this.inventoryService = inventoryService;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.orderMapper = orderMapper;
        this.shippingProperties = shippingProperties;
    }

    public record ListResult<T>(List<T> items, PageMeta meta) {
    }

    /**
     * Re-validates every cart line against CURRENT variant/product state
     * (never trusts anything the client might already have cached from an
     * earlier cart read) before writing anything, then creates the order,
     * reserves stock and clears the cart - all in one transaction so a
     * failure at any point leaves no order, no stock change, and no cart
     * mutation behind.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_PRODUCTS, allEntries = true)
    })
    public OrderResponse createOrderFromCart(UUID userId, CreateOrderRequest request) {
        boolean buyNow = request.buyNowVariantId() != null;
        if (buyNow && request.cartItemIds() != null && !request.cartItemIds().isEmpty()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose either buy now or cart checkout");
        }

        List<CartItem> cartItems = List.of();
        List<ValidatedLine> validated = new ArrayList<>();
        if (buyNow) {
            int quantity = requireBuyNowQuantity(request.buyNowQuantity());
            VariantCheck check = checkVariant(request.buyNowVariantId(), quantity, true);
            throwIfInvalid(check);
            validated.add(new ValidatedLine(quantity, check.variant()));
        } else {
            // Lock the cart so the same cart lines cannot be checked out twice concurrently.
            Cart cart = cartRepository.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Cart is empty"));
            cartItems = selectCartItems(cartItemRepository.findAllByCartId(cart.getId()), request.cartItemIds());
            if (cartItems.isEmpty()) {
                throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Cart is empty");
            }

            List<CartItem> sortedItems = cartItems.stream()
                    .sorted(Comparator.comparing(ci -> ci.getVariant().getId()))
                    .toList();
            for (CartItem cartItem : sortedItems) {
                LineCheck check = checkLine(cartItem, true);
                if (!check.isValid()) {
                    if (check.errorStatus() == HttpStatus.NOT_FOUND) {
                        throw new ResourceNotFoundException(check.errorMessage());
                    }
                    throw new BusinessRuleException(check.errorStatus(), check.errorMessage());
                }
                validated.add(new ValidatedLine(cartItem.getQuantity(), check.variant()));
            }
        }

        Address address = addressRepository.findById(request.addressId())
                .filter(a -> a.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        // Phase 2: build the order using prices read just now, under lock - never
        // anything cached from an earlier cart response.
        User user = userRepository.getReferenceById(userId);
        Order order = new Order();
        order.setUser(user);
        order.setOrderCode(generateOrderCode());
        order.setAddressSnapshotJson(buildAddressSnapshot(address));
        order.setPaymentMethod(request.paymentMethod());
        order.setNote(request.note());
        applyInvoiceRequest(order, request);

        BigDecimal subtotal = BigDecimal.ZERO;
        for (ValidatedLine line : validated) {
            ProductVariant variant = line.variant();
            // Read the current SKU price while the variant is locked.
            BigDecimal unitPrice = effectiveUnitPrice(variant);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity()));
            subtotal = subtotal.add(lineTotal);

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(variant.getProduct());
            orderItem.setVariant(variant);
            orderItem.setProductNameSnapshot(variant.getProduct().getName());
            orderItem.setSkuSnapshot(variant.getSku());
            orderItem.setSizeSnapshot(variant.getSize());
            orderItem.setColorSnapshot(variant.getColor());
            orderItem.setUnitPriceSnapshot(unitPrice);
            orderItem.setQuantity(line.quantity());
            orderItem.setLineTotal(lineTotal);
            orderItem.setNetLineAmount(lineTotal);
            order.getItems().add(orderItem);
        }

        order.setSubtotalAmount(subtotal);
        // See calculateShippingFee's javadoc - also used by CheckoutPreviewService,
        // so preview and the real order always agree on this number.
        order.setShippingFee(calculateShippingFee(subtotal));

        // Combo/promotion pricing is deferred until its V2 schema exists.
        BigDecimal discount = BigDecimal.ZERO;
        order.setDiscountAmount(discount);
        order.setTotalAmount(subtotal.add(order.getShippingFee()).subtract(discount));

        // Allocate the exact order discount deterministically across immutable
        // item snapshots. The final item absorbs any scale remainder.
        BigDecimal remainingDiscount = discount;
        for (OrderItem item : order.getItems()) {
            BigDecimal allocated = remainingDiscount.min(item.getLineTotal());
            item.setDiscountAllocated(allocated);
            item.setNetLineAmount(item.getLineTotal().subtract(allocated));
            remainingDiscount = remainingDiscount.subtract(allocated);
        }

        order = orderRepository.save(order);
        appendStatusHistory(order, null, OrderStatus.PENDING_CONFIRMATION, user, "Order placed");
        if (order.getPaymentMethod() == PaymentMethod.COD) {
            Payment payment = new Payment();
            payment.setOrder(order);
            payment.setProvider(PaymentProvider.COD);
            payment.setTransactionRef("COD:" + order.getId());
            payment.setAmount(order.getTotalAmount());
            payment.setStatus(PaymentStatus.UNPAID);
            paymentRepository.save(payment);
        }

        // Phase 3: reserve stock for every line, reusing the SAME locked variant
        // entities from Phase 1 (still locked for the rest of this transaction).
        for (ValidatedLine line : validated) {
            inventoryService.recordReserve(line.variant(), line.quantity(), order, user);
        }

        // Phase 4: the cart that was just turned into an order must not still
        // offer the same items for a second checkout.
        if (!buyNow) {
            cartItemRepository.deleteAll(cartItems);
        }

        return assembleResponse(order);
    }

    @Transactional(readOnly = true)
    public ListResult<OrderResponse> listMyOrders(UUID userId, OrderListQuery query) {
        Specification<Order> spec = OrderSpecifications.belongsToUser(userId);
        if (query.status() != null) {
            spec = spec.and(OrderSpecifications.hasStatus(query.status()));
        }
        Pageable pageable = PageRequest.of(
                resolvePageIndex(query.page()), resolveLimit(query.limit()), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Order> page = orderRepository.findAll(spec, pageable);
        List<OrderResponse> items = assembleResponses(page.getContent());
        return new ListResult<>(items, PageMeta.from(page));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderDetail(UUID orderId, UUID callerId, UserRole callerRole) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (callerRole == UserRole.CUSTOMER && !order.getUser().getId().equals(callerId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        return assembleResponse(order);
    }

    /**
     * A real invoice is only issued for a completed sale: paid, and not
     * cancelled or mid-cancellation. Lazily assigns invoiceNumber the first
     * time an eligible order's invoice is actually requested, rather than at
     * order creation or at payment time - mirrors how a shop only writes the
     * invoice once someone asks for it, and keeps the number stable across
     * repeated views/reprints (issued once, then reused).
     */
    @Transactional
    public OrderResponse getOrderInvoice(UUID orderId, UUID customerId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getUser().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        if (!isInvoiceEligible(order)) {
            throw new BusinessRuleException(HttpStatus.CONFLICT,
                    "Order must be paid and not cancelled before an invoice can be issued");
        }
        if (order.getInvoiceNumber() == null) {
            order.setInvoiceNumber(generateInvoiceNumber());
            order = orderRepository.save(order);
        }
        return assembleResponse(order);
    }

    private boolean isInvoiceEligible(Order order) {
        return order.getPaymentStatus() == PaymentStatus.PAID
                && !INVOICE_BLOCKED_STATUSES.contains(order.getOrderStatus());
    }

    private String generateInvoiceNumber() {
        long sequence = orderRepository.nextInvoiceSequence();
        int year = LocalDate.now(AppTimeZone.ZONE).getYear();
        return String.format("HD-%d-%06d", year, sequence);
    }

    /**
     * All three company fields are required together (or none at all) -
     * a cross-field rule that does not fit a single-field bean validation
     * annotation on CreateOrderRequest, so it is checked here instead.
     */
    private void applyInvoiceRequest(Order order, CreateOrderRequest request) {
        boolean requested = Boolean.TRUE.equals(request.invoiceRequested());
        if (!requested) {
            return;
        }
        String companyName = request.invoiceCompanyName() != null ? request.invoiceCompanyName().trim() : "";
        String taxCode = request.invoiceTaxCode() != null ? request.invoiceTaxCode().trim() : "";
        String companyAddress = request.invoiceCompanyAddress() != null ? request.invoiceCompanyAddress().trim() : "";
        if (companyName.isEmpty() || taxCode.isEmpty() || companyAddress.isEmpty()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Company name, tax code, and address are required to request a company invoice");
        }
        if (!TAX_CODE_PATTERN.matcher(taxCode).matches()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid tax code format");
        }
        order.setInvoiceRequested(true);
        order.setInvoiceCompanyName(companyName);
        order.setInvoiceTaxCode(taxCode);
        order.setInvoiceCompanyAddress(companyAddress);
    }

    @Transactional(readOnly = true)
    public ListResult<AdminOrderResponse> listOrdersForAdmin(AdminOrderListQuery query) {
        Specification<Order> spec = OrderSpecifications.fetchUser();
        if (query.customerId() != null) {
            spec = spec.and(OrderSpecifications.belongsToUser(query.customerId()));
        }
        if (query.productId() != null) {
            spec = spec.and(OrderSpecifications.containsProduct(query.productId()));
        }
        if (query.status() != null) {
            spec = spec.and(OrderSpecifications.hasStatus(query.status()));
        }
        if (query.paymentStatus() != null) {
            spec = spec.and(OrderSpecifications.hasPaymentStatus(query.paymentStatus()));
        }
        if (query.paymentMethod() != null) {
            spec = spec.and(OrderSpecifications.hasPaymentMethod(query.paymentMethod()));
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            spec = spec.and(OrderSpecifications.keywordMatches(query.keyword().trim()));
        }
        if (query.dateFrom() != null) {
            spec = spec.and(OrderSpecifications.createdFrom(
                    query.dateFrom().atStartOfDay(AppTimeZone.ZONE).toInstant()));
        }
        if (query.dateTo() != null) {
            spec = spec.and(OrderSpecifications.createdTo(
                    LocalDateTime.of(query.dateTo(), java.time.LocalTime.MAX).atZone(AppTimeZone.ZONE).toInstant()));
        }
        Pageable pageable = PageRequest.of(
                resolvePageIndex(query.page()), resolveLimit(query.limit()), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Order> page = orderRepository.findAll(spec, pageable);
        List<AdminOrderResponse> items = assembleAdminResponses(page.getContent());
        return new ListResult<>(items, PageMeta.from(page));
    }

    @Transactional(readOnly = true)
    public AdminOrderResponse getOrderDetailForAdmin(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return assembleAdminResponse(order);
    }

    @Transactional(readOnly = true)
    public List<AdminOrderResponse> getPackingSlipDetailsForAdmin(List<UUID> requestedOrderIds) {
        List<UUID> orderIds = requestedOrderIds.stream().distinct().toList();
        Map<UUID, Order> ordersById = orderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
        if (ordersById.size() != orderIds.size()) {
            throw new ResourceNotFoundException("One or more orders were not found");
        }

        List<Order> orders = orderIds.stream().map(ordersById::get).toList();
        return assembleAdminResponses(orders, false);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_PRODUCTS, allEntries = true)
    })
    public AdminOrderResponse updateOrderStatus(UUID orderId, UpdateOrderStatusRequest request, User actor) {
        // Serialize corrections with checkout/cancellation/refund side effects.
        // Staff may correct freely between operational states, but cancellation
        // states are owned by the explicit request/approve/reject workflow.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "Cancelled order is final and cannot be updated");
        }
        if (!ADMIN_EDITABLE_STATUSES.contains(request.status())) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "Use the cancellation workflow to cancel an order");
        }
        order = applyStatusTransition(order, request.status(), actor);
        if (request.note() != null) {
            order.setInternalNote(request.note());
            order = orderRepository.save(order);
        }
        return assembleAdminResponse(order);
    }

    /** Customer cancellation is immediate before DELIVERED; refund is separate. */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_PRODUCTS, allEntries = true)
    })
    public OrderResponse cancelOwnOrder(UUID orderId, UUID customerId, String reason) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getUser().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        OrderStatus current = order.getOrderStatus();
        if (current == OrderStatus.CANCELLED) {
            return assembleResponse(order);
        }
        if (!ADMIN_EDITABLE_STATUSES.contains(current) || current == OrderStatus.DELIVERED) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "Order can no longer be cancelled");
        }
        User actor = order.getUser();
        order = applyStatusTransition(order, OrderStatus.CANCELLED, actor);
        order.setCancellationRequestedBy(
                com.dunghaiquyen.ecommerce.modules.order.entity.CancellationRequestedBy.CUSTOMER);
        order.setCancellationReason(reason == null || reason.isBlank() ? "Customer cancelled order" : reason.trim());
        order.setCancellationRequestedAt(java.time.Instant.now());
        if (reason != null && !reason.isBlank()) {
            order.setInternalNote("Cancelled by customer: " + reason.trim());
            order = orderRepository.save(order);
        }
        return assembleResponse(order);
    }

    /** Staff cancellation is immediate until delivery; refund is tracked separately. */
    @Transactional
    public AdminOrderResponse cancelOrderByStaff(UUID orderId, String reason, User actor) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        OrderStatus current = order.getOrderStatus();
        if (current == OrderStatus.CANCELLED) {
            return assembleAdminResponse(order);
        }
        if (!ADMIN_EDITABLE_STATUSES.contains(current) || current == OrderStatus.DELIVERED) {
            throw new BusinessRuleException(HttpStatus.CONFLICT,
                    "Order can no longer be cancelled by staff");
        }
        order = applyStatusTransition(order, OrderStatus.CANCELLED, actor);
        order.setCancellationRequestedBy(
                com.dunghaiquyen.ecommerce.modules.order.entity.CancellationRequestedBy.STAFF);
        order.setCancellationReason(reason == null || reason.isBlank() ? "Cửa hàng chủ động hủy đơn" : reason.trim());
        order.setCancellationRequestedAt(java.time.Instant.now());
        order.setInternalNote("Cancelled by staff: " + order.getCancellationReason());
        return assembleAdminResponse(orderRepository.save(order));
    }

    /** Applies the inventory side effect and persists one order-status change. */
    private Order applyStatusTransition(Order order, OrderStatus target, User actor) {
        OrderStatus current = order.getOrderStatus();
        if (current == target) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "Order already has status " + target);
        }
        OrderFinancialPolicy.validateTransition(order, target);

        List<OrderItem> items = orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId());
        // Lock variants in a fixed order before mutating, same deadlock-avoidance
        // reasoning as order creation.
        List<OrderItem> sortedItems = items.stream()
                .sorted(Comparator.comparing(i -> i.getVariant().getId()))
                .toList();

        boolean wasEverDeducted = inventoryTransactionRepository.existsByOrder_IdAndType(
                order.getId(), com.dunghaiquyen.ecommerce.modules.inventory.entity.InventoryTransactionType.ORDER_CONFIRM_DEDUCT);
        boolean targetUsesDeductedStock = java.util.Set.of(
                OrderStatus.CONFIRMED, OrderStatus.PACKING, OrderStatus.SHIPPING, OrderStatus.DELIVERED).contains(target);
        if (targetUsesDeductedStock && !wasEverDeducted) {
            for (OrderItem item : sortedItems) {
                inventoryService.confirmDeduct(item.getVariant().getId(), item.getQuantity(), order, actor);
            }
        } else if (target == OrderStatus.CANCELLED) {
            // A cancellation reaching CANCELLED either never had real stock deducted
            // (PENDING_CONFIRMATION -> CANCELLED: still just reserved, release() undoes
            // that) or passed through CONFIRMED/PACKING first (real stock was deducted
            // there via ORDER_CONFIRM_DEDUCT) - restockReturn() is the correct undo for
            // that case, release() would incorrectly touch reservedQuantity again.
            for (OrderItem item : sortedItems) {
                if (wasEverDeducted) {
                    inventoryService.restockCancelledOrder(item.getVariant().getId(), item.getQuantity(), order, actor);
                } else {
                    inventoryService.release(item.getVariant().getId(), item.getQuantity(), order, actor);
                }
            }
        }

        order.setOrderStatus(target);
        if (target == OrderStatus.DELIVERED) {
            // Returns module's return-window eligibility check reads this - see Order.deliveredAt's javadoc.
            if (order.getDeliveredAt() == null) {
                java.time.Instant deliveredAt = java.time.Instant.now();
                order.setDeliveredAt(deliveredAt);
                order.setReturnEligibleUntil(deliveredAt.plus(7, java.time.temporal.ChronoUnit.DAYS));
            }
            if (order.getPaymentMethod() == PaymentMethod.COD) {
                order.setPaymentStatus(PaymentStatus.PAID);
                Payment payment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(order.getId())
                        .orElseThrow(() -> new BusinessRuleException(
                                HttpStatus.CONFLICT, "COD payment record not found"));
                if (payment.getProvider() != PaymentProvider.COD) {
                    throw new BusinessRuleException(HttpStatus.CONFLICT, "Invalid COD payment record");
                }
                if (payment.getStatus() == PaymentStatus.UNPAID) {
                    payment.setStatus(PaymentStatus.PAID);
                    payment.setPaidAt(java.time.Instant.now());
                    paymentRepository.save(payment);
                }
            }
        }
        order = orderRepository.save(order);
        appendStatusHistory(order, current, target, actor, null);

        return order;
    }

    private void appendStatusHistory(
            Order order,
            OrderStatus from,
            OrderStatus to,
            User actor,
            String reason) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setActorUser(actor);
        history.setReason(reason);
        orderStatusHistoryRepository.save(history);
    }

    private record ValidatedLine(int quantity, ProductVariant variant) {
    }

    /**
     * Single line-level result shared by createOrderFromCart (lockForUpdate=true,
     * throws on the first invalid line) and checkCartLines (lockForUpdate=false,
     * collects every line's verdict) - the exact same rules (variant exists,
     * ACTIVE product/variant, enough stock) back both call sites, which is the
     * whole point: a checkout preview must never say "fine" about something the
     * real checkout would then reject, or vice versa.
     */
    private record LineCheck(
            CartItem cartItem, ProductVariant variant, BigDecimal lineTotal, HttpStatus errorStatus, String errorMessage) {
        boolean isValid() {
            return errorMessage == null;
        }
    }

    private LineCheck checkLine(CartItem cartItem, boolean lockForUpdate) {
        VariantCheck check = checkVariant(cartItem.getVariant().getId(), cartItem.getQuantity(), lockForUpdate);
        return new LineCheck(cartItem, check.variant(), check.lineTotal(), check.errorStatus(), check.errorMessage());
    }

    private record VariantCheck(
            ProductVariant variant, BigDecimal lineTotal, HttpStatus errorStatus, String errorMessage) {
        boolean isValid() { return errorMessage == null; }
    }

    private VariantCheck checkVariant(UUID variantId, int quantity, boolean lockForUpdate) {
        var variantOpt = lockForUpdate
                ? variantRepository.findByIdForUpdate(variantId)
                : variantRepository.findById(variantId);
        if (variantOpt.isEmpty()) {
            return new VariantCheck(null, null, HttpStatus.NOT_FOUND, "Variant not found");
        }
        ProductVariant variant = variantOpt.get();
        if (variant.getStatus() != VariantStatus.ACTIVE) {
            return new VariantCheck(
                    variant, null, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Variant " + variant.getSku() + " is no longer available");
        }
        if (variant.getProduct().getStatus() != ProductStatus.ACTIVE) {
            return new VariantCheck(
                    variant, null, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Product " + variant.getProduct().getName() + " is no longer available");
        }
        int available = variant.getStockQuantity() - variant.getReservedQuantity();
        if (quantity > available) {
            return new VariantCheck(
                    variant, null, HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient stock for " + variant.getSku());
        }
        BigDecimal lineTotal = effectiveUnitPrice(variant).multiply(BigDecimal.valueOf(quantity));
        return new VariantCheck(variant, lineTotal, null, null);
    }

    /**
     * Baseline SKU price shared by cart preview and order creation.
     */
    private BigDecimal effectiveUnitPrice(ProductVariant variant) {
        return variant.getPrice();
    }

    private int requireBuyNowQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Buy now quantity must be greater than 0");
        }
        return quantity;
    }

    private void throwIfInvalid(VariantCheck check) {
        if (check.isValid()) return;
        if (check.errorStatus() == HttpStatus.NOT_FOUND) throw new ResourceNotFoundException(check.errorMessage());
        throw new BusinessRuleException(check.errorStatus(), check.errorMessage());
    }

    /** One cart line's checkout-preview verdict - public/DTO-safe (no entity references), used by CheckoutPreviewService. */
    public record CartLineCheck(
            UUID variantId,
            UUID productId,
            String productName,
            String sku,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            boolean valid,
            String errorMessage) {
    }

    public record CartLinesCheckResult(List<CartLineCheck> lines) {
        public boolean allValid() {
            return lines.stream().allMatch(CartLineCheck::valid);
        }

        public BigDecimal validSubtotal() {
            return lines.stream()
                    .filter(CartLineCheck::valid)
                    .map(CartLineCheck::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * Read-only counterpart of createOrderFromCart's Phase 1 validation loop -
     * deliberately does NOT pessimistic-lock any variant row (lockForUpdate=false
     * in checkLine): a preview is, by definition, not about to write anything,
     * and holding write locks for a "just looking" call would only contend with
     * real checkouts for no benefit. Used by CheckoutPreviewService.
     */
    @Transactional(readOnly = true)
    public CartLinesCheckResult checkCartLines(UUID userId) {
        return checkCartLines(userId, null);
    }

    @Transactional(readOnly = true)
    public CartLinesCheckResult checkCartLines(UUID userId, List<UUID> cartItemIds) {
        var cartOpt = cartRepository.findByUserId(userId);
        if (cartOpt.isEmpty()) {
            return new CartLinesCheckResult(List.of());
        }
        List<CartItem> items = selectCartItems(
                cartItemRepository.findAllByCartId(cartOpt.get().getId()), cartItemIds);
        List<CartLineCheck> checks = items.stream().map(ci -> toCartLineCheck(ci, checkLine(ci, false))).toList();
        return new CartLinesCheckResult(checks);
    }

    @Transactional(readOnly = true)
    public CartLinesCheckResult checkBuyNowLine(UUID variantId, Integer requestedQuantity) {
        int quantity = requireBuyNowQuantity(requestedQuantity);
        VariantCheck check = checkVariant(variantId, quantity, false);
        ProductVariant variant = check.variant();
        CartLineCheck line = new CartLineCheck(
                variantId,
                variant != null ? variant.getProduct().getId() : null,
                variant != null ? variant.getProduct().getName() : null,
                variant != null ? variant.getSku() : null,
                quantity,
                variant != null ? effectiveUnitPrice(variant) : null,
                check.isValid() ? check.lineTotal() : BigDecimal.ZERO,
                check.isValid(),
                check.errorMessage());
        return new CartLinesCheckResult(List.of(line));
    }

    private List<CartItem> selectCartItems(List<CartItem> allItems, List<UUID> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return allItems;
        }
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>(requestedIds);
        List<CartItem> selected = allItems.stream().filter(item -> ids.contains(item.getId())).toList();
        if (selected.size() != ids.size()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Selected cart items are invalid");
        }
        return selected;
    }

    private CartLineCheck toCartLineCheck(CartItem cartItem, LineCheck check) {
        ProductVariant variant = check.variant();
        UUID variantId = cartItem.getVariant().getId();
        UUID productId = variant != null ? variant.getProduct().getId() : null;
        String productName = variant != null ? variant.getProduct().getName() : null;
        String sku = variant != null ? variant.getSku() : null;
        BigDecimal unitPrice = variant != null ? effectiveUnitPrice(variant) : null;
        BigDecimal lineTotal = check.isValid() ? check.lineTotal() : BigDecimal.ZERO;
        return new CartLineCheck(
                variantId, productId, productName, sku, cartItem.getQuantity(), unitPrice, lineTotal,
                check.isValid(), check.errorMessage());
    }

    /**
     * No real shipping engine exists this phase (AppShippingProperties' javadoc) -
     * flat fee, waived once subtotal reaches the free-shipping threshold. Public
     * so CheckoutPreviewService computes the exact same number a real checkout
     * would, instead of re-implementing (and risking drifting from) this rule.
     */
    public BigDecimal calculateShippingFee(BigDecimal subtotal) {
        BigDecimal threshold = shippingProperties.freeShippingThreshold();
        if (threshold != null && subtotal.compareTo(threshold) >= 0) {
            return BigDecimal.ZERO;
        }
        return shippingProperties.flatFee() != null ? shippingProperties.flatFee() : BigDecimal.ZERO;
    }

    private String generateOrderCode() {
        String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String uniquePart = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        return "ORD" + datePart + uniquePart;
    }

    private Map<String, Object> buildAddressSnapshot(Address address) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("receiverName", address.getReceiverName());
        snapshot.put("phone", address.getPhone());
        snapshot.put("province", address.getProvince());
        snapshot.put("district", address.getDistrict());
        snapshot.put("ward", address.getWard());
        snapshot.put("addressLine", address.getAddressLine());
        return snapshot;
    }

    private int resolvePageIndex(Integer page) {
        return (page != null && page > 0) ? page - 1 : 0;
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private OrderResponse assembleResponse(Order order) {
        List<OrderItem> orderItems = orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId());
        Map<UUID, String> thumbnails = resolveThumbnails(orderItems);
        List<OrderItemResponse> items = orderItems.stream()
                .map(item -> orderMapper.toItemResponse(item, thumbnails.get(item.getProduct().getId())))
                .toList();
        return toResponse(order, items);
    }

    private List<OrderResponse> assembleResponses(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<OrderItemResponse>> itemsByOrder = itemsByOrderId(orders);
        return orders.stream()
                .map(o -> toResponse(o, itemsByOrder.getOrDefault(o.getId(), List.of())))
                .toList();
    }

    private AdminOrderResponse assembleAdminResponse(Order order) {
        List<OrderItem> orderItems = orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId());
        Map<UUID, String> thumbnails = resolveThumbnails(orderItems);
        List<OrderItemResponse> items = orderItems.stream()
                .map(item -> orderMapper.toItemResponse(item, thumbnails.get(item.getProduct().getId())))
                .toList();
        return toAdminResponse(order, items);
    }

    private List<AdminOrderResponse> assembleAdminResponses(List<Order> orders) {
        return assembleAdminResponses(orders, true);
    }

    private List<AdminOrderResponse> assembleAdminResponses(List<Order> orders, boolean includeThumbnails) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<OrderItemResponse>> itemsByOrder = itemsByOrderId(orders, includeThumbnails);
        return orders.stream()
                .map(o -> toAdminResponse(o, itemsByOrder.getOrDefault(o.getId(), List.of())))
                .toList();
    }

    private Map<UUID, List<OrderItemResponse>> itemsByOrderId(List<Order> orders) {
        return itemsByOrderId(orders, true);
    }

    private Map<UUID, List<OrderItemResponse>> itemsByOrderId(List<Order> orders, boolean includeThumbnails) {
        List<UUID> orderIds = orders.stream().map(Order::getId).toList();
        List<OrderItem> orderItems = orderItemRepository.findAllByOrderIdIn(orderIds);
        Map<UUID, String> thumbnails = includeThumbnails ? resolveThumbnails(orderItems) : Map.of();
        return orderItems.stream()
                .collect(Collectors.groupingBy(
                        i -> i.getOrder().getId(),
                        Collectors.mapping(
                                item -> orderMapper.toItemResponse(item, thumbnails.get(item.getProduct().getId())),
                                Collectors.toList())));
    }

    private Map<UUID, String> resolveThumbnails(List<OrderItem> items) {
        List<UUID> productIds = items.stream()
                .map(item -> item.getProduct().getId())
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productImageRepository.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(image -> image.getProduct().getId()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> ThumbnailResolver.resolve(entry.getValue())));
    }

    private OrderResponse toResponse(Order order, List<OrderItemResponse> items) {
        return new OrderResponse(
                order.getId(),
                order.getOrderCode(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                order.getPaymentMethod(),
                order.getSubtotalAmount(),
                order.getShippingFee(),
                order.getDiscountAmount(),
                order.getTotalAmount(),
                order.getNote(),
                order.getCancellationRequestedBy(),
                order.getCancellationReason(),
                order.getCancellationRequestedAt(),
                items,
                toShippingAddress(order),
                order.getInvoiceNumber(),
                order.isInvoiceRequested(),
                order.getInvoiceCompanyName(),
                order.getInvoiceTaxCode(),
                order.getInvoiceCompanyAddress(),
                order.getCreatedAt());
    }

    private AdminOrderResponse toAdminResponse(Order order, List<OrderItemResponse> items) {
        User user = order.getUser();
        return new AdminOrderResponse(
                order.getId(),
                order.getOrderCode(),
                user.getId(),
                user.getFullName(),
                user.getPhone(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                order.getPaymentMethod(),
                order.getSubtotalAmount(),
                order.getShippingFee(),
                order.getDiscountAmount(),
                order.getTotalAmount(),
                order.getNote(),
                order.getInternalNote(),
                order.getCancellationRequestedBy(),
                order.getCancellationReason(),
                order.getCancellationRequestedAt(),
                items,
                toShippingAddress(order),
                order.getCreatedAt());
    }

    /** Same address_snapshot_json keys ShipmentService.createShipmentFor reads - captured once at checkout. */
    private com.dunghaiquyen.ecommerce.modules.order.dto.ShippingAddressResponse toShippingAddress(Order order) {
        Map<String, Object> snapshot = order.getAddressSnapshotJson();
        if (snapshot == null) {
            return null;
        }
        return new com.dunghaiquyen.ecommerce.modules.order.dto.ShippingAddressResponse(
                snapshotString(snapshot, "receiverName"),
                snapshotString(snapshot, "phone"),
                snapshotString(snapshot, "province"),
                snapshotString(snapshot, "district"),
                snapshotString(snapshot, "ward"),
                snapshotString(snapshot, "addressLine"));
    }

    private String snapshotString(Map<String, Object> snapshot, String key) {
        Object value = snapshot.get(key);
        return value != null ? value.toString() : null;
    }
}
