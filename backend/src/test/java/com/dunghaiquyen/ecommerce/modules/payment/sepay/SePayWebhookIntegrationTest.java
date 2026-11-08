package com.dunghaiquyen.ecommerce.modules.payment.sepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatus;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.payment.entity.BankTransactionMatchStatus;
import com.dunghaiquyen.ecommerce.modules.payment.entity.BankTransactionSource;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.dunghaiquyen.ecommerce.modules.payment.repository.BankTransactionRepository;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentRepository;
import com.dunghaiquyen.ecommerce.modules.returns.entity.Refund;
import com.dunghaiquyen.ecommerce.modules.returns.entity.RefundProvider;
import com.dunghaiquyen.ecommerce.modules.returns.entity.RefundStatus;
import com.dunghaiquyen.ecommerce.modules.returns.repository.RefundRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties = {
        "app.vietqr.enabled=true",
        "app.vietqr.bank-bin=970422",
        "app.vietqr.account-number=1326045980",
        "app.vietqr.account-name=LE THANH H",
        "app.vietqr.payment-expiry-minutes=30",
        "app.sepay.webhook-enabled=true",
        "app.sepay.webhook-secret=integration-test-sepay-webhook-secret",
        "app.sepay.webhook-timestamp-tolerance-seconds=300"
})
class SePayWebhookIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "integration-test-sepay-webhook-secret";
    private static final AtomicLong EXTERNAL_IDS = new AtomicLong(900_000L);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void signedIncomingWebhookMatchesPaymentExactlyOnceUnderConcurrentDelivery() throws Exception {
        PaymentContext context = createPendingVietQrPayment("sepay-match");
        long externalId = EXTERNAL_IDS.incrementAndGet();
        byte[] payload = payload(externalId, context.transactionRef(), "in", "250000.00", "1326045980")
                .getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = sign(timestamp, payload);
        Callable<MvcResult> call = () -> sendWebhook(payload, timestamp, signature);

        List<MvcResult> results;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = pool.invokeAll(List.of(call, call));
            results = List.of(futures.get(0).get(), futures.get(1).get());
        }

        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
        assertThat(results.stream()
                .map(result -> {
                    try {
                        return json(result.getResponse().getContentAsString()).path("alreadyProcessed").asBoolean();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }).toList()).containsExactlyInAnyOrder(false, true);

        var bankTransaction = bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, Long.toString(externalId)).orElseThrow();
        assertThat(bankTransaction.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.MATCHED);
        assertThat(bankTransaction.getPayment().getId()).isEqualTo(context.paymentId());
        assertThat(bankTransaction.getRawPayloadRedacted()).doesNotContainKeys("id", "accountNumber", "transferAmount");

        var payment = paymentRepository.findById(context.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(orderRepository.findById(context.orderId()).orElseThrow())
                .satisfies(order -> {
                    assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
                    assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
                });
        assertThat(paymentEventCount(context.paymentId())).isEqualTo(1);
        assertThat(notificationCount(context.orderId(), "PAYMENT_COMPLETED")).isEqualTo(1);
    }

    @Test
    void invalidSignatureDoesNotInsertLedgerRow() throws Exception {
        long externalId = EXTERNAL_IDS.incrementAndGet();
        byte[] payload = payload(externalId, "DHABCDEFG234", "in", "250000.00", "1326045980")
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/webhooks/sepay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SePay-Timestamp", Long.toString(Instant.now().getEpochSecond()))
                        .header("X-SePay-Signature", "sha256=" + "0".repeat(64))
                        .content(payload))
                .andExpect(status().isUnauthorized());

        assertThat(bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, Long.toString(externalId))).isEmpty();
    }

    @Test
    void signedDashboardTestPayloadReturnsSuccessWithoutInsertingLedgerRow() throws Exception {
        MvcResult result = sendSigned(payload(0L, "DHABCDEFG234", "in", "10000.00", "1326045980"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(json(result.getResponse().getContentAsString()).path("success").asBoolean()).isTrue();
        assertThat(json(result.getResponse().getContentAsString()).has("alreadyProcessed")).isFalse();
        assertThat(bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, "0")).isEmpty();
    }

    @Test
    void amountMismatchIsRecordedWithoutPayingOrder() throws Exception {
        PaymentContext context = createPendingVietQrPayment("sepay-amount");
        long externalId = EXTERNAL_IDS.incrementAndGet();
        sendSigned(payload(externalId, context.transactionRef(), "in", "249999.00", "1326045980"))
                .andExpect(status().isCreated());

        var bankTransaction = bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, Long.toString(externalId)).orElseThrow();
        assertThat(bankTransaction.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.AMOUNT_MISMATCH);
        assertThat(bankTransaction.getPayment().getId()).isEqualTo(context.paymentId());
        assertThat(paymentRepository.findById(context.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(orderRepository.findById(context.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentEventCount(context.paymentId())).isZero();
    }

    @Test
    void outgoingTransactionIsStoredAsNegativeAndIgnored() throws Exception {
        long externalId = EXTERNAL_IDS.incrementAndGet();
        sendSigned(payload(externalId, "DHABCDEFG234", "out", "10000.00", "1326045980"))
                .andExpect(status().isCreated());

        var bankTransaction = bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, Long.toString(externalId)).orElseThrow();
        assertThat(bankTransaction.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.IGNORED_OUTGOING);
        assertThat(bankTransaction.getAmount()).isEqualByComparingTo("-10000.00");
        assertThat(bankTransaction.getPayment()).isNull();
    }

    @Test
    void signedOutgoingWebhookCompletesMatchingVietQrRefundExactlyOnce() throws Exception {
        PaymentContext context = createPendingVietQrPayment("sepay-refund");
        var payment = paymentRepository.findById(context.paymentId()).orElseThrow();
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(Instant.now());
        paymentRepository.saveAndFlush(payment);

        var order = orderRepository.findById(context.orderId()).orElseThrow();
        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setPaymentStatus(PaymentStatus.PAID);
        orderRepository.saveAndFlush(order);

        Refund refund = new Refund();
        refund.setOrder(order);
        refund.setPayment(payment);
        refund.setRefundCode("RFD2026110800001");
        refund.setAmount(payment.getAmount());
        refund.setProvider(RefundProvider.MANUAL);
        refund.setStatus(RefundStatus.PENDING);
        refund.setReason("Test outgoing confirmation");
        refund.setRefundBankCode("MB");
        refund.setRefundBankName("MB Bank");
        refund.setRefundAccountNumber("0123456789");
        refund.setRefundAccountName("TEST CUSTOMER");
        refund = refundRepository.saveAndFlush(refund);

        long externalId = EXTERNAL_IDS.incrementAndGet();
        String body = payload(externalId, refund.getRefundCode(), "out", "250000.00", "1326045980");
        sendSigned(body).andExpect(status().isCreated());
        MvcResult replay = sendSigned(body).andExpect(status().isCreated()).andReturn();

        assertThat(json(replay.getResponse().getContentAsString()).path("alreadyProcessed").asBoolean()).isTrue();
        var bankTransaction = bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, Long.toString(externalId)).orElseThrow();
        assertThat(bankTransaction.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.MATCHED);
        assertThat(bankTransaction.getMatchReason())
                .isEqualTo("EXACT_REFUND_REFERENCE_ACCOUNT_AMOUNT_MATCH");
        assertThat(bankTransaction.getAmount()).isEqualByComparingTo("-250000.00");
        assertThat(bankTransaction.getPayment().getId()).isEqualTo(payment.getId());
        assertThat(bankTransaction.getRefund().getId()).isEqualTo(refund.getId());

        assertThat(refundRepository.findById(refund.getId()).orElseThrow()).satisfies(completed -> {
            assertThat(completed.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(completed.getManualReference()).isEqualTo("FT" + externalId);
            assertThat(completed.getRefundedAt()).isNotNull();
        });
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paymentEventCount(payment.getId())).isEqualTo(1);
        assertThat(notificationCount(order.getId(), "REFUND_COMPLETED")).isEqualTo(1);
    }

    @Test
    void outgoingRefundAmountMismatchDoesNotCompleteRefund() throws Exception {
        PaymentContext context = createPendingVietQrPayment("sepay-refund-mismatch");
        var payment = paymentRepository.findById(context.paymentId()).orElseThrow();
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(Instant.now());
        paymentRepository.saveAndFlush(payment);
        var order = orderRepository.findById(context.orderId()).orElseThrow();
        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setPaymentStatus(PaymentStatus.PAID);
        orderRepository.saveAndFlush(order);

        Refund refund = new Refund();
        refund.setOrder(order);
        refund.setPayment(payment);
        refund.setRefundCode("RFD2026110800002");
        refund.setAmount(payment.getAmount());
        refund.setProvider(RefundProvider.MANUAL);
        refund.setStatus(RefundStatus.PENDING);
        refund.setReason("Test amount mismatch");
        refund.setRefundBankCode("MB");
        refund.setRefundBankName("MB Bank");
        refund.setRefundAccountNumber("0123456789");
        refund.setRefundAccountName("TEST CUSTOMER");
        refund = refundRepository.saveAndFlush(refund);

        long externalId = EXTERNAL_IDS.incrementAndGet();
        sendSigned(payload(externalId, refund.getRefundCode(), "out", "249999.00", "1326045980"))
                .andExpect(status().isCreated());

        var bankTransaction = bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, Long.toString(externalId)).orElseThrow();
        assertThat(bankTransaction.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.AMOUNT_MISMATCH);
        assertThat(bankTransaction.getRefund().getId()).isEqualTo(refund.getId());
        assertThat(refundRepository.findById(refund.getId()).orElseThrow().getStatus())
                .isEqualTo(RefundStatus.PENDING);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void transactionWithoutPaymentReferenceIsStoredAsUnmatched() throws Exception {
        long externalId = EXTERNAL_IDS.incrementAndGet();
        sendSigned(payload(externalId, "NO-REFERENCE", "in", "10000.00", "1326045980"))
                .andExpect(status().isCreated());

        var bankTransaction = bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, Long.toString(externalId)).orElseThrow();
        assertThat(bankTransaction.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.UNMATCHED);
        assertThat(bankTransaction.getPayment()).isNull();
    }

    @Test
    void creditForCancelledPaymentIsStoredAsLateWithoutRevivingOrder() throws Exception {
        PaymentContext context = createPendingVietQrPayment("sepay-late");
        var payment = paymentRepository.findById(context.paymentId()).orElseThrow();
        payment.setStatus(PaymentStatus.CANCELLED);
        paymentRepository.saveAndFlush(payment);
        var order = orderRepository.findById(context.orderId()).orElseThrow();
        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setPaymentStatus(PaymentStatus.CANCELLED);
        orderRepository.saveAndFlush(order);

        long externalId = EXTERNAL_IDS.incrementAndGet();
        sendSigned(payload(externalId, context.transactionRef(), "in", "250000.00", "1326045980"))
                .andExpect(status().isCreated());

        var bankTransaction = bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, Long.toString(externalId)).orElseThrow();
        assertThat(bankTransaction.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.LATE_PAYMENT);
        assertThat(bankTransaction.getPayment().getId()).isEqualTo(context.paymentId());
        assertThat(paymentRepository.findById(context.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
        assertThat(orderRepository.findById(context.orderId()).orElseThrow())
                .satisfies(cancelled -> {
                    assertThat(cancelled.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
                    assertThat(cancelled.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
                });
    }

    @Test
    void conflictingReferencesAreStoredAsAmbiguous() throws Exception {
        PaymentContext context = createPendingVietQrPayment("sepay-ambiguous");
        long externalId = EXTERNAL_IDS.incrementAndGet();
        String otherReference = "DHZZZZZZZZ99";
        String body = payload(externalId, context.transactionRef(), "in", "250000.00", "1326045980")
                .replace("chuyen tien", otherReference + " chuyen tien");
        sendSigned(body).andExpect(status().isCreated());

        var bankTransaction = bankTransactionRepository.findBySourceAndExternalTransactionId(
                BankTransactionSource.SEPAY, Long.toString(externalId)).orElseThrow();
        assertThat(bankTransaction.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.AMBIGUOUS);
        assertThat(paymentRepository.findById(context.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    private PaymentContext createPendingVietQrPayment(String prefix) throws Exception {
        String email = uniqueEmail(prefix);
        TokenPair owner = registerUser(email);
        Order order = saveOrder(email);
        MvcResult result = mockMvc.perform(post("/api/v1/payments/vietqr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var data = json(result.getResponse().getContentAsString()).path("data");
        return new PaymentContext(
                order.getId(),
                UUID.fromString(data.path("paymentId").asText()),
                data.path("transactionRef").asText());
    }

    private Order saveOrder(String email) {
        Order order = new Order();
        order.setOrderCode("SPW-" + UUID.randomUUID());
        order.setUser(userRepository.findByEmail(email).orElseThrow());
        order.setAddressSnapshotJson(Map.of(
                "receiverName", "SePay Test",
                "phone", "0900000000",
                "addressLine", "123 Test Street"));
        order.setSubtotalAmount(new BigDecimal("250000.00"));
        order.setShippingFee(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("250000.00"));
        order.setCurrencyCode("VND");
        order.setPaymentMethod(PaymentMethod.VIETQR);
        order.setPaymentStatus(PaymentStatus.UNPAID);
        return orderRepository.saveAndFlush(order);
    }

    private org.springframework.test.web.servlet.ResultActions sendSigned(String body) throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        return mockMvc.perform(post("/api/v1/webhooks/sepay")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-SePay-Timestamp", timestamp)
                .header("X-SePay-Signature", sign(timestamp, payload))
                .content(payload));
    }

    private MvcResult sendWebhook(byte[] payload, String timestamp, String signature) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/sepay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SePay-Timestamp", timestamp)
                        .header("X-SePay-Signature", signature)
                        .content(payload))
                .andReturn();
    }

    private String payload(long id, String code, String type, String amount, String accountNumber) {
        String transactionDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return """
                {"id":%d,"gateway":"MBBank","transactionDate":"%s","accountNumber":"%s",\
                "subAccount":"","code":"%s","content":"%s chuyen tien",\
                "transferType":"%s","description":"SePay integration test",\
                "transferAmount":%s,"accumulated":1000000,"referenceCode":"FT%s"}
                """.formatted(id, transactionDate, accountNumber, code, code, type, amount, id);
    }

    private String sign(String timestamp, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }

    private int paymentEventCount(UUID paymentId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from payment_events where payment_id = ? and provider = 'BANK_TRANSFER'",
                Integer.class,
                paymentId);
        return count == null ? 0 : count;
    }

    private int notificationCount(UUID orderId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from notifications where order_id = ? and type = ?",
                Integer.class,
                orderId,
                type);
        return count == null ? 0 : count;
    }

    private record PaymentContext(UUID orderId, UUID paymentId, String transactionRef) {
    }
}
