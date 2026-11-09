package com.dunghaiquyen.ecommerce.modules.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ProductSearchIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    private record AdminContext(String token, String categoryId, String categorySlug, String brandId, String brandSlug) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("search-admin"));
        String categorySlug = "search-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat " + categorySlug.substring(categorySlug.length() - 8)
                                + "\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "search-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand " + brandSlug.substring(brandSlug.length() - 8)
                                + "\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, categorySlug, brandId, brandSlug);
    }

    private AdminContext setUpSecondCategoryAndBrand(AdminContext ctx) throws Exception {
        String categorySlug = "search-cat2-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat2 " + categorySlug.substring(categorySlug.length() - 8)
                                + "\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "search-brand2-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand2 " + brandSlug.substring(brandSlug.length() - 8)
                                + "\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(ctx.token(), categoryId, categorySlug, brandId, brandSlug);
    }

    private String createActiveProduct(AdminContext ctx, String name) throws Exception {
        return createActiveProduct(ctx, name, 100000);
    }

    private String createActiveProduct(AdminContext ctx, String name, int price) throws Exception {
        String slug = "search-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        createVariant(ctx, productId, price);
        makeProductPublishableForTest(productId);
        return productId;
    }

    private String createVariant(AdminContext ctx, String productId, int price) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"SKU-%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":%d,\"stockQuantity\":50}")
                                .formatted(UUID.randomUUID(), price)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String firstVariantId(String productId) {
        return jdbcTemplate.queryForObject(
                "select id::text from product_variants where product_id = ? order by created_at limit 1",
                String.class,
                UUID.fromString(productId));
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

    /** Buys `quantity` units of variantId, leaving the order at PENDING_CONFIRMATION (counts as "sold" per the bestselling rule). */
    private void buy(String variantId, int quantity) throws Exception {
        String email = uniqueEmail("search-buy");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, quantity);
        String addressId = createAddressForUser(email);
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated());
    }

    /** Buys then immediately cancels - these units must NOT count towards bestselling. */
    private void buyThenCancel(String variantId, int quantity) throws Exception {
        String email = uniqueEmail("search-cancelbuy");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, quantity);
        String addressId = createAddressForUser(email);
        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk());
    }

    // ===== search by keyword (q) =====

    @Test
    void search_byKeyword_findsMatchingProductOnly() throws Exception {
        AdminContext ctx = setUpAdmin();
        String uniqueWord = "Zynthex" + UUID.randomUUID().toString().substring(0, 8);
        createActiveProduct(ctx, uniqueWord + " Running Shoes");
        createActiveProduct(ctx, "Tennis Racket Beta");

        MvcResult result = mockMvc.perform(get("/api/v1/products").param("q", uniqueWord))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/name").asText()).contains(uniqueWord);
    }

    // ===== filter by category (categorySlug) =====

    @Test
    void filter_byCategorySlug_returnsOnlyThatCategory() throws Exception {
        AdminContext ctx = setUpAdmin();
        AdminContext ctx2 = setUpSecondCategoryAndBrand(ctx);
        String productInCat1 = createActiveProduct(ctx, "Cat1 Product");
        createActiveProduct(ctx2, "Cat2 Product");

        MvcResult result = mockMvc.perform(get("/api/v1/products").param("categorySlug", ctx.categorySlug()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/id").asText()).isEqualTo(productInCat1);
    }

    @Test
    void filter_byUnknownCategorySlug_returnsEmptyNotError() throws Exception {
        setUpAdmin();
        mockMvc.perform(get("/api/v1/products").param("categorySlug", "no-such-category-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").isEmpty());
    }

    // ===== filter by brand (brandSlug) =====

    @Test
    void filter_byBrandSlug_returnsOnlyThatBrand() throws Exception {
        AdminContext ctx = setUpAdmin();
        AdminContext ctx2 = setUpSecondCategoryAndBrand(ctx);
        String productBrand1 = createActiveProduct(ctx, "Brand1 Product");
        createActiveProduct(ctx2, "Brand2 Product");

        MvcResult result = mockMvc.perform(get("/api/v1/products").param("brandSlug", ctx.brandSlug()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/id").asText()).isEqualTo(productBrand1);
    }

    // ===== filter by price range =====

    @Test
    void filter_byPriceRange_excludesOutOfRangeProducts() throws Exception {
        AdminContext ctx = setUpAdmin();
        String cheapId = createActiveProduct(ctx, "Cheap Item", 50000);
        String midId = createActiveProduct(ctx, "Mid Item", 150000);
        String expensiveId = createActiveProduct(ctx, "Expensive Item", 500000);

        MvcResult result = mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", ctx.categoryId())
                        .param("minPrice", "100000")
                        .param("maxPrice", "200000"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/id").asText()).isEqualTo(midId);
    }

    // ===== sort price asc / desc =====

    @Test
    void sort_priceAscAndDesc_ordersCorrectly() throws Exception {
        AdminContext ctx = setUpAdmin();
        String lowId = createActiveProduct(ctx, "Low Price", 30000);
        String highId = createActiveProduct(ctx, "High Price", 900000);

        MvcResult asc = mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", ctx.categoryId())
                        .param("sort", "price_asc"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode ascBody = json(asc.getResponse().getContentAsString());
        assertThat(ascBody.at("/data/0/id").asText()).isEqualTo(lowId);

        MvcResult desc = mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", ctx.categoryId())
                        .param("sort", "price_desc"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode descBody = json(desc.getResponse().getContentAsString());
        assertThat(descBody.at("/data/0/id").asText()).isEqualTo(highId);
    }

    // ===== sort bestselling (excludes CANCELLED) =====

    @Test
    void sort_bestselling_ranksByNonCancelledQuantitySold() throws Exception {
        AdminContext ctx = setUpAdmin();
        String bestId = createActiveProduct(ctx, "Best Seller");
        String bestVariant = firstVariantId(bestId);
        String worstId = createActiveProduct(ctx, "Worst Seller");
        String worstVariant = firstVariantId(worstId);
        String cancelledOnlyId = createActiveProduct(ctx, "Cancelled Only Seller");
        String cancelledVariant = firstVariantId(cancelledOnlyId);

        buy(bestVariant, 5);
        buy(worstVariant, 1);
        buyThenCancel(cancelledVariant, 40);

        MvcResult result = mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", ctx.categoryId())
                        .param("sort", "bestselling"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());

        int bestIndex = -1;
        int worstIndex = -1;
        int cancelledIndex = -1;
        for (int i = 0; i < body.at("/data").size(); i++) {
            String id = body.at("/data/" + i + "/id").asText();
            if (id.equals(bestId)) bestIndex = i;
            if (id.equals(worstId)) worstIndex = i;
            if (id.equals(cancelledOnlyId)) cancelledIndex = i;
        }
        assertThat(bestIndex).isGreaterThanOrEqualTo(0);
        assertThat(worstIndex).isGreaterThan(bestIndex);
        assertThat(cancelledIndex).isGreaterThan(worstIndex);
    }

    // ===== pagination =====

    @Test
    void pagination_returnsCorrectMetaAndPageSlice() throws Exception {
        AdminContext ctx = setUpAdmin();
        createActiveProduct(ctx, "Page Item 1");
        createActiveProduct(ctx, "Page Item 2");
        createActiveProduct(ctx, "Page Item 3");

        MvcResult result = mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", ctx.categoryId())
                        .param("limit", "2")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(2);
        assertThat(body.at("/meta/page").asInt()).isEqualTo(1);
        assertThat(body.at("/meta/limit").asInt()).isEqualTo(2);
        assertThat(body.at("/meta/total").asInt()).isEqualTo(3);
        assertThat(body.at("/meta/totalPages").asInt()).isEqualTo(2);

        MvcResult page2 = mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", ctx.categoryId())
                        .param("limit", "2")
                        .param("page", "2"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page2Body = json(page2.getResponse().getContentAsString());
        assertThat(page2Body.at("/data")).hasSize(1);
    }

    // ===== search suggestions =====

    @Test
    void searchSuggestions_returnsLightweightMatches() throws Exception {
        AdminContext ctx = setUpAdmin();
        String uniqueWord = "Suggesto" + UUID.randomUUID().toString().substring(0, 8);
        String productId = createActiveProduct(ctx, uniqueWord + " Jacket");

        MvcResult result = mockMvc.perform(get("/api/v1/products/search-suggestions").param("q", uniqueWord))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/id").asText()).isEqualTo(productId);
        assertThat(body.at("/data/0/slug").asText()).isNotBlank();
    }

    @Test
    void searchSuggestions_blankQuery_returnsEmptyList_notError() throws Exception {
        mockMvc.perform(get("/api/v1/products/search-suggestions"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").isEmpty());
    }

    // ===== invalid input -> clean error, not 500 =====

    @Test
    void invalidInput_minPriceGreaterThanMaxPrice_returns422() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("minPrice", "500000").param("maxPrice", "100000"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidInput_negativeMinPrice_returns422() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("minPrice", "-1"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidInput_unknownSortValue_returns422() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("sort", "not-a-real-sort"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidInput_malformedCategoryIdUuid_returns422CleanFieldError_notServerError() throws Exception {
        // Spring's @ModelAttribute binder reports a type-conversion failure as a
        // field-level bind error, which the existing MethodArgumentNotValidException
        // handler already turns into a clean 422 with a field error - same shape
        // as every other validation error in this app, not a raw 500.
        mockMvc.perform(get("/api/v1/products").param("categoryId", "not-a-uuid"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.errors[0].field").value("categoryId"));
    }
}
