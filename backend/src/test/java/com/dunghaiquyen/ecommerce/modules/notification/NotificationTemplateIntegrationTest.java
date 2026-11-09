package com.dunghaiquyen.ecommerce.modules.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationTemplate;
import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationType;
import com.dunghaiquyen.ecommerce.modules.notification.repository.NotificationTemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class NotificationTemplateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationTemplateRepository templateRepository;

    @Autowired
    private AddressRepository addressRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("ntpl-admin"));
        String categorySlug = "ntpl-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "ntpl-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    private String createDraftProduct(AdminContext ctx, String name) throws Exception {
        String slug = "ntpl-prod-" + UUID.randomUUID();
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
        makeProductPublishableForTest(productId);
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
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

    private String orderCreatedTemplateId() {
        return templateRepository.findByType(
                        com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationType.ORDER_CREATED)
                .orElseThrow()
                .getId()
                .toString();
    }

    // ===== list: all 5 types seeded, real content, allowed placeholders exposed =====

    @Test
    void list_returnsAllSeededTemplates_withAllowedPlaceholders() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntpl-list-admin"));

        MvcResult result = mockMvc.perform(get("/api/v1/admin/notification-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");
        assertThat(data).hasSize(NotificationType.values().length);

        JsonNode orderCreated = null;
        for (JsonNode item : data) {
            if (item.at("/type").asText().equals("ORDER_CREATED")) {
                orderCreated = item;
            }
        }
        assertThat(orderCreated).isNotNull();
        assertThat(orderCreated.at("/subject").asText()).contains("{orderCode}");
        assertThat(orderCreated.at("/body").asText()).isNotBlank();
        var placeholders = new java.util.ArrayList<String>();
        orderCreated.at("/allowedPlaceholders").forEach(p -> placeholders.add(p.asText()));
        assertThat(placeholders).containsExactlyInAnyOrder("customerName", "orderCode", "totalAmount", "paymentMethod");
    }

    @Test
    void list_nonAdmin_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("ntpl-list-notadmin"));
        mockMvc.perform(get("/api/v1/admin/notification-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ===== update: valid content actually changes what gets sent =====

    @Test
    void update_validSubjectAndBody_isReflectedInTheNextActualEmail() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntpl-update-admin"));
        String templateId = orderCreatedTemplateId();
        // ORDER_CREATED is a singleton row shared by the whole suite (unique
        // constraint on type) - other test classes (e.g. NotificationIntegrationTest)
        // assert against its DEFAULT subject/body, so this customization must not
        // leak past this test. Captured up front and restored in finally, regardless
        // of outcome.
        NotificationTemplate before = templateRepository.findById(UUID.fromString(templateId)).orElseThrow();
        String originalSubject = before.getSubject();
        String originalBody = before.getBody();

        try {
            String newSubject = "Custom subject for {orderCode}";
            String newBody = "Hi {customerName}, your order {orderCode} totaling {totalAmount} is confirmed.";
            MvcResult patchResult = mockMvc.perform(patch("/api/v1/admin/notification-templates/" + templateId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"subject\":\"" + newSubject + "\",\"body\":\"" + newBody + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(json(patchResult.getResponse().getContentAsString()).at("/data/subject").asText())
                    .isEqualTo(newSubject);

            // Trigger a real ORDER_CREATED send and confirm the customized template - not the
            // old hardcoded default - is what actually went out.
            AdminContext ctx = setUpAdmin();
            String productId = createDraftProduct(ctx, "Template Shirt");
            String variantId = createVariant(ctx, productId);
            String buyerEmail = uniqueEmail("ntpl-update-buyer");
            TokenPair buyer = registerUser(buyerEmail);
            addToCart(buyer.accessToken(), variantId);
            String addressId = createAddressForUser(buyerEmail);

            MvcResult orderResult = mockMvc.perform(post("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();
            JsonNode orderBody = json(orderResult.getResponse().getContentAsString());
            String orderCode = orderBody.at("/data/orderCode").asText();

            MvcResult mineResult = mockMvc.perform(get("/api/v1/notifications/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                            .param("type", "ORDER_CREATED"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode mine = json(mineResult.getResponse().getContentAsString()).at("/data/0");
            assertThat(mine.at("/subject").asText()).isEqualTo("Custom subject for " + orderCode);
            assertThat(mine.at("/body").asText()).contains("your order " + orderCode + " totaling");
        } finally {
            NotificationTemplate restore = templateRepository.findById(UUID.fromString(templateId)).orElseThrow();
            restore.setSubject(originalSubject);
            restore.setBody(originalBody);
            templateRepository.save(restore);
        }
    }

    // ===== update: unknown placeholder rejected, template left unchanged =====

    @Test
    void update_unknownPlaceholder_returns422_leavesTemplateUnchanged() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntpl-badph-admin"));
        String templateId = orderCreatedTemplateId();
        NotificationTemplate before = templateRepository.findById(UUID.fromString(templateId)).orElseThrow();
        String originalSubject = before.getSubject();

        MvcResult result = mockMvc.perform(patch("/api/v1/admin/notification-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Broken {totallyBogusToken}\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);

        NotificationTemplate after = templateRepository.findById(UUID.fromString(templateId)).orElseThrow();
        assertThat(after.getSubject()).isEqualTo(originalSubject);
    }

    @Test
    void update_placeholderNotAllowedForThisType_returns422() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntpl-wrongtype-admin"));
        String templateId = orderCreatedTemplateId();

        // {resetLink} is only valid for PASSWORD_RESET, not ORDER_CREATED.
        MvcResult result = mockMvc.perform(patch("/api/v1/admin/notification-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Click {resetLink} for your order {orderCode}\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void update_blankSubject_returns422() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntpl-blank-admin"));
        String templateId = orderCreatedTemplateId();

        mockMvc.perform(patch("/api/v1/admin/notification-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void update_notFound_returns404() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("ntpl-notfound-admin"));
        mockMvc.perform(patch("/api/v1/admin/notification-templates/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Anything\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_nonAdmin_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("ntpl-update-notadmin"));
        String templateId = orderCreatedTemplateId();
        mockMvc.perform(patch("/api/v1/admin/notification-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Anything\"}"))
                .andExpect(status().isForbidden());
    }
}
