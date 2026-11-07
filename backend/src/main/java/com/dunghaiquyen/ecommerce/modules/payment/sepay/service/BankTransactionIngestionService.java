package com.dunghaiquyen.ecommerce.modules.payment.sepay.service;

import com.dunghaiquyen.ecommerce.common.time.AppTimeZone;
import com.dunghaiquyen.ecommerce.config.AppVietQrProperties;
import com.dunghaiquyen.ecommerce.config.CacheConfig;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatus;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.payment.entity.BankTransaction;
import com.dunghaiquyen.ecommerce.modules.payment.entity.BankTransactionMatchStatus;
import com.dunghaiquyen.ecommerce.modules.payment.entity.BankTransactionSource;
import com.dunghaiquyen.ecommerce.modules.payment.entity.Payment;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentEvent;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentProvider;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.dunghaiquyen.ecommerce.modules.payment.repository.BankTransactionRepository;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentEventRepository;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentRepository;
import com.dunghaiquyen.ecommerce.modules.payment.sepay.dto.SePayWebhookRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankTransactionIngestionService {

    private static final DateTimeFormatter SEPAY_TRANSACTION_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern PAYMENT_REFERENCE =
            Pattern.compile("(?<![A-Z0-9])DH[A-Z0-9]{10}(?![A-Z0-9])");

    private final BankTransactionRepository bankTransactionRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderRepository orderRepository;
    private final AppVietQrProperties vietQrProperties;
    private final JdbcTemplate jdbcTemplate;

    public BankTransactionIngestionService(
            BankTransactionRepository bankTransactionRepository,
            PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository,
            OrderRepository orderRepository,
            AppVietQrProperties vietQrProperties,
            JdbcTemplate jdbcTemplate) {
        this.bankTransactionRepository = bankTransactionRepository;
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.orderRepository = orderRepository;
        this.vietQrProperties = vietQrProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true)
    public IngestionResult ingestWebhook(SePayWebhookRequest request) {
        requireMatchingAccountConfiguration();
        String externalId = request.id().toString();
        acquireTransactionLock("SEPAY:WEBHOOK:" + externalId);

        Optional<BankTransaction> duplicate = bankTransactionRepository
                .findBySourceAndExternalTransactionId(BankTransactionSource.SEPAY, externalId);
        if (duplicate.isPresent()) {
            return new IngestionResult(
                    duplicate.get().getId(), duplicate.get().getMatchStatus(), true);
        }

        BankTransaction transaction = new BankTransaction();
        transaction.setSource(BankTransactionSource.SEPAY);
        transaction.setExternalTransactionId(externalId);
        transaction.setBankReference(trimToNull(request.referenceCode()));
        transaction.setAccountNumber(request.accountNumber().trim());
        transaction.setAmount(normalizedAmount(request));
        transaction.setDescription(firstNonBlank(request.content(), request.description()));
        transaction.setTransactionTime(parseTransactionTime(request.transactionDate()));
        transaction.setRunningBalance(request.accumulated());
        transaction.setRawPayloadRedacted(redactedPayload(request));
        transaction = bankTransactionRepository.saveAndFlush(transaction);

        match(transaction, request);
        bankTransactionRepository.save(transaction);
        return new IngestionResult(transaction.getId(), transaction.getMatchStatus(), false);
    }

    private void match(BankTransaction transaction, SePayWebhookRequest request) {
        if (!"in".equals(request.transferType()) || transaction.getAmount().signum() <= 0) {
            reject(transaction, BankTransactionMatchStatus.IGNORED_OUTGOING, "OUTGOING_TRANSACTION");
            return;
        }
        if (!vietQrProperties.accountNumber().equals(transaction.getAccountNumber())) {
            reject(transaction, BankTransactionMatchStatus.MANUAL_REVIEW, "ACCOUNT_MISMATCH");
            return;
        }

        ReferenceResult reference = extractReference(request);
        if (reference.ambiguous()) {
            reject(transaction, BankTransactionMatchStatus.AMBIGUOUS, "MULTIPLE_PAYMENT_REFERENCES");
            return;
        }
        if (reference.value() == null) {
            reject(transaction, BankTransactionMatchStatus.UNMATCHED, "PAYMENT_REFERENCE_NOT_FOUND");
            return;
        }

        Optional<Payment> candidate = paymentRepository.findByProviderAndTransactionRef(
                PaymentProvider.BANK_TRANSFER, reference.value());
        if (candidate.isEmpty()) {
            reject(transaction, BankTransactionMatchStatus.UNMATCHED, "PAYMENT_NOT_FOUND");
            return;
        }

        Order order = orderRepository.findByIdForUpdate(candidate.get().getOrder().getId()).orElse(null);
        if (order == null) {
            reject(transaction, BankTransactionMatchStatus.MANUAL_REVIEW, "ORDER_NOT_FOUND");
            return;
        }
        Payment payment = paymentRepository.findByProviderAndTransactionRefForUpdate(
                PaymentProvider.BANK_TRANSFER, reference.value()).orElse(null);
        if (payment == null || !payment.getOrder().getId().equals(order.getId())) {
            reject(transaction, BankTransactionMatchStatus.MANUAL_REVIEW, "PAYMENT_CHANGED_DURING_MATCH");
            return;
        }
        transaction.setPayment(payment);

        if (payment.getAmount().compareTo(transaction.getAmount()) != 0) {
            reject(transaction, BankTransactionMatchStatus.AMOUNT_MISMATCH, "AMOUNT_MISMATCH");
            return;
        }
        if (order.getOrderStatus() == OrderStatus.CANCELLED
                || payment.getStatus() == PaymentStatus.CANCELLED
                || payment.getExpiresAt() == null
                || !payment.getExpiresAt().isAfter(Instant.now())) {
            reject(transaction, BankTransactionMatchStatus.LATE_PAYMENT, "PAYMENT_EXPIRED_OR_CANCELLED");
            return;
        }
        if (order.getPaymentStatus() == PaymentStatus.PAID || payment.getStatus() == PaymentStatus.PAID) {
            reject(transaction, BankTransactionMatchStatus.MANUAL_REVIEW, "DUPLICATE_COLLECTION");
            return;
        }
        if (order.getPaymentMethod() != PaymentMethod.VIETQR
                || payment.getStatus() != PaymentStatus.PENDING
                || !"VND".equals(order.getCurrencyCode())) {
            reject(transaction, BankTransactionMatchStatus.MANUAL_REVIEW, "PAYMENT_NOT_MATCHABLE");
            return;
        }

        Instant matchedAt = Instant.now();
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(matchedAt);
        payment.setGatewayTransactionNo(truncate(request.referenceCode(), 100));
        payment.setBankCode(truncate(request.gateway(), 30));
        paymentRepository.save(payment);

        order.setPaymentStatus(PaymentStatus.PAID);
        orderRepository.save(order);

        transaction.setMatchStatus(BankTransactionMatchStatus.MATCHED);
        transaction.setMatchReason("EXACT_REFERENCE_ACCOUNT_AMOUNT_MATCH");
        transaction.setReconciledAt(matchedAt);
        savePaymentEvent(payment, request, transaction.getRawPayloadRedacted());
    }

    private void savePaymentEvent(
            Payment payment, SePayWebhookRequest request, Map<String, Object> redactedPayload) {
        PaymentEvent event = new PaymentEvent();
        event.setPayment(payment);
        event.setProvider(PaymentProvider.BANK_TRANSFER);
        event.setProviderEventKey(sha256("SEPAY|" + request.id()));
        event.setEventType("BANK_CREDIT_MATCHED");
        event.setVerified(true);
        event.setPayloadRedacted(redactedPayload);
        paymentEventRepository.save(event);
    }

    private ReferenceResult extractReference(SePayWebhookRequest request) {
        Set<String> codeReferences = referencesIn(request.code());
        Set<String> textReferences = new LinkedHashSet<>();
        textReferences.addAll(referencesIn(request.content()));
        textReferences.addAll(referencesIn(request.description()));

        Set<String> all = new LinkedHashSet<>(codeReferences);
        all.addAll(textReferences);
        if (all.size() > 1) {
            return new ReferenceResult(null, true);
        }
        if (!codeReferences.isEmpty()) {
            return new ReferenceResult(codeReferences.iterator().next(), false);
        }
        return all.isEmpty()
                ? new ReferenceResult(null, false)
                : new ReferenceResult(all.iterator().next(), false);
    }

    private Set<String> referencesIn(String value) {
        Set<String> references = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return references;
        Matcher matcher = PAYMENT_REFERENCE.matcher(value.toUpperCase(Locale.ROOT));
        while (matcher.find()) references.add(matcher.group());
        return references;
    }

    private Instant parseTransactionTime(String value) {
        try {
            return LocalDateTime.parse(value, SEPAY_TRANSACTION_TIME)
                    .atZone(AppTimeZone.ZONE)
                    .toInstant();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid SePay transactionDate", exception);
        }
    }

    private java.math.BigDecimal normalizedAmount(SePayWebhookRequest request) {
        java.math.BigDecimal absolute = request.transferAmount().abs();
        return "in".equals(request.transferType()) ? absolute : absolute.negate();
    }

    private Map<String, Object> redactedPayload(SePayWebhookRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotNull(payload, "gateway", request.gateway());
        putIfNotNull(payload, "subAccount", request.subAccount());
        putIfNotNull(payload, "code", request.code());
        putIfNotNull(payload, "content", request.content());
        putIfNotNull(payload, "transferType", request.transferType());
        putIfNotNull(payload, "referenceCode", request.referenceCode());
        return payload;
    }

    private void acquireTransactionLock(String key) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, key);
                statement.execute();
            }
            return null;
        });
    }

    private void requireMatchingAccountConfiguration() {
        if (vietQrProperties.accountNumber() == null || vietQrProperties.accountNumber().isBlank()) {
            throw new IllegalStateException("VietQR account number is required for SePay webhook matching");
        }
    }

    private void reject(
            BankTransaction transaction,
            BankTransactionMatchStatus status,
            String reason) {
        transaction.setMatchStatus(status);
        transaction.setMatchReason(reason);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : trimToNull(second);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        String trimmed = trimToNull(value);
        return trimmed == null || trimmed.length() <= maxLength
                ? trimmed : trimmed.substring(0, maxLength);
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    public record IngestionResult(
            java.util.UUID bankTransactionId,
            BankTransactionMatchStatus matchStatus,
            boolean alreadyProcessed) {
    }

    private record ReferenceResult(String value, boolean ambiguous) {
    }
}
