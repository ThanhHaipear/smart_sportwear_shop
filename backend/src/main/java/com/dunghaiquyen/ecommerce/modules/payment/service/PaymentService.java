package com.dunghaiquyen.ecommerce.modules.payment.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.config.AppOrderProperties;
import com.dunghaiquyen.ecommerce.config.AppVnpayProperties;
import com.dunghaiquyen.ecommerce.config.CacheConfig;
import com.dunghaiquyen.ecommerce.common.time.AppTimeZone;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatus;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreatePaymentRequest;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreatePaymentResponse;
import com.dunghaiquyen.ecommerce.modules.payment.dto.PaymentResponse;
import com.dunghaiquyen.ecommerce.modules.payment.entity.Payment;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentEvent;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentProvider;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentRepository;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentEventRepository;
import com.dunghaiquyen.ecommerce.modules.returns.service.ReturnService;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VNPay 2.1.0 payment session and IPN handling. The configured payment URL may
 * point to sandbox or production; protocol validation is identical in both
 * environments. This service never creates orders or reserves stock.
 */
@Service
public class PaymentService {

    private static final String VNP_SUCCESS_CODE = "00";
    private static final String VNP_USER_CANCELLED_CODE = "24";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final VnpaySignatureService signatureService;
    private final AppVnpayProperties vnpayProperties;
    private final AppOrderProperties orderProperties;
    private final ReturnService returnService;

    public PaymentService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository,
            VnpaySignatureService signatureService,
            AppVnpayProperties vnpayProperties,
            AppOrderProperties orderProperties,
            ReturnService returnService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.signatureService = signatureService;
        this.vnpayProperties = vnpayProperties;
        this.orderProperties = orderProperties;
        this.returnService = returnService;
    }

    /**
     * Order eligibility + the "call this twice for the same order" rule:
     * - order not found / not owned by caller -> 404.
     * - order.paymentMethod != VNPAY -> 422 (COD orders have no online session,
     *   per PHASE1_SPEC.md 6.7 - "order COD thì... không có online payment session").
     * - order.orderStatus == CANCELLED -> 422 (nothing to pay for any more).
     * - order.paymentStatus == PAID -> 422 (already settled, no second session).
     * - if the order's MOST RECENT payment attempt is still PENDING: re-issue
     *   that SAME session (same transactionRef/URL) instead of creating a new
     *   row. Chosen over "always insert a new row" because a customer calling
     *   create twice in a row (double click, page refresh) is almost certainly
     *   trying to resume the one in-flight attempt, not start a second
     *   competing one - having two simultaneously-PENDING sessions for the same
     *   order would make "which one does the next callback belong to" murky.
     *   A FAILED/CANCELLED latest attempt still allows a fresh row (ERD_PHASE1.md
     *   9.14: "một order có thể có nhiều bản ghi payment theo lịch sử retry").
     *
     * Concurrency: the order row is locked (findByIdForUpdate) before the
     * "is there a PENDING attempt already" read - without this, two concurrent
     * calls for the same order could both read "no PENDING attempt yet" and
     * both insert a competing PENDING row. The lock serializes the whole
     * read-then-decide-then-write sequence per order: the second caller blocks
     * until the first commits, then re-reads and finds the first one's PENDING
     * row, landing in the reuse branch instead of inserting a duplicate.
     */
    @Transactional
    public CreatePaymentResponse createPaymentSession(UUID userId, CreatePaymentRequest request) {
        return createPaymentSession(userId, request, "127.0.0.1");
    }

    @Transactional
    public CreatePaymentResponse createPaymentSession(
            UUID userId, CreatePaymentRequest request, String clientIpAddress) {
        Order order = orderRepository.findByIdForUpdate(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        if (order.getPaymentMethod() != PaymentMethod.VNPAY) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Order is not eligible for online payment");
        }
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Order is cancelled");
        }
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Order is already paid");
        }

        Instant now = Instant.now();
        Instant orderPaymentDeadline = order.getCreatedAt()
                .plusSeconds(orderProperties.effectivePendingPaymentExpiryMinutes() * 60L);
        if (!orderPaymentDeadline.isAfter(now)) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Order payment window has expired");
        }

        Optional<Payment> latest = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(order.getId());
        if (latest.isPresent() && latest.get().getStatus() == PaymentStatus.PENDING) {
            Payment existing = latest.get();
            if (existing.getExpiresAt() != null && existing.getExpiresAt().isAfter(now)) {
                return new CreatePaymentResponse(
                        buildPaymentUrl(existing, clientIpAddress), existing.getTransactionRef());
            }
            existing.setStatus(PaymentStatus.CANCELLED);
            paymentRepository.save(existing);
        }

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setProvider(PaymentProvider.VNPAY);
        payment.setTransactionRef(generateTransactionRef());
        payment.setAmount(order.getTotalAmount());
        payment.setStatus(PaymentStatus.PENDING);
        ZonedDateTime paymentCreatedAt = ZonedDateTime.now(AppTimeZone.ZONE);
        payment.setTransactionDate(paymentCreatedAt.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        // A retry created near the order's hard expiry must never produce a URL
        // that remains payable after the order is eligible for auto-cancellation.
        Instant normalSessionDeadline = paymentCreatedAt.plusMinutes(15).toInstant();
        payment.setExpiresAt(normalSessionDeadline.isBefore(orderPaymentDeadline)
                ? normalSessionDeadline : orderPaymentDeadline);
        payment = paymentRepository.save(payment);

        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            order.setPaymentStatus(PaymentStatus.PENDING);
            orderRepository.save(order);
        }

        return new CreatePaymentResponse(buildPaymentUrl(payment, clientIpAddress), payment.getTransactionRef());
    }

    /**
     * Idempotency contract (PHASE1_SPEC.md 6.7 + this phase's explicit ask):
     * - invalid/missing checksum -> reject, no row touched at all.
     * - unknown transactionRef -> 404, no row touched.
     * - payment already in a TERMINAL state (PAID/FAILED/CANCELLED/REFUNDED) ->
     *   no-op, return the same "processed" success response. This is the crux of
     *   "a payment success must not apply twice": the lock (findByTransactionRefForUpdate)
     *   plus this status check together mean two concurrent deliveries for the
     *   same transactionRef can never both observe PENDING and both write.
     * - payment still PENDING -> apply exactly once, mapping vnp_ResponseCode to
     *   the matching terminal PaymentStatus. OrderStatus itself is never
     *   touched here - a failed/cancelled payment does not auto-cancel the
     *   order, it just leaves it payable again (the customer can call
     *   create-payment-session again, landing in the "latest attempt is
     *   FAILED/CANCELLED -> new row" branch above).
     *
     * Order-already-CANCELLED rule (deliberately chosen over silently mirroring
     * onto Order.paymentStatus): if the order was cancelled while this payment
     * was in flight, Payment.status is still resolved and saved - that is what
     * the gateway actually reported, and dropping it would lose the one piece
     * of data a future refund/reconciliation flow would need - but
     * Order.paymentStatus is deliberately left UNTOUCHED. Phase H has no
     * refund flow, so there is no safe way to represent or unwind "PAID" on a
     * cancelled order through Order.paymentStatus alone; leaving it as it was
     * (whatever it was before this callback) avoids writing a state combination
     * the rest of the system has no defined handling for. This is a single
     * unconditional rule applied to every response code, not a special case
     * per outcome, so there is nothing implicit to keep in sync.
     */
    @Transactional
    @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true)
    public Map<String, String> handleCallback(Map<String, String> params) {
        if (!signatureService.verify(params)) {
            return ipnResponse("97", "Invalid signature");
        }
        String transactionRef = params.get("vnp_TxnRef");
        if (transactionRef == null || transactionRef.isBlank()) {
            return ipnResponse("99", "Missing transaction reference");
        }

        // Resolve the immutable owning order first without taking a payment lock,
        // then acquire locks in the same order used by createPaymentSession:
        // order -> payment. The previous payment -> order order could deadlock
        // with a concurrent retry that held the order and updated that payment.
        Optional<Payment> paymentLookup = paymentRepository.findByTransactionRef(transactionRef);
        if (paymentLookup.isEmpty()) {
            return ipnResponse("01", "Payment not found");
        }
        Order order = orderRepository.findByIdForUpdate(paymentLookup.get().getOrder().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        Payment payment = paymentRepository.findByTransactionRefForUpdate(transactionRef)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        String validationCode = validateCallbackPayload(params, payment);
        if (validationCode != null) {
            return switch (validationCode) {
                case "04" -> ipnResponse("04", "Invalid amount");
                default -> ipnResponse("99", "Invalid payment data");
            };
        }

        String providerEventKey = providerEventKey(params);
        if (paymentEventRepository.existsByProviderAndProviderEventKey(PaymentProvider.VNPAY, providerEventKey)) {
            return ipnResponse("02", "Payment event already processed");
        }
        Map<String, Object> redactedPayload = redactedCallbackPayload(params);
        PaymentEvent event = new PaymentEvent();
        event.setPayment(payment);
        event.setProvider(PaymentProvider.VNPAY);
        event.setProviderEventKey(providerEventKey);
        event.setEventType("IPN_CALLBACK");
        event.setVerified(true);
        event.setPayloadRedacted(redactedPayload);
        paymentEventRepository.save(event);

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return ipnResponse("02", "Payment already confirmed");
        }

        String responseCode = params.get("vnp_ResponseCode");
        String transactionStatus = params.get("vnp_TransactionStatus");
        PaymentStatus resolvedStatus = resolveStatus(responseCode, transactionStatus);
        payment.setStatus(resolvedStatus);
        if (resolvedStatus == PaymentStatus.PAID) {
            payment.setPaidAt(Instant.now());
        }
        payment.setRawPayloadJson(redactedPayload);
        payment.setGatewayTransactionNo(params.get("vnp_TransactionNo"));
        payment.setBankCode(params.get("vnp_BankCode"));
        paymentRepository.save(payment);

        order.setPaymentStatus(resolvedStatus);
        if (order.getOrderStatus() == OrderStatus.CANCELLED && resolvedStatus == PaymentStatus.PAID) {
            order.setInternalNote("VNPay paid after order cancellation - refund required. TxnRef: "
                    + payment.getTransactionRef());
        }
        orderRepository.save(order);
        if (order.getOrderStatus() == OrderStatus.CANCELLED && resolvedStatus == PaymentStatus.PAID) {
            returnService.ensurePendingCancellationRefund(
                    order.getId(), "VNPay paid after order cancellation", null);
        }
        return ipnResponse("00", "Confirm Success");
    }

    private String validateCallbackPayload(Map<String, String> params, Payment payment) {
        if (!vnpayProperties.tmnCode().equals(params.get("vnp_TmnCode"))) {
            return "99";
        }
        String amountValue = params.get("vnp_Amount");
        try {
            BigDecimal callbackAmount = new BigDecimal(amountValue).movePointLeft(2);
            if (callbackAmount.compareTo(payment.getAmount()) != 0) {
                return "04";
            }
        } catch (NumberFormatException | NullPointerException ex) {
            return "04";
        }
        if (params.get("vnp_ResponseCode") == null || params.get("vnp_TransactionStatus") == null) {
            return "99";
        }
        return null;
    }

    private Map<String, String> ipnResponse(String code, String message) {
        return Map.of("RspCode", code, "Message", message);
    }

    private String providerEventKey(Map<String, String> params) {
        String canonical = String.join("|",
                value(params, "vnp_TmnCode"),
                value(params, "vnp_TxnRef"),
                value(params, "vnp_TransactionNo"),
                value(params, "vnp_ResponseCode"),
                value(params, "vnp_TransactionStatus"),
                value(params, "vnp_Amount"),
                value(params, "vnp_PayDate"));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private Map<String, Object> redactedCallbackPayload(Map<String, String> params) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (String key : List.of(
                "vnp_TmnCode", "vnp_TxnRef", "vnp_TransactionNo", "vnp_ResponseCode",
                "vnp_TransactionStatus", "vnp_Amount", "vnp_PayDate", "vnp_BankCode")) {
            if (params.get(key) != null) {
                redacted.put(key, params.get(key));
            }
        }
        return redacted;
    }

    private String value(Map<String, String> params, String key) {
        String value = params.get(key);
        return value == null || value.isBlank() ? "-" : value;
    }

    private PaymentStatus resolveStatus(String responseCode, String transactionStatus) {
        if (VNP_SUCCESS_CODE.equals(responseCode) && VNP_SUCCESS_CODE.equals(transactionStatus)) {
            return PaymentStatus.PAID;
        }
        if (VNP_USER_CANCELLED_CODE.equals(responseCode)) {
            return PaymentStatus.CANCELLED;
        }
        return PaymentStatus.FAILED;
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsByOrder(UUID orderId, UUID callerId, UserRole callerRole) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (callerRole == UserRole.CUSTOMER && !order.getUser().getId().equals(callerId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        return paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(this::toResponse)
                .toList();
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getProvider(),
                payment.getTransactionRef(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getGatewayTransactionNo(),
                payment.getBankCode(),
                payment.getPaidAt(),
                payment.getCreatedAt());
    }

    private String generateTransactionRef() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String uniquePart = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        return "TXN" + datePart + uniquePart;
    }

    private String buildPaymentUrl(Payment payment, String clientIpAddress) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", vnpayProperties.tmnCode());
        params.put(
                "vnp_Amount",
                payment.getAmount().multiply(BigDecimal.valueOf(100)).toBigInteger().toString());
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", payment.getTransactionRef());
        params.put("vnp_OrderInfo", "Payment for order " + payment.getOrder().getOrderCode());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", vnpayProperties.returnUrl());
        params.put(
                "vnp_IpAddr",
                clientIpAddress == null || clientIpAddress.isBlank() ? "127.0.0.1" : clientIpAddress);
        params.put("vnp_CreateDate", payment.getTransactionDate());
        params.put("vnp_ExpireDate", ZonedDateTime.ofInstant(payment.getExpiresAt(), AppTimeZone.ZONE)
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));

        String hash = signatureService.hash(params);
        String query = signatureService.buildQueryString(params);
        return vnpayProperties.payUrl() + "?" + query + "&vnp_SecureHash=" + hash;
    }
}
