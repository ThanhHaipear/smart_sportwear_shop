package com.dunghaiquyen.ecommerce.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.inventory.repository.InventoryTransactionRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductVariantRepository;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class InventoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductVariantRepository variantRepository;

    @Autowired
    private InventoryTransactionRepository transactionRepository;

    @Autowired
    private AddressRepository addressRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("inv-admin"));
        String categorySlug = "inv-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "inv-brand-" + UUID.randomUUID();
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
        String slug = "inv-prod-" + UUID.randomUUID();
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

    private String registerWarehouseStaffAndGetAccessToken(String email) throws Exception {
        registerUser(email);
        var user = userRepository.findByEmail(email).orElseThrow();
        user.setRole(UserRole.WAREHOUSE_STAFF);
        userRepository.save(user);

        String body = "{\"email\":\"" + email + "\",\"password\":\"Password123\"}";
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();
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

    /** Checks out the buyer's whole cart (must already contain 1 VNPAY-or-COD-eligible item) and returns the order id. */
    private String createOrder(String token, String addressId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    // ===== manual import =====

    @Test
    void importStock_success_increasesStockAndLogsTransaction() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Import Jersey");
        String variantId = createVariant(ctx, productId, 100000, 10);

        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-import"));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/inventory/import")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":50,\"note\":\"Nhap lo moi\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/stockQuantity").asInt()).isEqualTo(60);
        assertThat(body.at("/data/reservedQuantity").asInt()).isEqualTo(0);
        assertThat(body.at("/data/availableQuantity").asInt()).isEqualTo(60);

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(60);

        var transactions = transactionRepository.findAll().stream()
                .filter(t -> t.getVariant().getId().equals(variant.getId()))
                .toList();
        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).getType().name()).isEqualTo("IMPORT");
        assertThat(transactions.get(0).getBeforeStockQuantity()).isEqualTo(10);
        assertThat(transactions.get(0).getAfterStockQuantity()).isEqualTo(60);
        assertThat(transactions.get(0).getOrder()).isNull();
    }

    @Test
    void importStock_nonPositiveQuantity_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Bad Import");
        String variantId = createVariant(ctx, productId, 50000, 5);
        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-badimport"));

        mockMvc.perform(post("/api/v1/admin/inventory/import")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ===== manual export =====

    @Test
    void exportStock_success_decreasesStockOnly() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Export Shorts");
        String variantId = createVariant(ctx, productId, 50000, 20);
        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-export"));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/inventory/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":5,\"note\":\"Xuat noi bo\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/stockQuantity").asInt()).isEqualTo(15);

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(15);
        assertThat(variant.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    void exportStock_insufficientStock_returns422_noPartialChange() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Scarce Cap");
        String variantId = createVariant(ctx, productId, 30000, 5);
        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-insuff"));

        long txCountBefore = transactionRepository.count();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/inventory/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":10}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .contains("Insufficient stock");

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(5);
        assertThat(transactionRepository.count())
                .as("a rejected export must not write a half-applied transaction row")
                .isEqualTo(txCountBefore);
    }

    @Test
    void exportStock_blockedByReservedQuantity_evenWhenRawStockWouldCoverIt() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Reserved Bag");
        String variantId = createVariant(ctx, productId, 80000, 10);

        // Reserve 8 of the 10 units via a real checkout, leaving only 2 available.
        String buyerEmail = uniqueEmail("inv-export-reserve-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 8);
        String addressId = createAddressForUser(buyerEmail);
        createOrder(buyer.accessToken(), addressId);

        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-reserved"));
        MvcResult result = mockMvc.perform(post("/api/v1/admin/inventory/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":5}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("raw stock is 10 but only 2 are available once 8 are reserved by a pending order")
                .isEqualTo(422);

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(10);
        assertThat(variant.getReservedQuantity()).isEqualTo(8);
    }

    // ===== manual adjustment =====

    @Test
    void adjustUp_success_increasesStock() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Adjust Up Belt");
        String variantId = createVariant(ctx, productId, 40000, 10);
        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-adjup"));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/inventory/adjust")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"type\":\"ADJUSTMENT_UP\",\"quantity\":3,\"note\":\"Kiem ke thua\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(result.getResponse().getContentAsString()).at("/data/stockQuantity").asInt())
                .isEqualTo(13);

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(13);
    }

    @Test
    void adjustDown_success_decreasesStock() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Adjust Down Hat");
        String variantId = createVariant(ctx, productId, 35000, 10);
        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-adjdown"));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/inventory/adjust")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"type\":\"ADJUSTMENT_DOWN\",\"quantity\":2,\"note\":\"Hang loi\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(result.getResponse().getContentAsString()).at("/data/stockQuantity").asInt())
                .isEqualTo(8);

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(8);
    }

    @Test
    void adjustDown_pastAvailableQuantity_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Overadjust Glove");
        String variantId = createVariant(ctx, productId, 45000, 5);
        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-overadjust"));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/inventory/adjust")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"type\":\"ADJUSTMENT_DOWN\",\"quantity\":9}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(5);
    }

    @Test
    void adjust_invalidType_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Wrong Type Sock");
        String variantId = createVariant(ctx, productId, 15000, 10);
        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-wrongtype"));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/inventory/adjust")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"type\":\"IMPORT\",\"quantity\":1}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(10);
    }

    // ===== confirm order deduct flow (I5) =====

    @Test
    void confirmOrder_deductsStockAndLogsOrderConfirmDeduct() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Confirm Sneakers");
        String variantId = createVariant(ctx, productId, 200000, 10);

        String buyerEmail = uniqueEmail("inv-confirm-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 4);
        String addressId = createAddressForUser(buyerEmail);
        String orderId = createOrder(buyer.accessToken(), addressId);

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk());

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(6);
        assertThat(variant.getReservedQuantity()).isEqualTo(0);

        MvcResult history = mockMvc.perform(get("/api/v1/admin/inventory/transactions?variantId=" + variantId
                        + "&type=ORDER_CONFIRM_DEDUCT")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        assertThat(history.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = json(history.getResponse().getContentAsString()).at("/data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).at("/orderId").asText()).isEqualTo(orderId);
        assertThat(data.get(0).at("/quantity").asInt()).isEqualTo(4);
    }

    // ===== cancel order release flow (I6) =====

    @Test
    void cancelOrder_releasesReservedQuantityAndLogsOrderRelease() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Cancel Watch");
        String variantId = createVariant(ctx, productId, 250000, 10);

        String buyerEmail = uniqueEmail("inv-cancel-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 3);
        String addressId = createAddressForUser(buyerEmail);
        String orderId = createOrder(buyer.accessToken(), addressId);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk());

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getReservedQuantity()).isEqualTo(0);
        assertThat(variant.getStockQuantity()).isEqualTo(10);

        MvcResult history = mockMvc.perform(get("/api/v1/admin/inventory/transactions?variantId=" + variantId
                        + "&type=ORDER_RELEASE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        JsonNode data = json(history.getResponse().getContentAsString()).at("/data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).at("/quantity").asInt()).isEqualTo(3);
    }

    // ===== regression: concurrent confirm calls on the same order must not double-deduct =====

    @Test
    void concurrentConfirmCalls_onSameOrder_deductsStockExactlyOnce() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Race Jacket");
        String variantId = createVariant(ctx, productId, 300000, 10);

        String buyerEmail = uniqueEmail("inv-race-confirm-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 5);
        String addressId = createAddressForUser(buyerEmail);
        String orderId = createOrder(buyer.accessToken(), addressId);

        Callable<Integer> confirmCall = () -> mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andReturn()
                .getResponse()
                .getStatus();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = pool.invokeAll(List.of(confirmCall, confirmCall));
        pool.shutdown();

        int successCount = 0;
        for (Future<Integer> f : results) {
            int statusCode = f.get();
            assertThat(statusCode).as("loser must fail in a controlled way, never 500").isIn(200, 409);
            if (statusCode == 200) {
                successCount++;
            }
        }
        assertThat(successCount).as("exactly one of the two concurrent confirms must succeed").isEqualTo(1);

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity())
                .as("stock must be deducted exactly once (5), never twice (0)")
                .isEqualTo(5);
        assertThat(variant.getReservedQuantity()).isEqualTo(0);

        long confirmDeductCount = transactionRepository.findAll().stream()
                .filter(t -> t.getVariant().getId().equals(variant.getId()))
                .filter(t -> t.getType().name().equals("ORDER_CONFIRM_DEDUCT"))
                .count();
        assertThat(confirmDeductCount).as("exactly one ORDER_CONFIRM_DEDUCT row, not two").isEqualTo(1);
    }

    // ===== regression: concurrent cancel calls on the same order must not double-release =====

    @Test
    void concurrentCancelCalls_onSameOrder_releasesReservedQuantityExactlyOnce() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Race Beanie");
        String variantId = createVariant(ctx, productId, 90000, 10);

        String buyerEmail = uniqueEmail("inv-race-cancel-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 4);
        String addressId = createAddressForUser(buyerEmail);
        String orderId = createOrder(buyer.accessToken(), addressId);

        Callable<Integer> cancelCall = () -> mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andReturn()
                .getResponse()
                .getStatus();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = pool.invokeAll(List.of(cancelCall, cancelCall));
        pool.shutdown();

        for (Future<Integer> f : results) {
            int statusCode = f.get();
            assertThat(statusCode).as("repeated cancel is idempotent, never 500").isEqualTo(200);
        }

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getReservedQuantity())
                .as("reserved must be released exactly once (0), never go negative")
                .isEqualTo(0);

        long releaseCount = transactionRepository.findAll().stream()
                .filter(t -> t.getVariant().getId().equals(variant.getId()))
                .filter(t -> t.getType().name().equals("ORDER_RELEASE"))
                .count();
        assertThat(releaseCount).as("exactly one ORDER_RELEASE row, not two").isEqualTo(1);
    }

    // ===== inventory list filters =====

    @Test
    void inventoryList_filtersByKeywordProductCategoryBrandAndStatus() throws Exception {
        AdminContext ctx = setUpAdmin();
        String uniqueKeyword = "Filterable-" + UUID.randomUUID();
        String productId1 = createActiveProduct(ctx, uniqueKeyword + " Hoodie");
        String variantId1 = createVariant(ctx, productId1, 120000, 8);
        String productId2 = createActiveProduct(ctx, "Other Pants");
        String variantId2 = createVariant(ctx, productId2, 60000, 3);

        mockMvc.perform(patch("/api/v1/admin/variants/" + variantId2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-list"));

        MvcResult byKeyword = mockMvc.perform(get("/api/v1/admin/inventory?keyword=" + uniqueKeyword)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andReturn();
        JsonNode keywordData = json(byKeyword.getResponse().getContentAsString()).at("/data");
        assertThat(keywordData).hasSize(1);
        assertThat(keywordData.get(0).at("/variantId").asText()).isEqualTo(variantId1);

        MvcResult byProduct = mockMvc.perform(get("/api/v1/admin/inventory?productId=" + productId2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andReturn();
        JsonNode productData = json(byProduct.getResponse().getContentAsString()).at("/data");
        assertThat(productData).hasSize(1);
        assertThat(productData.get(0).at("/variantId").asText()).isEqualTo(variantId2);

        MvcResult byCategory = mockMvc.perform(get("/api/v1/admin/inventory?categoryId=" + ctx.categoryId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andReturn();
        assertThat(json(byCategory.getResponse().getContentAsString()).at("/data")).hasSize(2);

        MvcResult byBrand = mockMvc.perform(get("/api/v1/admin/inventory?brandId=" + ctx.brandId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andReturn();
        assertThat(json(byBrand.getResponse().getContentAsString()).at("/data")).hasSize(2);

        // Combined with categoryId (unique to this test's own category) so this is
        // not polluted by INACTIVE variants other test classes created earlier in
        // the same shared schema (tests do not truncate between each other, only
        // between full suite runs).
        MvcResult byStatus = mockMvc.perform(get("/api/v1/admin/inventory?status=INACTIVE&categoryId="
                        + ctx.categoryId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andReturn();
        JsonNode statusData = json(byStatus.getResponse().getContentAsString()).at("/data");
        assertThat(statusData).hasSize(1);
        assertThat(statusData.get(0).at("/variantId").asText()).isEqualTo(variantId2);
    }

    // ===== inventory transaction history filters =====

    @Test
    void inventoryTransactionHistory_filtersByVariantTypeAndDateRange() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId1 = createActiveProduct(ctx, "History Variant One");
        String variantId1 = createVariant(ctx, productId1, 70000, 10);
        String productId2 = createActiveProduct(ctx, "History Variant Two");
        String variantId2 = createVariant(ctx, productId2, 55000, 10);
        String warehouseToken = registerWarehouseStaffAndGetAccessToken(uniqueEmail("inv-warehouse-history"));

        mockMvc.perform(post("/api/v1/admin/inventory/import")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId1 + "\",\"quantity\":5}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/inventory/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId1 + "\",\"quantity\":2}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/inventory/import")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId2 + "\",\"quantity\":9}"))
                .andExpect(status().isCreated());

        // Filter by variantId: only variant1's 2 transactions (IMPORT + EXPORT).
        MvcResult byVariant = mockMvc.perform(get("/api/v1/admin/inventory/transactions?variantId=" + variantId1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andReturn();
        assertThat(json(byVariant.getResponse().getContentAsString()).at("/data")).hasSize(2);

        // Filter by type: combined with variantId so this is not polluted by IMPORT
        // rows other tests created earlier in the same shared schema (tests do not
        // truncate between each other, only between full suite runs).
        MvcResult byType = mockMvc.perform(get("/api/v1/admin/inventory/transactions?type=IMPORT&variantId="
                        + variantId1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andReturn();
        assertThat(json(byType.getResponse().getContentAsString()).at("/data")).hasSize(1);

        // Filter by date range covering today, scoped to variant1: its 2 rows show up.
        String today = java.time.LocalDate.now().toString();
        MvcResult byDate = mockMvc.perform(get("/api/v1/admin/inventory/transactions?variantId=" + variantId1
                        + "&dateFrom=" + today + "&dateTo=" + today)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andReturn();
        assertThat(json(byDate.getResponse().getContentAsString()).at("/data")).hasSize(2);

        // A date range entirely in the past must exclude everything, even scoped to variant1.
        MvcResult byPastDate = mockMvc.perform(get("/api/v1/admin/inventory/transactions?variantId=" + variantId1
                        + "&dateFrom=2000-01-01&dateTo=2000-01-02")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andReturn();
        assertThat(json(byPastDate.getResponse().getContentAsString()).at("/data")).isEmpty();
    }

    // ===== role gate =====

    @Test
    void customer_cannotAccessInventoryEndpoints_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("inv-not-staff"));
        mockMvc.perform(get("/api/v1/admin/inventory")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }
}
