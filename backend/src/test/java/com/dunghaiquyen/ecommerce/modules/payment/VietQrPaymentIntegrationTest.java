package com.dunghaiquyen.ecommerce.modules.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentProvider;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties = {
        "app.vietqr.enabled=true",
        "app.vietqr.bank-bin=970422",
        "app.vietqr.account-number=1326045980",
        "app.vietqr.account-name=LE THANH H",
        "app.vietqr.payment-expiry-minutes=30"
})
class VietQrPaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void endpointCreatesThenResumesTheSamePendingPayment() throws Exception {
        String email = uniqueEmail("vietqr-owner");
        TokenPair owner = registerUser(email);
        Order order = saveOrder(email, PaymentMethod.VIETQR);
        String body = "{\"orderId\":\"" + order.getId() + "\"}";

        var first = mockMvc.perform(post("/api/v1/payments/vietqr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        var second = mockMvc.perform(post("/api/v1/payments/vietqr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        var firstData = json(first.getResponse().getContentAsString()).at("/data");
        var secondData = json(second.getResponse().getContentAsString()).at("/data");
        assertThat(firstData.path("paymentId").asText()).isEqualTo(secondData.path("paymentId").asText());
        assertThat(firstData.path("transactionRef").asText())
                .matches("DH[A-HJ-NP-Z2-9]{10}")
                .isEqualTo(secondData.path("transactionRef").asText());
        assertThat(firstData.path("amount").decimalValue()).isEqualByComparingTo("250000.00");
        assertThat(firstData.path("bankBin").asText()).isEqualTo("970422");
        assertThat(firstData.path("accountNumber").asText()).isEqualTo("1326045980");

        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId()))
                .singleElement()
                .satisfies(payment -> {
                    assertThat(payment.getProvider()).isEqualTo(PaymentProvider.BANK_TRANSFER);
                    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
                    assertThat(payment.getAmount()).isEqualByComparingTo(order.getTotalAmount());
                });
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void concurrentDoubleClickCreatesOnlyOnePendingPayment() throws Exception {
        String email = uniqueEmail("vietqr-concurrent-owner");
        TokenPair owner = registerUser(email);
        Order order = saveOrder(email, PaymentMethod.VIETQR);
        String body = "{\"orderId\":\"" + order.getId() + "\"}";
        Callable<MvcResult> call = () -> mockMvc.perform(post("/api/v1/payments/vietqr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(call, call));
            String firstId = json(results.get(0).get().getResponse().getContentAsString())
                    .at("/data/paymentId").asText();
            String secondId = json(results.get(1).get().getResponse().getContentAsString())
                    .at("/data/paymentId").asText();

            assertThat(results.get(0).get().getResponse().getStatus()).isEqualTo(201);
            assertThat(results.get(1).get().getResponse().getStatus()).isEqualTo(201);
            assertThat(firstId).isNotBlank().isEqualTo(secondId);
        }
        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId())).hasSize(1);
    }

    @Test
    void endpointDoesNotRevealAnotherCustomersOrder() throws Exception {
        String ownerEmail = uniqueEmail("vietqr-real-owner");
        registerUser(ownerEmail);
        Order order = saveOrder(ownerEmail, PaymentMethod.VIETQR);
        TokenPair other = registerUser(uniqueEmail("vietqr-other"));

        mockMvc.perform(post("/api/v1/payments/vietqr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order.getId() + "\"}"))
                .andExpect(status().isNotFound());

        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId())).isEmpty();
    }

    @Test
    void ownerCanReloadVietQrPaymentButAnotherCustomerCannot() throws Exception {
        String email = uniqueEmail("vietqr-reload-owner");
        TokenPair owner = registerUser(email);
        Order order = saveOrder(email, PaymentMethod.VIETQR);
        MvcResult created = mockMvc.perform(post("/api/v1/payments/vietqr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String paymentId = json(created.getResponse().getContentAsString()).at("/data/paymentId").asText();

        MvcResult detail = mockMvc.perform(get("/api/v1/payments/vietqr/{paymentId}", paymentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(detail.getResponse().getContentAsString()).at("/data/status").asText())
                .isEqualTo("PENDING");
        assertThat(json(detail.getResponse().getContentAsString()).at("/data/orderId").asText())
                .isEqualTo(order.getId().toString());

        TokenPair other = registerUser(uniqueEmail("vietqr-reload-other"));
        mockMvc.perform(get("/api/v1/payments/vietqr/{paymentId}", paymentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void endpointRejectsCodOrder() throws Exception {
        String email = uniqueEmail("vietqr-cod-owner");
        TokenPair owner = registerUser(email);
        Order order = saveOrder(email, PaymentMethod.COD);

        mockMvc.perform(post("/api/v1/payments/vietqr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order.getId() + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private Order saveOrder(String email, PaymentMethod paymentMethod) {
        Order order = new Order();
        order.setOrderCode("VQR-" + UUID.randomUUID());
        order.setUser(userRepository.findByEmail(email).orElseThrow());
        order.setAddressSnapshotJson(Map.of(
                "receiverName", "VietQR Test",
                "phone", "0900000000",
                "addressLine", "123 Test Street"));
        order.setSubtotalAmount(new BigDecimal("250000.00"));
        order.setShippingFee(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("250000.00"));
        order.setCurrencyCode("VND");
        order.setPaymentMethod(paymentMethod);
        order.setPaymentStatus(PaymentStatus.UNPAID);
        return orderRepository.saveAndFlush(order);
    }
}
