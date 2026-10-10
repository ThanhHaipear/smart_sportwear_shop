package com.dunghaiquyen.ecommerce.modules.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.audit.repository.AuditLogRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.returns.repository.ReturnRepository;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ReturnRefundIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ReturnRepository returnRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("rtn-admin"));
        String categorySlug = "rtn-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "rtn-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    private String registerWarehouseStaffAndGetAccessToken(String email) throws Exception {
        registerUser(email);
        var user = userRepository.findByEmail(email).orElseThrow();
        user.setRole(UserRole.WAREHOUSE_STAFF);
        userRepository.save(user);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();
    }

    private String createActiveProduct(AdminContext ctx, String name) throws Exception {
        String slug = "rtn-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\",\"status\":\"DRAFT\"}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String createVariant(AdminContext ctx, String productId, int price) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"SKU-%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":%d,\"stockQuantity\":20}")
                                .formatted(UUID.randomUUID(), price)))
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

    private record DeliveredOrder(String orderId, String orderItemId) {
    }

    /** Creates an order, advances it to DELIVERED via the existing admin status endpoint, returns its id and its single order item's id. */
    private DeliveredOrder createDeliveredOrder(AdminContext ctx, String buyerToken, String addressId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json(created.getResponse().getContentAsString());
        String orderId = body.at("/data/id").asText();
        String orderItemId = body.at("/data/items/0/id").asText();

        for (String next : new String[] {"CONFIRMED", "PACKING", "SHIPPING", "DELIVERED"}) {
            mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + next + "\"}"))
                    .andExpect(status().isOk());
        }
        return new DeliveredOrder(orderId, orderItemId);
    }

    private String createReturnRequestBody(String orderId, String orderItemId, int quantity) {
        return ("{\"orderId\":\"%s\",\"reason\":\"DEFECTIVE\",\"description\":\"broken zipper\","
                        + "\"items\":[{\"orderItemId\":\"%s\",\"quantity\":%d,\"reason\":\"DAMAGED\"}]}")
                .formatted(orderId, orderItemId, quantity);
    }

    // ===== happy path: request -> approve -> receive+resolve -> refund -> complete =====

    @Test
    void fullHappyPath_requestApproveReceiveRefundComplete() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return Shirt");
        String variantId = createVariant(ctx, productId, 100000);
        String buyerEmail = uniqueEmail("rtn-happy-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);

        MvcResult createResult = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createBody = json(createResult.getResponse().getContentAsString());
        String returnId = createBody.at("/data/id").asText();
        assertThat(createBody.at("/data/status").asText()).isEqualTo("REQUESTED");
        String returnItemId = createBody.at("/data/items/0/id").asText();

        // Admin approves.
        mockMvc.perform(patch("/api/v1/admin/returns/" + returnId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        // Admin marks RECEIVED with item resolution.
        String receiveBody = ("{\"status\":\"RECEIVED\",\"items\":[{\"returnItemId\":\"%s\","
                        + "\"conditionStatus\":\"LIKE_NEW\",\"resolution\":\"REFUND\",\"refundAmount\":100000}]}")
                .formatted(returnItemId);
        mockMvc.perform(patch("/api/v1/admin/returns/" + returnId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiveBody))
                .andExpect(status().isOk());

        // Admin creates the refund.
        MvcResult refundResult = mockMvc.perform(post("/api/v1/admin/returns/" + returnId + "/refund")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode refundBody = json(refundResult.getResponse().getContentAsString());
        String refundId = refundBody.at("/data/id").asText();
        assertThat(refundBody.at("/data/amount").asDouble()).isEqualTo(100000.0);
        assertThat(refundBody.at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(refundBody.at("/data/provider").asText()).isEqualTo("COD");

        // Completing the refund cascades the return to REFUNDED.
        mockMvc.perform(patch("/api/v1/admin/refunds/" + refundId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());

        MvcResult finalDetail = mockMvc.perform(get("/api/v1/returns/" + returnId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(finalDetail.getResponse().getContentAsString()).at("/data/status").asText())
                .isEqualTo("REFUNDED");
    }

    // ===== eligibility rules =====

    @Test
    void createReturn_orderNotDelivered_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return NotDelivered");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-notdelivered-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json(created.getResponse().getContentAsString());
        String orderId = body.at("/data/id").asText();
        String orderItemId = body.at("/data/items/0/id").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(orderId, orderItemId, 1)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void createReturn_pastReturnWindow_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return Expired");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-expired-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);

        var orderEntity = orderRepository.findById(UUID.fromString(order.orderId())).orElseThrow();
        orderEntity.setDeliveredAt(Instant.now().minus(10, ChronoUnit.DAYS));
        orderEntity.setReturnEligibleUntil(Instant.now().minus(3, ChronoUnit.DAYS));
        orderRepository.save(orderEntity);

        MvcResult result = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void createReturn_quantityExceedsOrdered_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return TooMany");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-toomany-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);

        MvcResult result = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 99)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void createReturn_secondActiveReturn_returns409() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return Duplicate");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-dup-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);

        mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createReturn_anotherUsersOrder_returns404() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return NotMine");
        String variantId = createVariant(ctx, productId, 50000);
        String ownerEmail = uniqueEmail("rtn-notmine-owner");
        TokenPair owner = registerUser(ownerEmail);
        addToCart(owner.accessToken(), variantId, 1);
        String addressId = createAddressForUser(ownerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, owner.accessToken(), addressId);

        TokenPair stranger = registerUser(uniqueEmail("rtn-notmine-stranger"));
        MvcResult result = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ===== customer self-cancel =====

    @Test
    void customerCancel_atRequested_succeeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return SelfCancel");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-selfcancel-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);

        MvcResult created = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        String returnId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult cancelResult = mockMvc.perform(post("/api/v1/returns/" + returnId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andReturn();
        assertThat(cancelResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(cancelResult.getResponse().getContentAsString()).at("/data/status").asText())
                .isEqualTo("CANCELLED");
    }

    @Test
    void customerCancel_anotherUsersReturn_returns404() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return CancelNotMine");
        String variantId = createVariant(ctx, productId, 50000);
        String ownerEmail = uniqueEmail("rtn-cancelnotmine-owner");
        TokenPair owner = registerUser(ownerEmail);
        addToCart(owner.accessToken(), variantId, 1);
        String addressId = createAddressForUser(ownerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, owner.accessToken(), addressId);
        MvcResult created = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        String returnId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        TokenPair stranger = registerUser(uniqueEmail("rtn-cancelnotmine-stranger"));
        mockMvc.perform(post("/api/v1/returns/" + returnId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound());
    }

    // ===== admin status transition rules =====

    @Test
    void adminUpdateStatus_invalidJumpFromRequestedToReceived_returns409() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return InvalidJump");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-invalidjump-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);
        MvcResult created = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        String returnId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult result = mockMvc.perform(patch("/api/v1/admin/returns/" + returnId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RECEIVED\",\"items\":[]}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void adminUpdateStatus_received_missingItemResolution_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return MissingResolution");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-missingres-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);
        MvcResult created = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        String returnId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/admin/returns/" + returnId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(patch("/api/v1/admin/returns/" + returnId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RECEIVED\",\"items\":[]}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void adminUpdateStatus_received_refundAmountExceedsLineValue_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return ExceedRefund");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-exceedrefund-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);
        MvcResult created = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createBody = json(created.getResponse().getContentAsString());
        String returnId = createBody.at("/data/id").asText();
        String returnItemId = createBody.at("/data/items/0/id").asText();

        mockMvc.perform(patch("/api/v1/admin/returns/" + returnId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        String receiveBody = ("{\"status\":\"RECEIVED\",\"items\":[{\"returnItemId\":\"%s\","
                        + "\"conditionStatus\":\"LIKE_NEW\",\"resolution\":\"REFUND\",\"refundAmount\":999999}]}")
                .formatted(returnItemId);
        MvcResult result = mockMvc.perform(patch("/api/v1/admin/returns/" + returnId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiveBody))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    // ===== refund creation rules =====

    @Test
    void createRefund_returnNotReceived_returns409() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return RefundNotReceived");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-refundnotreceived-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);
        MvcResult created = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        String returnId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/admin/returns/" + returnId + "/refund")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    // ===== permission =====

    @Test
    void warehouseStaff_canApproveReturn_butCannotCreateRefund() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return WarehousePerm");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-warehouseperm-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);
        MvcResult created = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        String returnId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("rtn-warehouse-staff"));
        mockMvc.perform(patch("/api/v1/admin/returns/" + returnId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/returns/" + returnId + "/refund")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannotAccessAdminReturnEndpoints() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("rtn-customer-noadmin"));
        mockMvc.perform(get("/api/v1/admin/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ===== audit log: written for status/refund actions, readable by admin only =====

    @Test
    void returnStatusUpdate_writesAuditLogEntry_visibleToAdminOnly() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Return AuditLog");
        String variantId = createVariant(ctx, productId, 50000);
        String buyerEmail = uniqueEmail("rtn-audit-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);
        DeliveredOrder order = createDeliveredOrder(ctx, buyer.accessToken(), addressId);
        MvcResult created = mockMvc.perform(post("/api/v1/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReturnRequestBody(order.orderId(), order.orderItemId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        String returnId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/admin/returns/" + returnId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        boolean found = auditLogRepository.findAll().stream()
                .anyMatch(a -> a.getEntityType().equals("Return") && a.getEntityId().equals(returnId)
                        && a.getAction().equals("RETURN_STATUS_UPDATE"));
        assertThat(found).isTrue();

        MvcResult adminList = mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .param("entityType", "Return")
                        .param("entityId", returnId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(adminList.getResponse().getContentAsString()).at("/data")).hasSize(1);

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isForbidden());
    }
}
