package com.dunghaiquyen.ecommerce.modules.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.notification.entity.Notification;
import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationType;
import com.dunghaiquyen.ecommerce.modules.notification.repository.NotificationRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("ntf-admin"));
        String categorySlug = "ntf-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "ntf-brand-" + UUID.randomUUID();
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
        String slug = "ntf-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\",\"status\":\"DRAFT\"}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        return productId;
    }

    private String createVariant(AdminContext ctx, String productId) throws Exception {
        return createVariant(ctx, productId, "M", "Black");
    }

    private String createVariant(AdminContext ctx, String productId, String size, String color) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"SKU-%s\",\"size\":\"%s\",\"color\":\"%s\",\"price\":80000,\"stockQuantity\":20}")
                                .formatted(UUID.randomUUID(), size, color)))
                .andExpect(status().isCreated())
                .andReturn();
        if (productRepository.findById(UUID.fromString(productId)).orElseThrow().getStatus()
                == com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus.DRAFT) {
            mockMvc.perform(post("/api/v1/admin/products/" + productId + "/images")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(("{\"imageUrl\":\"https://res.cloudinary.com/demo/image/upload/%s.jpg\",\"publicId\":\"notification/%s\",\"isPrimary\":true}")
                                    .formatted(productId, UUID.randomUUID())))
                    .andExpect(status().isCreated());
            var image = productImageRepository.findAllByProductIdOrderBySortOrderAsc(UUID.fromString(productId)).getFirst();
            image.setChecksum("0".repeat(64));
            image.setBytes(1L);
            image.setWidth(1);
            image.setHeight(1);
            productImageRepository.save(image);
            mockMvc.perform(patch("/api/v1/admin/products/" + productId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\"}"))
                    .andExpect(status().isOk());
        }
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

    private record CreatedOrder(String id, String orderCode) {
    }

    private CreatedOrder createOrder(String buyerToken, String addressId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        return new CreatedOrder(body.at("/data/id").asText(), body.at("/data/orderCode").asText());
    }

    private List<Notification> notificationsFor(UUID orderId, NotificationType type) {
        for (int attempt = 0; attempt < 100; attempt++) {
            List<Notification> notifications = notificationRepository.findAll().stream()
                    .filter(n -> n.getOrder() != null && n.getOrder().getId().equals(orderId) && n.getType() == type)
                    .toList();
            if (!notifications.isEmpty()
                    && notifications.stream().noneMatch(n -> n.getStatus().name().equals("PENDING"))) {
                return notifications;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return notificationRepository.findAll().stream()
                .filter(n -> n.getOrder() != null && n.getOrder().getId().equals(orderId) && n.getType() == type)
                .toList();
    }

    // ===== order created successfully -> ORDER_CREATED notification logged =====

    @Test
    void createOrder_logsOrderCreatedNotification() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Notify Shirt");
        String variantId = createVariant(ctx, productId);

        String buyerEmail = uniqueEmail("ntf-create-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId);
        String addressId = createAddressForUser(buyerEmail);

        CreatedOrder order = createOrder(buyer.accessToken(), addressId);

        List<Notification> notifications = notificationsFor(UUID.fromString(order.id()), NotificationType.ORDER_CREATED);
        assertThat(notifications).hasSize(1);
        Notification notification = notifications.get(0);
        assertThat(notification.getStatus().name()).isEqualTo("SENT");
        assertThat(notification.getRecipient()).isEqualTo(buyerEmail);
        assertThat(notification.getSubject()).contains(order.orderCode());
        // Exact Vietnamese diacritics, asserted on the actual Java string content
        // (not the console) - proves UTF-8 round-trips correctly through Postgres.
        assertThat(notification.getSubject()).isEqualTo("Xác nhận đơn hàng " + order.orderCode());
        assertThat(notification.getBody()).contains("Cảm ơn bạn đã đặt hàng");
        assertThat(notification.getChannel().name()).isEqualTo("EMAIL");
        assertThat(notification.getSentAt()).isNotNull();
    }

    // ===== customer cancel -> ORDER_CANCELLED notification logged =====

    @Test
    void cancelOwnOrder_logsOrderCancelledNotification() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Notify Cancel");
        String variantId = createVariant(ctx, productId);

        String buyerEmail = uniqueEmail("ntf-cancel-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId);
        String addressId = createAddressForUser(buyerEmail);
        CreatedOrder order = createOrder(buyer.accessToken(), addressId);

        mockMvc.perform(post("/api/v1/orders/" + order.id() + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed my mind\"}"))
                .andExpect(status().isOk());

        List<Notification> notifications = notificationsFor(UUID.fromString(order.id()), NotificationType.ORDER_CANCELLED);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getRecipient()).isEqualTo(buyerEmail);
    }

    // ===== delivered -> ORDER_DELIVERED notification logged =====

    @Test
    void adminMarksDelivered_logsOrderDeliveredNotification() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Notify Delivered");
        String variantId = createVariant(ctx, productId);

        String buyerEmail = uniqueEmail("ntf-delivered-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId);
        String addressId = createAddressForUser(buyerEmail);
        CreatedOrder order = createOrder(buyer.accessToken(), addressId);

        for (String next : new String[] {"CONFIRMED", "PACKING", "SHIPPING", "DELIVERED"}) {
            mockMvc.perform(patch("/api/v1/admin/orders/" + order.id() + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + next + "\"}"))
                    .andExpect(status().isOk());
        }

        List<Notification> notifications = notificationsFor(UUID.fromString(order.id()), NotificationType.ORDER_DELIVERED);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getRecipient()).isEqualTo(buyerEmail);
    }

    // ===== forgot password -> PASSWORD_RESET notification logged =====

    @Test
    void forgotPassword_logsPasswordResetNotification() throws Exception {
        String email = uniqueEmail("ntf-forgot");
        registerUser(email);

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());

        List<Notification> notifications = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.PASSWORD_RESET && n.getRecipient().equals(email))
                .toList();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getOrder()).isNull();
        assertThat(notifications.get(0).getStatus().name()).isEqualTo("SENT");
    }

    // ===== admin can list notifications =====

    @Test
    void adminListNotifications_succeeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Notify AdminList");
        String variantId = createVariant(ctx, productId);

        String buyerEmail = uniqueEmail("ntf-adminlist-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId);
        String addressId = createAddressForUser(buyerEmail);
        CreatedOrder order = createOrder(buyer.accessToken(), addressId);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .param("type", "ORDER_CREATED")
                        .param("orderId", order.id()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/type").asText()).isEqualTo("ORDER_CREATED");
        assertThat(body.at("/data/0/orderId").asText()).isEqualTo(order.id());
    }

    // ===== non-admin cannot access admin notification history =====

    @Test
    void nonAdmin_cannotListAdminNotifications_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("ntf-not-admin"));
        mockMvc.perform(get("/api/v1/admin/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ===== user can view their own notifications, scoped to themselves only =====

    @Test
    void user_canListOwnNotifications_butOnlyTheirOwn() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Notify SelfView");
        String variantId1 = createVariant(ctx, productId);
        String variantId2 = createVariant(ctx, productId, "L", "Black");

        String buyerEmail = uniqueEmail("ntf-self-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId1);
        String addressId = createAddressForUser(buyerEmail);
        createOrder(buyer.accessToken(), addressId);

        String otherEmail = uniqueEmail("ntf-self-other");
        TokenPair other = registerUser(otherEmail);
        addToCart(other.accessToken(), variantId2);
        String otherAddressId = createAddressForUser(otherEmail);
        createOrder(other.accessToken(), otherAddressId);

        MvcResult result = mockMvc.perform(get("/api/v1/notifications/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        for (JsonNode item : body.at("/data")) {
            assertThat(item.at("/recipient").asText()).isEqualTo(buyerEmail);
        }
        boolean foundOwn = false;
        for (JsonNode item : body.at("/data")) {
            if (item.at("/recipient").asText().equals(buyerEmail)) {
                foundOwn = true;
            }
        }
        assertThat(foundOwn).isTrue();
    }
}
