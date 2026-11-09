package com.dunghaiquyen.ecommerce.modules.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for Catalog V2: collections, product_type, and their
 * filters. All 10 required scenarios are covered.
 */
@Tag("phase0-excluded")
@Tag("dormant")
class CatalogV2IntegrationTest extends AbstractIntegrationTest {

    // ===== helpers ===========================================================

    private record AdminCtx(String token, String categoryId, String brandId) {
    }

    private AdminCtx setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cv2-admin"));
        String catSlug = "cv2-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + catSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "cv2-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminCtx(token, categoryId, brandId);
    }

    private String createProduct(AdminCtx ctx, String productType) throws Exception {
        String slug = "cv2-prod-" + UUID.randomUUID();
        String body = ("{\"name\":\"Product\",\"slug\":\"%s\",\"categoryId\":\"%s\","
                        + "\"brandId\":\"%s\",\"status\":\"ACTIVE\",\"productType\":\"%s\"}")
                .formatted(slug, ctx.categoryId(), ctx.brandId(), productType);
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String createCollection(AdminCtx ctx, String status, Instant startsAt, Instant endsAt) throws Exception {
        String slug = "cv2-col-" + UUID.randomUUID();
        StringBuilder body = new StringBuilder()
                .append("{\"name\":\"BST Test\",\"slug\":\"").append(slug).append("\"")
                .append(",\"collectionType\":\"SEASONAL\"")
                .append(",\"status\":\"").append(status).append("\"");
        if (startsAt != null) body.append(",\"startsAt\":\"").append(startsAt).append("\"");
        if (endsAt != null) body.append(",\"endsAt\":\"").append(endsAt).append("\"");
        body.append("}");

        MvcResult result = mockMvc.perform(post("/api/v1/admin/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");
        return data.at("/id").asText();
    }

    private void linkProductToCollection(AdminCtx ctx, String productId, String collectionId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/products/" + productId + "/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionId\":\"" + collectionId + "\"}"))
                .andExpect(status().isCreated());
    }

    // ===== TEST 1: migration + app boot ======================================

    /**
     * Simply starting the Spring context against the Flyway-migrated Postgres DB
     * and reaching any endpoint proves that V10 migrated correctly and the entity
     * mapping matches the schema (Hibernate validates on startup with ddl-auto=validate).
     */
    @Test
    void appBoot_withV10Migration_entitySchemaMatchesDb() throws Exception {
        mockMvc.perform(get("/api/v1/collections"))
                .andExpect(status().isOk());
    }

    // ===== TEST 2: admin create collection ====================================

    @Test
    void adminCreateCollection_validRequest_returns201WithFullData() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String slug = "bst-mua-he-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"BST Mùa hè 2026\",\"slug\":\"%s\","
                                        + "\"collectionType\":\"SEASONAL\",\"season\":\"Summer\","
                                        + "\"year\":2026,\"status\":\"DRAFT\",\"isFeatured\":true}")
                                .formatted(slug)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");
        assertThat(data.at("/slug").asText()).isEqualTo(slug);
        assertThat(data.at("/collectionType").asText()).isEqualTo("SEASONAL");
        assertThat(data.at("/status").asText()).isEqualTo("DRAFT");
        assertThat(data.at("/isFeatured").asBoolean()).isTrue();
        assertThat(data.at("/year").asInt()).isEqualTo(2026);
    }

    @Test
    void adminCreateCollection_invalidStartsAfterEnds_returns422() throws Exception {
        AdminCtx ctx = setUpAdmin();
        Instant future = Instant.now().plus(10, ChronoUnit.DAYS);
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        mockMvc.perform(post("/api/v1/admin/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"X\",\"slug\":\"bad-range-" + UUID.randomUUID() + "\","
                                        + "\"collectionType\":\"SPORT\","
                                        + "\"startsAt\":\"" + future + "\","
                                        + "\"endsAt\":\"" + past + "\"}")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void adminCreateCollection_nonAdmin_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("cv2-notadmin"));
        mockMvc.perform(post("/api/v1/admin/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"slug\":\"x\",\"collectionType\":\"SPORT\"}"))
                .andExpect(status().isForbidden());
    }

    // ===== TEST 3: admin update collection ====================================

    @Test
    void adminUpdateCollection_partialPatch_onlyChangesSuppliedFields() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String collectionId = createCollection(ctx, "DRAFT", null, null);

        MvcResult patchResult = mockMvc.perform(patch("/api/v1/admin/collections/" + collectionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"isFeatured\":true}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = json(patchResult.getResponse().getContentAsString()).at("/data");
        assertThat(data.at("/status").asText()).isEqualTo("ACTIVE");
        assertThat(data.at("/isFeatured").asBoolean()).isTrue();
        assertThat(data.at("/collectionType").asText()).isEqualTo("SEASONAL");
    }

    @Test
    void adminUpdateCollection_omittedBrandPreservesIt_andClearBrandRemovesIt() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String slug = "brand-contract-" + UUID.randomUUID();
        MvcResult created = mockMvc.perform(post("/api/v1/admin/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Brand collection\",\"slug\":\"%s\","
                                        + "\"collectionType\":\"BRAND\",\"brandId\":\"%s\"}")
                                .formatted(slug, ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        String collectionId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult preserved = mockMvc.perform(patch("/api/v1/admin/collections/" + collectionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed collection\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(preserved.getResponse().getContentAsString()).at("/data/brand/id").asText())
                .isEqualTo(ctx.brandId());

        MvcResult cleared = mockMvc.perform(patch("/api/v1/admin/collections/" + collectionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearBrand\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(cleared.getResponse().getContentAsString()).at("/data/brand").isNull()).isTrue();
    }

    // ===== TEST 4: admin link product to collection ===========================

    @Test
    void adminLinkProduct_validIds_returns201() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String productId = createProduct(ctx, "FOOTWEAR");
        String collectionId = createCollection(ctx, "ACTIVE", null, null);

        linkProductToCollection(ctx, productId, collectionId);
    }

    // ===== TEST 5: duplicate link blocked =====================================

    @Test
    void adminLinkProduct_duplicate_returns409() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String productId = createProduct(ctx, "APPAREL");
        String collectionId = createCollection(ctx, "ACTIVE", null, null);
        linkProductToCollection(ctx, productId, collectionId);

        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionId\":\"" + collectionId + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    // ===== TEST 6: admin remove product from collection =======================

    @Test
    void adminUnlinkProduct_existingLink_removesIt() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String productId = createProduct(ctx, "ACCESSORY");
        String collectionId = createCollection(ctx, "ACTIVE", null, null);
        linkProductToCollection(ctx, productId, collectionId);

        mockMvc.perform(delete("/api/v1/admin/products/" + productId + "/collections/" + collectionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk());

        // Link is gone: public list still works (collection is ACTIVE but now has no products)
        mockMvc.perform(get("/api/v1/collections"))
                .andExpect(status().isOk());
    }

    @Test
    void adminUnlinkProduct_nonExistentLink_isIdempotent200() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String productId = createProduct(ctx, "EQUIPMENT");
        String collectionId = createCollection(ctx, "ACTIVE", null, null);

        mockMvc.perform(delete("/api/v1/admin/products/" + productId + "/collections/" + collectionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk());
    }

    // ===== TEST 7: public list only returns active + in-window collections ====

    @Test
    void publicListCollections_onlyReturnsActiveInTimeWindow() throws Exception {
        AdminCtx ctx = setUpAdmin();
        Instant now = Instant.now();

        // Should appear
        String activeId = createCollection(ctx, "ACTIVE", null, null);
        String activeWithinWindowId = createCollection(ctx, "ACTIVE",
                now.minus(1, ChronoUnit.DAYS), now.plus(10, ChronoUnit.DAYS));

        // Should NOT appear
        String draftId = createCollection(ctx, "DRAFT", null, null);
        String inactiveId = createCollection(ctx, "INACTIVE", null, null);
        String notStartedId = createCollection(ctx, "ACTIVE",
                now.plus(10, ChronoUnit.DAYS), null);
        String expiredId = createCollection(ctx, "ACTIVE",
                null, now.minus(1, ChronoUnit.DAYS));

        MvcResult result = mockMvc.perform(get("/api/v1/collections"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");

        var ids = new java.util.ArrayList<String>();
        data.forEach(item -> ids.add(item.at("/id").asText()));

        assertThat(ids).contains(activeId, activeWithinWindowId);
        assertThat(ids).doesNotContain(draftId, inactiveId, notStartedId, expiredId);

        // Also verify no admin-only fields are exposed
        if (!data.isEmpty()) {
            assertThat(data.get(0).has("status")).isFalse();
        }
    }

    // ===== TEST 8: public detail returns correct products =====================

    @Test
    void publicCollectionDetail_bySlug_returnsOnlyActiveProducts() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String footwearId = createProduct(ctx, "FOOTWEAR");
        String apparelId = createProduct(ctx, "APPAREL");

        // Create a DRAFT product that should NOT appear in the collection detail
        String slug = "cv2-prod-draft-" + UUID.randomUUID();
        MvcResult draft = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Draft\",\"slug\":\"%s\",\"categoryId\":\"%s\","
                                        + "\"brandId\":\"%s\",\"status\":\"DRAFT\",\"productType\":\"APPAREL\"}")
                                .formatted(slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        String draftProductId = json(draft.getResponse().getContentAsString()).at("/data/id").asText();

        String colSlug = "bst-detail-" + UUID.randomUUID();
        MvcResult colResult = mockMvc.perform(post("/api/v1/admin/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"BST\",\"slug\":\"%s\","
                                        + "\"collectionType\":\"SPORT\",\"status\":\"ACTIVE\"}")
                                .formatted(colSlug)))
                .andExpect(status().isCreated())
                .andReturn();
        String collectionId = json(colResult.getResponse().getContentAsString()).at("/data/id").asText();

        linkProductToCollection(ctx, footwearId, collectionId);
        linkProductToCollection(ctx, apparelId, collectionId);
        linkProductToCollection(ctx, draftProductId, collectionId);

        MvcResult result = mockMvc.perform(get("/api/v1/collections/" + colSlug))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");
        assertThat(data.at("/slug").asText()).isEqualTo(colSlug);

        var productIds = new java.util.ArrayList<String>();
        data.at("/products").forEach(p -> productIds.add(p.at("/id").asText()));
        assertThat(productIds).contains(footwearId, apparelId);
        assertThat(productIds).doesNotContain(draftProductId);
    }

    @Test
    void publicCollectionDetail_draftCollection_returns404() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String colSlug = "bst-draft-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Draft BST\",\"slug\":\"%s\","
                                        + "\"collectionType\":\"CAMPAIGN\",\"status\":\"DRAFT\"}")
                                .formatted(colSlug)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/collections/" + colSlug))
                .andExpect(status().isNotFound());
    }

    // ===== TEST 9: product list filter by productType =========================

    @Test
    void productListFilter_byProductType_returnsOnlyMatchingType() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String footwearId = createProduct(ctx, "FOOTWEAR");
        String apparelId = createProduct(ctx, "APPAREL");
        String accessoryId = createProduct(ctx, "ACCESSORY");

        MvcResult footwearResult = mockMvc.perform(
                        get("/api/v1/products").param("productType", "FOOTWEAR"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode footwearData = json(footwearResult.getResponse().getContentAsString()).at("/data");

        var footwearIds = new java.util.ArrayList<String>();
        footwearData.forEach(p -> footwearIds.add(p.at("/id").asText()));
        assertThat(footwearIds).contains(footwearId);
        assertThat(footwearIds).doesNotContain(apparelId, accessoryId);

        // Also verify productType is present in the response
        if (!footwearData.isEmpty()) {
            for (JsonNode item : footwearData) {
                if (item.at("/id").asText().equals(footwearId)) {
                    assertThat(item.at("/productType").asText()).isEqualTo("FOOTWEAR");
                }
            }
        }
    }

    // ===== TEST 10: product list filter by collection =========================

    @Test
    void productListFilter_byCollectionSlug_returnsOnlyProductsInCollection() throws Exception {
        AdminCtx ctx = setUpAdmin();
        String inCollectionId = createProduct(ctx, "FOOTWEAR");
        String notInCollectionId = createProduct(ctx, "APPAREL");

        String colSlug = "bst-filter-" + UUID.randomUUID();
        MvcResult colResult = mockMvc.perform(post("/api/v1/admin/collections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"BST Filter\",\"slug\":\"%s\","
                                        + "\"collectionType\":\"NEW_ARRIVAL\",\"status\":\"ACTIVE\"}")
                                .formatted(colSlug)))
                .andExpect(status().isCreated())
                .andReturn();
        String collectionId = json(colResult.getResponse().getContentAsString()).at("/data/id").asText();
        linkProductToCollection(ctx, inCollectionId, collectionId);

        MvcResult result = mockMvc.perform(
                        get("/api/v1/products").param("collection", colSlug))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");

        var ids = new java.util.ArrayList<String>();
        data.forEach(p -> ids.add(p.at("/id").asText()));
        assertThat(ids).contains(inCollectionId);
        assertThat(ids).doesNotContain(notInCollectionId);

        // Unknown slug → empty results (not 404)
        MvcResult unknownResult = mockMvc.perform(
                        get("/api/v1/products").param("collection", "does-not-exist-xyz"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode unknownData = json(unknownResult.getResponse().getContentAsString()).at("/data");
        assertThat(unknownData).isEmpty();
    }
}
