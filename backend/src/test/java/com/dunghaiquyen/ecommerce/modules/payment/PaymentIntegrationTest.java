package com.dunghaiquyen.ecommerce.modules.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentRepository;
import com.dunghaiquyen.ecommerce.modules.payment.service.VnpaySignatureService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private VnpaySignatureService signatureService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("pay-admin"));
        String categorySlug = "pay-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "pay-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    private String createActiveProduct(AdminContext ctx, String name) throws Exception {
        String slug = "pay-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\",\"status\":\"DRAFT\"}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String createVariant(AdminContext ctx, String productId, int price, int stockQuantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"SKU-%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":%d,\"stockQuantity\":%d}")
                                .formatted(UUID.randomUUID(), price, stockQuantity)))
                .andExpect(status().isCreated())
                .andReturn();
        String variantId = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        mockMvc.perform(patch("/api/v1/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return variantId;
    }

    private void addToCart(String token, String variantId, int quantity) throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated());
    }

    private String createAddressForUser(String email) {
        var user = userRepository.findByEmail(email).orElseThrow();
        Address address = new Address();
        address.setUser(user);
        address.setReceiverName("Test Receiver");
        address.setPhone("0900000000");
        address.setProvince("HCM");
        address.setDistrict("District 1");
        address.setWard("Ward 1");
        address.setAddressLine("123 Test Street");
        return addressRepository.save(address).getId().toString();
    }

    /** Creates an order paid via the given method and returns its id. */
    private String createOrder(String token, String addressId, String paymentMethod) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"" + paymentMethod + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private MvcResult createPaymentSession(String token, String orderId) throws Exception {
        return mockMvc.perform(post("/api/v1/payments/create")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andReturn();
    }

    private Map<String, String> buildSignedCallbackParams(String transactionRef, String responseCode) {
        var payment = paymentRepository.findByTransactionRef(transactionRef).orElseThrow();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TxnRef", transactionRef);
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", responseCode.equals("00") ? "00" : responseCode);
        params.put("vnp_TmnCode", "TESTTMN1");
        params.put("vnp_Amount", payment.getAmount().movePointRight(2).toBigIntegerExact().toString());
        params.put("vnp_TransactionNo", "VNP" + UUID.randomUUID());
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_PayDate", "20260101120000");
        String hash = signatureService.hash(params);
        params.put("vnp_SecureHash", hash);
        return params;
    }

    private MockHttpServletRequestBuilder callbackRequest(Map<String, String> params) {
        MockHttpServletRequestBuilder builder = post("/api/v1/payments/callback");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder = builder.param(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    private MvcResult sendCallback(Map<String, String> params) throws Exception {
        return mockMvc.perform(callbackRequest(params)).andReturn();
    }

    // ===== create payment session: success path =====

    @Test
    void createPaymentSession_forOwnValidVnpayOrder_succeeds_movesOrderToPending() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Jersey");
        String variantId = createVariant(ctx, productId, 200000, 10);

        String email = uniqueEmail("pay-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 2);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");

        MvcResult result = createPaymentSession(buyer.accessToken(), orderId);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/paymentUrl").asText()).startsWith("https://sandbox.vnpayment.vn");
        assertThat(body.at("/data/transactionRef").asText()).isNotBlank();

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.getPaymentStatus().name()).isEqualTo("PENDING");

        var payments = paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus().name()).isEqualTo("PENDING");
        assertThat(payments.get(0).getAmount()).isEqualByComparingTo(order.getTotalAmount());
    }

    // ===== ownership =====

    @Test
    void createPaymentSession_forAnotherUsersOrder_returns404() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Cleats");
        String variantId = createVariant(ctx, productId, 300000, 10);

        String ownerEmail = uniqueEmail("pay-owner");
        TokenPair owner = registerUser(ownerEmail);
        addToCart(owner.accessToken(), variantId, 1);
        String addressId = createAddressForUser(ownerEmail);
        String orderId = createOrder(owner.accessToken(), addressId, "VNPAY");

        TokenPair stranger = registerUser(uniqueEmail("pay-stranger"));
        MvcResult result = createPaymentSession(stranger.accessToken(), orderId);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ===== not eligible: COD order =====

    @Test
    void createPaymentSession_forCodOrder_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Gloves");
        String variantId = createVariant(ctx, productId, 80000, 10);

        String email = uniqueEmail("pay-cod-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "COD");

        MvcResult result = createPaymentSession(buyer.accessToken(), orderId);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .isEqualTo("Order is not eligible for online payment");
    }

    // ===== not eligible: cancelled order =====

    @Test
    void createPaymentSession_forCancelledOrder_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Whistle");
        String variantId = createVariant(ctx, productId, 20000, 10);

        String email = uniqueEmail("pay-cancelled-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk());

        MvcResult result = createPaymentSession(buyer.accessToken(), orderId);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .isEqualTo("Order is cancelled");
    }

    // ===== create-session called twice while pending: reuse the same session =====

    @Test
    void createPaymentSession_calledTwiceWhilePending_reusesSameSession() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Headband");
        String variantId = createVariant(ctx, productId, 15000, 10);

        String email = uniqueEmail("pay-retry-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");

        MvcResult first = createPaymentSession(buyer.accessToken(), orderId);
        String firstRef = json(first.getResponse().getContentAsString()).at("/data/transactionRef").asText();

        MvcResult second = createPaymentSession(buyer.accessToken(), orderId);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String secondRef = json(second.getResponse().getContentAsString()).at("/data/transactionRef").asText();

        assertThat(secondRef).isEqualTo(firstRef);
        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId())).hasSize(1);
    }

    // ===== after a failed attempt, a new create-session call opens a fresh row =====

    @Test
    void createPaymentSession_afterPreviousAttemptFailed_opensNewSession() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Towel");
        String variantId = createVariant(ctx, productId, 25000, 10);

        String email = uniqueEmail("pay-retry-after-fail");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");

        MvcResult first = createPaymentSession(buyer.accessToken(), orderId);
        String firstRef = json(first.getResponse().getContentAsString()).at("/data/transactionRef").asText();

        Map<String, String> failParams = buildSignedCallbackParams(firstRef, "99");
        mockMvc.perform(callbackRequest(failParams)).andExpect(status().isOk());

        MvcResult second = createPaymentSession(buyer.accessToken(), orderId);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String secondRef = json(second.getResponse().getContentAsString()).at("/data/transactionRef").asText();
        assertThat(secondRef).isNotEqualTo(firstRef);

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId())).hasSize(2);
    }

    @Test
    void createPaymentSession_nearOrderDeadline_capsSessionAtOrderDeadline() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Deadline Shoes");
        String variantId = createVariant(ctx, productId, 250000, 10);
        String email = uniqueEmail("pay-near-deadline");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String orderId = createOrder(buyer.accessToken(), createAddressForUser(email), "VNPAY");

        Instant createdAt = Instant.now().minusSeconds(29 * 60L);
        jdbcTemplate.update(
                "update orders set created_at = ? where id = ?", Timestamp.from(createdAt), UUID.fromString(orderId));

        createPaymentSession(buyer.accessToken(), orderId).getResponse();

        var payment = paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(UUID.fromString(orderId)).get(0);
        Instant hardDeadline = createdAt.plusSeconds(30 * 60L);
        assertThat(payment.getExpiresAt()).isAfter(Instant.now());
        assertThat(payment.getExpiresAt())
                .as("a retry URL must not outlive the order's auto-cancellation deadline")
                .isBeforeOrEqualTo(hardDeadline.plusMillis(1));
    }

    @Test
    void createPaymentSession_afterOrderDeadline_isRejectedWithoutCreatingPayment() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Expired Payment Shoes");
        String variantId = createVariant(ctx, productId, 260000, 10);
        String email = uniqueEmail("pay-after-deadline");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String orderId = createOrder(buyer.accessToken(), createAddressForUser(email), "VNPAY");
        UUID orderUuid = UUID.fromString(orderId);

        jdbcTemplate.update(
                "update orders set created_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(31 * 60L)),
                orderUuid);

        MvcResult result = createPaymentSession(buyer.accessToken(), orderId);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .isEqualTo("Order payment window has expired");
        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(orderUuid)).isEmpty();
    }

    // ===== callback success =====

    @Test
    void callback_success_marksPaymentPaidAndOrderPaid() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Trophy");
        String variantId = createVariant(ctx, productId, 500000, 10);

        String email = uniqueEmail("pay-success-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");
        MvcResult session = createPaymentSession(buyer.accessToken(), orderId);
        String txnRef = json(session.getResponse().getContentAsString()).at("/data/transactionRef").asText();

        Map<String, String> params = buildSignedCallbackParams(txnRef, "00");
        MvcResult callbackResult = sendCallback(params);
        assertThat(callbackResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode callback = json(callbackResult.getResponse().getContentAsString());
        assertThat(callback.at("/RspCode").asText()).isEqualTo("00");
        assertThat(callback.at("/Message").asText()).isEqualTo("Confirm Success");

        var payment = paymentRepository.findByTransactionRef(txnRef).orElseThrow();
        assertThat(payment.getStatus().name()).isEqualTo("PAID");
        assertThat(payment.getPaidAt()).isNotNull();

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.getPaymentStatus().name()).isEqualTo("PAID");
    }

    // ===== callback fail =====

    @Test
    void callback_fail_marksPaymentAndOrderFailed() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Banner");
        String variantId = createVariant(ctx, productId, 60000, 10);

        String email = uniqueEmail("pay-fail-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");
        MvcResult session = createPaymentSession(buyer.accessToken(), orderId);
        String txnRef = json(session.getResponse().getContentAsString()).at("/data/transactionRef").asText();

        Map<String, String> params = buildSignedCallbackParams(txnRef, "99");
        mockMvc.perform(callbackRequest(params)).andExpect(status().isOk());

        var payment = paymentRepository.findByTransactionRef(txnRef).orElseThrow();
        assertThat(payment.getStatus().name()).isEqualTo("FAILED");

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.getPaymentStatus().name()).isEqualTo("FAILED");
    }

    // ===== callback invalid signature =====

    @Test
    void callback_invalidSignature_isRejected_andPaymentUntouched() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Flag");
        String variantId = createVariant(ctx, productId, 40000, 10);

        String email = uniqueEmail("pay-badsig-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");
        MvcResult session = createPaymentSession(buyer.accessToken(), orderId);
        String txnRef = json(session.getResponse().getContentAsString()).at("/data/transactionRef").asText();

        Map<String, String> params = buildSignedCallbackParams(txnRef, "00");
        params.put("vnp_SecureHash", "deadbeef" + params.get("vnp_SecureHash"));

        MvcResult result = sendCallback(params);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(result.getResponse().getContentAsString()).at("/RspCode").asText()).isEqualTo("97");

        var payment = paymentRepository.findByTransactionRef(txnRef).orElseThrow();
        assertThat(payment.getStatus().name())
                .as("a rejected callback must not change the payment's status")
                .isEqualTo("PENDING");
    }

    // ===== callback is public: not blocked by the JWT filter =====

    @Test
    void callback_withoutAuthHeader_isRejectedByChecksumNotByAuth() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TxnRef", "does-not-exist");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_SecureHash", "not-a-real-hash");

        MvcResult result = sendCallback(params);
        assertThat(result.getResponse().getStatus())
                .as("callback must be public and return VNPay's IPN response contract, never 401")
                .isEqualTo(200);
        assertThat(json(result.getResponse().getContentAsString()).at("/RspCode").asText()).isEqualTo("97");
    }

    // ===== duplicate callback delivery is idempotent =====

    @Test
    void callback_duplicateSuccessDelivery_isIdempotent_doesNotReapplySideEffect() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Medal");
        String variantId = createVariant(ctx, productId, 90000, 10);

        String email = uniqueEmail("pay-dup-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");
        MvcResult session = createPaymentSession(buyer.accessToken(), orderId);
        String txnRef = json(session.getResponse().getContentAsString()).at("/data/transactionRef").asText();

        Map<String, String> params = buildSignedCallbackParams(txnRef, "00");
        mockMvc.perform(callbackRequest(params)).andExpect(status().isOk());

        var afterFirst = paymentRepository.findByTransactionRef(txnRef).orElseThrow();
        var paidAtAfterFirst = afterFirst.getPaidAt();
        assertThat(paidAtAfterFirst).isNotNull();

        // Same gateway re-delivers the exact same callback a second time.
        MvcResult secondDelivery = sendCallback(params);
        assertThat(secondDelivery.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(secondDelivery.getResponse().getContentAsString()).at("/RspCode").asText())
                .isEqualTo("02");

        var afterSecond = paymentRepository.findByTransactionRef(txnRef).orElseThrow();
        assertThat(afterSecond.getStatus().name()).isEqualTo("PAID");
        assertThat(afterSecond.getPaidAt()).isEqualTo(paidAtAfterFirst);

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.getPaymentStatus().name()).isEqualTo("PAID");
    }

    // ===== regression: a fail callback arriving after an already-applied success must not flip state =====

    @Test
    void callback_failArrivingAfterAlreadyAppliedSuccess_doesNotFlipPaymentBackToFailed() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Ribbon");
        String variantId = createVariant(ctx, productId, 70000, 10);

        String email = uniqueEmail("pay-conflict-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");
        MvcResult session = createPaymentSession(buyer.accessToken(), orderId);
        String txnRef = json(session.getResponse().getContentAsString()).at("/data/transactionRef").asText();

        mockMvc.perform(callbackRequest(buildSignedCallbackParams(txnRef, "00"))).andExpect(status().isOk());

        // A late/out-of-order "fail" delivery for the SAME transactionRef arrives next.
        MvcResult lateFail = sendCallback(buildSignedCallbackParams(txnRef, "99"));
        assertThat(lateFail.getResponse().getStatus()).isEqualTo(200);

        var payment = paymentRepository.findByTransactionRef(txnRef).orElseThrow();
        assertThat(payment.getStatus().name())
                .as("an already-terminal PAID payment must never be overwritten by a later conflicting callback")
                .isEqualTo("PAID");

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.getPaymentStatus().name()).isEqualTo("PAID");
    }

    // ===== payment query: owner can view, stranger cannot =====

    @Test
    void getPaymentsByOrder_ownerCanView_strangerGets404() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Poster");
        String variantId = createVariant(ctx, productId, 35000, 10);

        String ownerEmail = uniqueEmail("pay-query-owner");
        TokenPair owner = registerUser(ownerEmail);
        addToCart(owner.accessToken(), variantId, 1);
        String addressId = createAddressForUser(ownerEmail);
        String orderId = createOrder(owner.accessToken(), addressId, "VNPAY");
        createPaymentSession(owner.accessToken(), orderId);

        MvcResult ownerView = mockMvc.perform(get("/api/v1/payments/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andReturn();
        assertThat(ownerView.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ownerView.getResponse().getContentAsString()).at("/data")).hasSize(1);

        TokenPair stranger = registerUser(uniqueEmail("pay-query-stranger"));
        MvcResult strangerView = mockMvc.perform(get("/api/v1/payments/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger.accessToken()))
                .andReturn();
        assertThat(strangerView.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void admin_canViewPaymentsForAnyOrder() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Pennant");
        String variantId = createVariant(ctx, productId, 45000, 10);

        String buyerEmail = uniqueEmail("pay-admin-view-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");
        createPaymentSession(buyer.accessToken(), orderId);

        MvcResult adminView = mockMvc.perform(get("/api/v1/payments/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        assertThat(adminView.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(adminView.getResponse().getContentAsString()).at("/data")).hasSize(1);
    }

    // ===== concurrent create-session for the same order must not duplicate the PENDING row =====

    @Test
    void createPaymentSession_calledConcurrentlyForSameOrder_onlyOnePendingRowExists() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Concurrent Jersey");
        String variantId = createVariant(ctx, productId, 150000, 10);

        String email = uniqueEmail("pay-race-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");

        Callable<MvcResult> call = () -> createPaymentSession(buyer.accessToken(), orderId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> results = pool.invokeAll(List.of(call, call));
        pool.shutdown();

        List<String> refs = new java.util.ArrayList<>();
        for (Future<MvcResult> f : results) {
            MvcResult result = f.get();
            assertThat(result.getResponse().getStatus()).as("must never fail with 500 under this race").isEqualTo(201);
            refs.add(json(result.getResponse().getContentAsString()).at("/data/transactionRef").asText());
        }
        assertThat(refs.get(0))
                .as("both concurrent calls must resolve to the same active session")
                .isEqualTo(refs.get(1));

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        var payments = paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId());
        long pendingCount =
                payments.stream().filter(p -> p.getStatus().name().equals("PENDING")).count();
        assertThat(pendingCount)
                .as("at most one PENDING attempt may exist for an order at a time")
                .isEqualTo(1);
        assertThat(payments)
                .as("the race must not have inserted a second competing row")
                .hasSize(1);
    }

    // ===== callback success arriving after the order was independently cancelled =====

    @Test
    void callback_success_afterOrderAlreadyCancelled_recordsPaymentAndCreatesRefund() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Late Callback Cap");
        String variantId = createVariant(ctx, productId, 95000, 10);

        String email = uniqueEmail("pay-cancel-then-callback");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId, "VNPAY");

        MvcResult session = createPaymentSession(buyer.accessToken(), orderId);
        String txnRef = json(session.getResponse().getContentAsString()).at("/data/transactionRef").asText();

        // Order is still PENDING_CONFIRMATION right after creation, so customer
        // cancel succeeds here - simulating the gateway's callback arriving late,
        // after the order was independently cancelled while payment was in flight.
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk());

        var orderBeforeCallback = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(orderBeforeCallback.getOrderStatus().name()).isEqualTo("CANCELLED");
        MvcResult callbackResult = sendCallback(buildSignedCallbackParams(txnRef, "00"));
        assertThat(callbackResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(callbackResult.getResponse().getContentAsString()).at("/RspCode").asText())
                .isEqualTo("00");

        var payment = paymentRepository.findByTransactionRef(txnRef).orElseThrow();
        assertThat(payment.getStatus().name())
                .as("the gateway's reported outcome is still recorded on the payment row itself")
                .isEqualTo("PAID");
        assertThat(payment.getPaidAt()).isNotNull();

        var orderAfterCallback = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(orderAfterCallback.getOrderStatus().name())
                .as("orderStatus must remain CANCELLED - the callback never touches it")
                .isEqualTo("CANCELLED");
        assertThat(orderAfterCallback.getPaymentStatus())
                .as("the cancelled order still records the financial truth until refund completes")
                .isEqualTo(com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus.PAID);
        Integer pendingRefunds = jdbcTemplate.queryForObject(
                "select count(*) from refunds where order_id = ? and status = 'PENDING'",
                Integer.class, UUID.fromString(orderId));
        assertThat(pendingRefunds).isEqualTo(1);

        // Still idempotent in this exact scenario: a duplicate delivery of the
        // same callback must not flip anything a second time either.
        MvcResult secondDelivery = sendCallback(buildSignedCallbackParams(txnRef, "00"));
        assertThat(secondDelivery.getResponse().getStatus()).isEqualTo(200);
        var orderAfterDuplicate = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(orderAfterDuplicate.getPaymentStatus().name()).isEqualTo("PAID");
        Integer refundsAfterDuplicate = jdbcTemplate.queryForObject(
                "select count(*) from refunds where order_id = ? and status = 'PENDING'",
                Integer.class, UUID.fromString(orderId));
        assertThat(refundsAfterDuplicate).isEqualTo(1);
    }

    @Test
    void paidOrder_cancelsImmediately_andCreatesPendingRefund() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Paid Cancel Guard");
        String variantId = createVariant(ctx, productId, 125000, 10);
        String email = uniqueEmail("paid-cancel-guard");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String orderId = createOrder(buyer.accessToken(), createAddressForUser(email), "VNPAY");
        String txnRef = json(createPaymentSession(buyer.accessToken(), orderId).getResponse().getContentAsString())
                .at("/data/transactionRef").asText();
        mockMvc.perform(callbackRequest(buildSignedCallbackParams(txnRef, "00"))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk());

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.getOrderStatus().name()).isEqualTo("CANCELLED");
        assertThat(order.getPaymentStatus().name()).isEqualTo("PAID");
        Integer pendingRefunds = jdbcTemplate.queryForObject(
                "select count(*) from refunds where order_id = ? and status = 'PENDING'",
                Integer.class, UUID.fromString(orderId));
        assertThat(pendingRefunds).isEqualTo(1);
    }

    @Test
    void paidOrder_cancelledConcurrently_createsExactlyOnePendingRefund() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Concurrent Refund Jersey");
        String variantId = createVariant(ctx, productId, 175000, 10);
        String email = uniqueEmail("concurrent-refund");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String orderId = createOrder(buyer.accessToken(), createAddressForUser(email), "VNPAY");
        String txnRef = json(createPaymentSession(buyer.accessToken(), orderId).getResponse().getContentAsString())
                .at("/data/transactionRef").asText();
        mockMvc.perform(callbackRequest(buildSignedCallbackParams(txnRef, "00"))).andExpect(status().isOk());
        Callable<MvcResult> cancel = () -> mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/cancel")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andReturn();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> results = pool.invokeAll(List.of(cancel, cancel));
        pool.shutdown();

        for (Future<MvcResult> result : results) {
            assertThat(result.get().getResponse().getStatus()).isEqualTo(200);
        }
        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.getOrderStatus().name()).isEqualTo("CANCELLED");
        assertThat(order.getPaymentStatus().name()).isEqualTo("PAID");
        Integer pendingRefunds = jdbcTemplate.queryForObject(
                "select count(*) from refunds where order_id = ? and status = 'PENDING'",
                Integer.class, UUID.fromString(orderId));
        assertThat(pendingRefunds).isEqualTo(1);
    }

    @Test
    void unpaidVnpayOrder_cannotBeConfirmed() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Unpaid Confirm Guard");
        String variantId = createVariant(ctx, productId, 130000, 10);
        String email = uniqueEmail("unpaid-confirm-guard");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String orderId = createOrder(buyer.accessToken(), createAddressForUser(email), "VNPAY");

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isConflict());

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.getOrderStatus().name()).isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void callback_withSignedWrongAmount_isRejected() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Amount Guard");
        String variantId = createVariant(ctx, productId, 140000, 10);
        String email = uniqueEmail("amount-guard");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String orderId = createOrder(buyer.accessToken(), createAddressForUser(email), "VNPAY");
        String txnRef = json(createPaymentSession(buyer.accessToken(), orderId).getResponse().getContentAsString())
                .at("/data/transactionRef").asText();

        Map<String, String> params = buildSignedCallbackParams(txnRef, "00");
        params.put("vnp_Amount", "1");
        params.remove("vnp_SecureHash");
        params.put("vnp_SecureHash", signatureService.hash(params));

        MvcResult mismatch = mockMvc.perform(callbackRequest(params)).andExpect(status().isOk()).andReturn();
        assertThat(json(mismatch.getResponse().getContentAsString()).at("/RspCode").asText()).isEqualTo("04");
        assertThat(paymentRepository.findByTransactionRef(txnRef).orElseThrow().getStatus().name())
                .isEqualTo("PENDING");
    }

    @Test
    void callback_requiresMatchingMerchantAndTransactionStatus() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Merchant Guard");
        String variantId = createVariant(ctx, productId, 145000, 10);
        String email = uniqueEmail("merchant-guard");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String orderId = createOrder(buyer.accessToken(), createAddressForUser(email), "VNPAY");
        String txnRef = json(createPaymentSession(buyer.accessToken(), orderId).getResponse().getContentAsString())
                .at("/data/transactionRef").asText();

        Map<String, String> wrongMerchant = buildSignedCallbackParams(txnRef, "00");
        wrongMerchant.put("vnp_TmnCode", "OTHER001");
        wrongMerchant.remove("vnp_SecureHash");
        wrongMerchant.put("vnp_SecureHash", signatureService.hash(wrongMerchant));
        MvcResult merchantResult =
                mockMvc.perform(callbackRequest(wrongMerchant)).andExpect(status().isOk()).andReturn();
        assertThat(json(merchantResult.getResponse().getContentAsString()).at("/RspCode").asText())
                .isEqualTo("99");

        Map<String, String> unsuccessfulTransaction = buildSignedCallbackParams(txnRef, "00");
        unsuccessfulTransaction.put("vnp_TransactionStatus", "01");
        unsuccessfulTransaction.remove("vnp_SecureHash");
        unsuccessfulTransaction.put("vnp_SecureHash", signatureService.hash(unsuccessfulTransaction));
        mockMvc.perform(callbackRequest(unsuccessfulTransaction)).andExpect(status().isOk());

        assertThat(paymentRepository.findByTransactionRef(txnRef).orElseThrow().getStatus().name())
                .isEqualTo("FAILED");
    }
}
