package com.dunghaiquyen.ecommerce.modules.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.common.mail.MailService;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.notification.entity.Notification;
import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationType;
import com.dunghaiquyen.ecommerce.modules.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class NotificationResendIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ToggleableMailService mailService;

    /**
     * Overrides the real LoggingMailService (which never fails) with a fake
     * that can be told to throw for a specific recipient - the only way to
     * deterministically produce a FAILED notification row and then prove
     * resend recovers (or doesn't) it, without depending on a real SMTP
     * server. Same @TestConfiguration/@Primary pattern as
     * ForgotPasswordIntegrationTest's CapturingMailService.
     */
    @TestConfiguration
    static class MailTestConfig {
        @Bean
        @Primary
        MailService toggleableMailService() {
            return new ToggleableMailService();
        }
    }

    static class ToggleableMailService implements MailService {
        private final Set<String> failingRecipients = ConcurrentHashMap.newKeySet();
        private final List<String[]> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String to, String subject, String body) {
            sent.add(new String[] {to, subject, body});
            if (failingRecipients.contains(to)) {
                throw new RuntimeException("Simulated SMTP failure for " + to);
            }
        }

        void failFor(String to) {
            failingRecipients.add(to);
        }

        void recoverFor(String to) {
            failingRecipients.remove(to);
        }

        int sentCountFor(String to) {
            return (int) sent.stream().filter(s -> s[0].equals(to)).count();
        }
    }

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("ntfrs-admin"));
        String categorySlug = "ntfrs-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "ntfrs-brand-" + UUID.randomUUID();
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
        String slug = "ntfrs-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String createVariant(AdminContext ctx, String productId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"SKU-%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":80000,\"stockQuantity\":20}")
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        String variantId = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        makeProductPublishableForTest(productId);
        return variantId;
    }

    private void addToCart(String token, String variantId) throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":1}"))
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

    /** Creates an order whose ORDER_CREATED notification ends up FAILED (buyer's email pre-registered as failing), returns the notification id. */
    private UUID createOrderWithFailedNotification(String buyerEmail) throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Resend Item");
        String variantId = createVariant(ctx, productId);
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId);
        String addressId = createAddressForUser(buyerEmail);

        mailService.failFor(buyerEmail);
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated());

        Notification notification = awaitOrderCreatedNotification(buyerEmail);
        assertThat(notification.getStatus().name()).isEqualTo("FAILED");
        return notification.getId();
    }

    private Notification awaitOrderCreatedNotification(String buyerEmail) {
        for (int attempt = 0; attempt < 100; attempt++) {
            var notification = notificationRepository.findAll().stream()
                    .filter(n -> n.getType() == NotificationType.ORDER_CREATED
                            && n.getRecipient().equals(buyerEmail)
                            && !n.getStatus().name().equals("PENDING"))
                    .findFirst();
            if (notification.isPresent()) {
                return notification.get();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for order notification delivery");
    }

    // ===== resend success: FAILED -> recovered, new row created, original bookkeeping updated =====

    @Test
    void resend_failedNotification_afterRecoveringMailService_succeeds_createsNewRow() throws Exception {
        String buyerEmail = uniqueEmail("ntfrs-success");
        UUID originalId = createOrderWithFailedNotification(buyerEmail);

        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntfrs-success-admin"));
        mailService.recoverFor(buyerEmail);

        MvcResult result = mockMvc.perform(post("/api/v1/admin/notifications/" + originalId + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString()).at("/data");
        assertThat(body.at("/status").asText()).isEqualTo("SENT");
        assertThat(body.at("/resendOfId").asText()).isEqualTo(originalId.toString());
        assertThat(body.at("/id").asText()).isNotEqualTo(originalId.toString());

        // Original row's own history is untouched - it really did fail, at that time.
        Notification original = notificationRepository.findById(originalId).orElseThrow();
        assertThat(original.getStatus().name()).isEqualTo("FAILED");
        assertThat(original.getResendCount()).isEqualTo(1);
        assertThat(original.getLastResendAt()).isNotNull();

        // Exactly 2 rows total for this recipient: the original FAILED + the new SENT resend.
        long total = notificationRepository.findAll().stream()
                .filter(n -> n.getRecipient().equals(buyerEmail))
                .count();
        assertThat(total).isEqualTo(2);
    }

    // ===== resend failure: still records FAILED on the new row, still 200, not 500 =====

    @Test
    void resend_failedNotification_stillFailing_returns200_recordsNewFailedRow() throws Exception {
        String buyerEmail = uniqueEmail("ntfrs-stillfail");
        UUID originalId = createOrderWithFailedNotification(buyerEmail);
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntfrs-stillfail-admin"));
        // Deliberately NOT recovered - mail still fails for this recipient.

        MvcResult result = mockMvc.perform(post("/api/v1/admin/notifications/" + originalId + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString()).at("/data");
        assertThat(body.at("/status").asText()).isEqualTo("FAILED");
        assertThat(body.at("/errorMessage").asText()).contains("Simulated SMTP failure");
        assertThat(body.at("/resendOfId").asText()).isEqualTo(originalId.toString());
    }

    // ===== resend a SENT notification is allowed (support case: "I never got it") =====

    @Test
    void resend_sentNotification_isAllowed() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Resend Sent Item");
        String variantId = createVariant(ctx, productId);
        String buyerEmail = uniqueEmail("ntfrs-resendsent");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId);
        String addressId = createAddressForUser(buyerEmail);
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated());
        Notification sent = awaitOrderCreatedNotification(buyerEmail);
        assertThat(sent.getStatus().name()).isEqualTo("SENT");

        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntfrs-resendsent-admin"));
        mockMvc.perform(post("/api/v1/admin/notifications/" + sent.getId() + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThat(mailService.sentCountFor(buyerEmail)).isEqualTo(2);
    }

    // ===== resend a resend record is rejected =====

    @Test
    void resend_aResendRecordItself_returns409() throws Exception {
        String buyerEmail = uniqueEmail("ntfrs-chain");
        UUID originalId = createOrderWithFailedNotification(buyerEmail);
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntfrs-chain-admin"));
        mailService.recoverFor(buyerEmail);

        MvcResult firstResend = mockMvc.perform(post("/api/v1/admin/notifications/" + originalId + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String resendRowId = json(firstResend.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/admin/notifications/" + resendRowId + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    // ===== cooldown: resending twice in a row is rejected the second time =====

    @Test
    void resend_secondCallWithinCooldown_returns409() throws Exception {
        String buyerEmail = uniqueEmail("ntfrs-cooldown");
        UUID originalId = createOrderWithFailedNotification(buyerEmail);
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntfrs-cooldown-admin"));

        mockMvc.perform(post("/api/v1/admin/notifications/" + originalId + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/v1/admin/notifications/" + originalId + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .containsIgnoringCase("wait");
    }

    // ===== max attempts: capped at 5, backdating lastResendAt to bypass cooldown for this check =====

    @Test
    void resend_pastMaxAttempts_returns409() throws Exception {
        String buyerEmail = uniqueEmail("ntfrs-maxcap");
        UUID originalId = createOrderWithFailedNotification(buyerEmail);
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntfrs-maxcap-admin"));

        Notification original = notificationRepository.findById(originalId).orElseThrow();
        original.setResendCount(5);
        original.setLastResendAt(Instant.now().minus(1, ChronoUnit.HOURS));
        notificationRepository.save(original);

        MvcResult result = mockMvc.perform(post("/api/v1/admin/notifications/" + originalId + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .containsIgnoringCase("limit");
    }

    // ===== not found =====

    @Test
    void resend_notFound_returns404() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntfrs-notfound-admin"));
        mockMvc.perform(post("/api/v1/admin/notifications/" + UUID.randomUUID() + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ===== permission =====

    @Test
    void resend_nonAdmin_returns403() throws Exception {
        String buyerEmail = uniqueEmail("ntfrs-notadmin");
        UUID originalId = createOrderWithFailedNotification(buyerEmail);
        TokenPair customer = registerUser(uniqueEmail("ntfrs-notadmin-caller"));
        mockMvc.perform(post("/api/v1/admin/notifications/" + originalId + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }
}
