package com.dunghaiquyen.ecommerce.modules.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationType;
import com.dunghaiquyen.ecommerce.modules.notification.repository.NotificationRepository;
import com.dunghaiquyen.ecommerce.modules.shipping.entity.Shipment;
import com.dunghaiquyen.ecommerce.modules.shipping.entity.ShippingMethod;
import com.dunghaiquyen.ecommerce.modules.shipping.entity.ShippingMethodStatus;
import com.dunghaiquyen.ecommerce.modules.shipping.repository.ShipmentRepository;
import com.dunghaiquyen.ecommerce.modules.shipping.repository.ShippingMethodRepository;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@Tag("phase0-excluded")
@Tag("dormant")
class AdminShipmentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private ShippingMethodRepository shippingMethodRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("admship-admin"));
        String categorySlug = "admship-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "admship-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    /** Same pattern as registerAdminAndGetAccessToken, just promoting to WAREHOUSE_STAFF instead of ADMIN. */
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

    private String registerSalesStaffAndGetAccessToken(String email) throws Exception {
        registerUser(email);
        var user = userRepository.findByEmail(email).orElseThrow();
        user.setRole(UserRole.SALES_STAFF);
        userRepository.save(user);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();
    }

    private String createActiveProduct(AdminContext ctx, String name) throws Exception {
        String slug = "admship-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\",\"status\":\"ACTIVE\"}")
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
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
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

    private String createOrder(String buyerToken, String addressId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private void updateOrderStatus(String adminToken, String orderId, String statusValue) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + statusValue + "\"}"))
                .andExpect(status().isOk());
    }

    private String createMethod(String name) {
        ShippingMethod method = new ShippingMethod();
        method.setName(name);
        method.setCode("CODE-" + UUID.randomUUID());
        method.setProvider("GHN");
        method.setBaseFee(BigDecimal.valueOf(15000));
        method.setStatus(ShippingMethodStatus.ACTIVE);
        method.setEstimatedDaysMin(2);
        method.setEstimatedDaysMax(4);
        return shippingMethodRepository.save(method).getId().toString();
    }

    private record TestSetup(AdminContext ctx, String warehouseToken, String orderId, String addressId) {
    }

    private TestSetup buildOrder() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Jacket");
        String variantId = createVariant(ctx, productId, 100000, 10);
        String email = uniqueEmail("admship-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);
        String orderId = createOrder(buyer.accessToken(), addressId);
        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("admship-warehouse"));
        return new TestSetup(ctx, warehouseToken, orderId, addressId);
    }

    // ===== lazy creation on first PATCH =====

    @Test
    void patch_firstCall_createsShipmentFromOrderAddressSnapshot() throws Exception {
        TestSetup setup = buildOrder();

        MvcResult result = mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Handle with care\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/orderId").asText()).isEqualTo(setup.orderId());
        assertThat(body.at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(body.at("/data/shipmentCode").asText()).isNotBlank();
        assertThat(body.at("/data/receiverName").asText()).isEqualTo("Test Receiver");
        assertThat(body.at("/data/receiverPhone").asText()).isEqualTo("0900000000");
        assertThat(body.at("/data/province").asText()).isEqualTo("HCM");
        assertThat(body.at("/data/note").asText()).isEqualTo("Handle with care");

        assertThat(shipmentRepository.findByOrderId(UUID.fromString(setup.orderId()))).isPresent();
    }

    @Test
    void patch_secondCall_updatesSameShipmentRow_doesNotCreateASecondOne() throws Exception {
        TestSetup setup = buildOrder();
        String methodId = createMethod("Express");

        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"GHN\"}"))
                .andExpect(status().isOk());
        UUID shipmentId = shipmentRepository.findByOrderId(UUID.fromString(setup.orderId())).orElseThrow().getId();

        String trackingNumber = "TRACK-" + UUID.randomUUID();
        MvcResult second = mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingMethodId\":\"" + methodId + "\",\"trackingNumber\":\"" + trackingNumber + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(second.getResponse().getContentAsString());
        assertThat(body.at("/data/id").asText()).isEqualTo(shipmentId.toString());
        assertThat(body.at("/data/carrierName").asText()).isEqualTo("GHN");
        assertThat(body.at("/data/trackingNumber").asText()).isEqualTo(trackingNumber);
        assertThat(body.at("/data/shippingMethodId").asText()).isEqualTo(methodId);
    }

    // ===== status transitions =====

    @Test
    void patch_validStatusProgression_succeedsAndSetsTimestamps() throws Exception {
        TestSetup setup = buildOrder();
        updateOrderStatus(setup.ctx().token(), setup.orderId(), "CONFIRMED");

        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"READY_TO_SHIP\"}"))
                .andExpect(status().isOk());

        updateOrderStatus(setup.ctx().token(), setup.orderId(), "PACKING");
        updateOrderStatus(setup.ctx().token(), setup.orderId(), "SHIPPING");

        MvcResult shippingResult = mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPING\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode shippingBody = json(shippingResult.getResponse().getContentAsString());
        assertThat(shippingBody.at("/data/shippedAt").isNull()).isFalse();

        updateOrderStatus(setup.ctx().token(), setup.orderId(), "DELIVERED");

        MvcResult deliveredResult = mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode deliveredBody = json(deliveredResult.getResponse().getContentAsString());
        assertThat(deliveredBody.at("/data/deliveredAt").isNull()).isFalse();
    }

    @Test
    void patch_invalidStatusJump_returns409_leavesStatusUnchanged() throws Exception {
        TestSetup setup = buildOrder();
        // First, an unrelated successful PATCH so the shipment row actually exists
        // (lazy-create and the requested status update share one transaction - a
        // rejected status update rolls back the whole PATCH, including a brand-new
        // lazy-created row, so this test must not rely on the rejected call having
        // created one).
        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"initial\"}"))
                .andExpect(status().isOk());

        // PENDING -> DELIVERED is not a valid direct jump.
        MvcResult result = mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);

        MvcResult detail = mockMvc.perform(get("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken()))
                .andReturn();
        assertThat(json(detail.getResponse().getContentAsString()).at("/data/status").asText())
                .isEqualTo("PENDING");
    }

    // ===== notification on shipment -> SHIPPING =====

    @Test
    void patch_toShipping_firesOrderShippingNotification() throws Exception {
        TestSetup setup = buildOrder();
        updateOrderStatus(setup.ctx().token(), setup.orderId(), "CONFIRMED");
        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY_TO_SHIP\"}"))
                .andExpect(status().isOk());
        updateOrderStatus(setup.ctx().token(), setup.orderId(), "PACKING");
        updateOrderStatus(setup.ctx().token(), setup.orderId(), "SHIPPING");
        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPING\"}"))
                .andExpect(status().isOk());

        boolean found = notificationRepository.findAll().stream()
                .anyMatch(n -> n.getType() == NotificationType.ORDER_SHIPPING
                        && n.getOrder() != null
                        && n.getOrder().getId().equals(UUID.fromString(setup.orderId())));
        assertThat(found).as("an ORDER_SHIPPING notification row must exist for this order").isTrue();
    }

    // ===== tracking number rules =====

    @Test
    void patch_trackingNumber_blockedOnTerminalStatus_returns409() throws Exception {
        TestSetup setup = buildOrder();
        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingNumber\":\"TRACK-XYZ\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void patch_trackingNumber_duplicateAcrossOrders_returns409() throws Exception {
        String trackingNumber = "DUP-" + UUID.randomUUID();
        TestSetup setupOne = buildOrder();
        mockMvc.perform(patch("/api/v1/admin/orders/" + setupOne.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setupOne.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingNumber\":\"" + trackingNumber + "\"}"))
                .andExpect(status().isOk());

        TestSetup setupTwo = buildOrder();
        MvcResult result = mockMvc.perform(patch("/api/v1/admin/orders/" + setupTwo.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setupTwo.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingNumber\":\"" + trackingNumber + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void patch_inactiveShippingMethod_returns422() throws Exception {
        TestSetup setup = buildOrder();
        String inactiveMethodId = createMethod("Old Method");
        ShippingMethod inactiveMethod = shippingMethodRepository.findById(UUID.fromString(inactiveMethodId)).orElseThrow();
        inactiveMethod.setStatus(ShippingMethodStatus.INACTIVE);
        shippingMethodRepository.save(inactiveMethod);

        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingMethodId\":\"" + inactiveMethodId + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void patch_shippingStatusBlockedWhileOrderStillPending_returns409() throws Exception {
        TestSetup setup = buildOrder();

        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPING\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patch_nonCancelledShipmentStatusBlockedWhenOrderCancelled_returns409() throws Exception {
        TestSetup setup = buildOrder();
        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.ctx().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY_TO_SHIP\"}"))
                .andExpect(status().isConflict());
    }

    // ===== order status and shipment status are independent state machines =====

    @Test
    void orderStatusAndShipmentStatus_areIndependent_updatingOneDoesNotMoveTheOther() throws Exception {
        TestSetup setup = buildOrder();

        for (String nextStatus : new String[] {"CONFIRMED", "PACKING", "SHIPPING"}) {
            mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.ctx().token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + nextStatus + "\"}"))
                    .andExpect(status().isOk());
        }

        // Order has reached its own SHIPPING status, but no shipment row was ever
        // touched via the shipping-specific endpoint - it must not exist yet.
        assertThat(shipmentRepository.findByOrderId(UUID.fromString(setup.orderId()))).isEmpty();

        MvcResult detail = mockMvc.perform(get("/api/v1/admin/orders/" + setup.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.ctx().token()))
                .andReturn();
        assertThat(json(detail.getResponse().getContentAsString()).at("/data/orderStatus").asText())
                .isEqualTo("SHIPPING");
    }

    // ===== GET detail =====

    @Test
    void get_noShipmentYet_returns404() throws Exception {
        TestSetup setup = buildOrder();
        mockMvc.perform(get("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken()))
                .andExpect(status().isNotFound());
    }

    // ===== role restriction =====

    @Test
    void salesStaff_cannotAccessShippingEndpoints_returns403() throws Exception {
        TestSetup setup = buildOrder();
        String salesToken = registerSalesStaffAndGetAccessToken(uniqueEmail("admship-sales"));
        mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannotAccessShippingEndpoints_returns403() throws Exception {
        TestSetup setup = buildOrder();
        TokenPair customer = registerUser(uniqueEmail("admship-customer"));
        mockMvc.perform(get("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ===== race: two concurrent first-PATCH calls on the same order must create exactly one shipment row =====

    @Test
    void concurrentFirstPatch_sameOrder_createsExactlyOneShipmentRow() throws Exception {
        TestSetup setup = buildOrder();

        Callable<Integer> patchCall = () -> mockMvc.perform(patch("/api/v1/admin/orders/" + setup.orderId() + "/shipping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + setup.warehouseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"concurrent\"}"))
                .andReturn()
                .getResponse()
                .getStatus();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = pool.invokeAll(List.of(patchCall, patchCall));
        pool.shutdown();
        for (Future<Integer> f : results) {
            assertThat(f.get()).isEqualTo(200);
        }

        List<Shipment> shipments = shipmentRepository.findAll().stream()
                .filter(s -> s.getOrder().getId().equals(UUID.fromString(setup.orderId())))
                .toList();
        assertThat(shipments).as("exactly one shipment row must exist for this order even under concurrent first-PATCH calls")
                .hasSize(1);
    }
}
