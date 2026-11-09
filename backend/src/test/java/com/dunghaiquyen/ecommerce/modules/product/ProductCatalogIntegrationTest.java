package com.dunghaiquyen.ecommerce.modules.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ProductCatalogIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    private String createCategory(String token) throws Exception {
        String slug = "cat-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat " + slug.substring(slug.length() - 8)
                                + "\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String createBrand(String token) throws Exception {
        String slug = "brand-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand " + slug.substring(slug.length() - 8)
                                + "\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdminWithCatalogRefs() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("product-admin"));
        return new AdminContext(token, createCategory(token), createBrand(token));
    }

    @Test
    void create_withValidRefs_defaultsToDraftAndNotFeatured() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String slug = "prod-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Shirt\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.isFeatured").value(false))
                .andExpect(jsonPath("$.data.category.id").value(ctx.categoryId()))
                .andExpect(jsonPath("$.data.brand.id").value(ctx.brandId()));
    }

    @Test
    void create_withUnknownCategoryId_returns404() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Shirt\",\"slug\":\"prod-%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(UUID.randomUUID(), UUID.randomUUID(), ctx.brandId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Category not found"));
    }

    @Test
    void create_duplicateSlug_returns409() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String slug = "prod-dup-" + UUID.randomUUID();
        String body = ("{\"name\":\"Shirt\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                .formatted(slug, ctx.categoryId(), ctx.brandId());

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void draftProduct_isNotVisiblePublicly() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String slug = "prod-draft-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Shirt\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/products/" + slug))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicDetail_bySlugThatLooksLikeAUuid_fallsBackToSlugLookup() throws Exception {
        // Patterns.SLUG accepts UUID-shaped strings too, so a slug like this is
        // legal. findVisibleProduct used to parse it as a UUID, fail the id
        // lookup, and 404 immediately instead of falling back to slug lookup.
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String uuidLookingSlug = UUID.randomUUID().toString();
        String productId = createActiveProduct(ctx, uuidLookingSlug);
        assertThat(productId).isNotEqualTo(uuidLookingSlug);

        mockMvc.perform(get("/api/v1/products/" + uuidLookingSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(productId))
                .andExpect(jsonPath("$.data.slug").value(uuidLookingSlug));
    }

    @Test
    void update_blankName_returns422_butNullFieldsLeaveOthersUnchanged() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String productId = createDraftProduct(ctx);

        mockMvc.perform(patch("/api/v1/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        // omitting name (null) must still work and leave it unchanged - PATCH semantics.
        mockMvc.perform(patch("/api/v1/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shortDescription\":\"Updated desc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Shirt"))
                .andExpect(jsonPath("$.data.shortDescription").value("Updated desc"));
    }

    @Test
    void variant_duplicateSku_returns409_andInvalidPriceReturns422() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String productId = createDraftProduct(ctx);
        String sku = "SKU-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":100000}").formatted(sku)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"%s\",\"size\":\"L\",\"color\":\"White\",\"price\":120000}").formatted(sku)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("SKU already exists: " + sku));

        mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-ZERO-" + UUID.randomUUID() + "\",\"size\":\"S\",\"color\":\"Red\",\"price\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    @Test
    void variantUpdate_cannotMutateStock_evenIfClientSendsTheField() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String productId = createDraftProduct(ctx);
        String sku = "SKU-" + UUID.randomUUID();

        MvcResult created = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":100000,\"stockQuantity\":20}")
                                .formatted(sku)))
                .andExpect(status().isCreated())
                .andReturn();
        String variantId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        // VariantUpdateRequest has no stockQuantity property at all - Jackson silently
        // ignores the unknown field, it never reaches the entity.
        mockMvc.perform(patch("/api/v1/admin/variants/" + variantId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":90000,\"stockQuantity\":999999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(90000))
                .andExpect(jsonPath("$.data.availableQuantity").value(20));
    }

    @Test
    void variantUpdate_blankSizeOrColor_returns422() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String productId = createDraftProduct(ctx);
        MvcResult created = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-" + UUID.randomUUID() + "\",\"size\":\"M\",\"color\":\"Black\",\"price\":100000}"))
                .andExpect(status().isCreated())
                .andReturn();
        String variantId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/admin/variants/" + variantId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"size\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("size"));

        mockMvc.perform(patch("/api/v1/admin/variants/" + variantId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("color"));

        // null/omitted fields still pass and leave size/color unchanged.
        mockMvc.perform(patch("/api/v1/admin/variants/" + variantId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":120000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value("M"))
                .andExpect(jsonPath("$.data.color").value("Đen"))
                .andExpect(jsonPath("$.data.price").value(120000));
    }

    @Test
    void image_secondPrimary_unsetsFirstPrimary() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String productId = createDraftProduct(ctx);

        mockMvc.perform(post("/api/v1/admin/products/" + productId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"https://res.cloudinary.com/test/image/upload/1.jpg\",\"isPrimary\":true,\"sortOrder\":0}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/products/" + productId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"https://res.cloudinary.com/test/image/upload/2.jpg\",\"isPrimary\":true,\"sortOrder\":1}"))
                .andExpect(status().isCreated());

        MvcResult detail = mockMvc.perform(get("/api/v1/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode images = json(detail.getResponse().getContentAsString()).at("/data/images");

        long primaryCount = 0;
        for (JsonNode image : images) {
            if (image.get("isPrimary").asBoolean()) {
                primaryCount++;
                assertThat(image.get("imageUrl").asText())
                        .isEqualTo("https://res.cloudinary.com/test/image/upload/2.jpg");
            }
        }
        assertThat(primaryCount).isEqualTo(1);
    }

    @Test
    void primaryImageInvariant_dbIndexRejectsSecondPrimaryRow_whenServiceLayerIsBypassed() throws Exception {
        // Deterministic, non-flaky proof that the DB constraint itself - not just
        // ProductImageService's read-modify-write - is what keeps this invariant.
        // Goes around the service entirely (straight repository access) to insert
        // a second is_primary=true row for the same product; this must fail at
        // the database regardless of any service-level logic.
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String productId = createDraftProduct(ctx);

        mockMvc.perform(post("/api/v1/admin/products/" + productId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"https://res.cloudinary.com/test/image/upload/first.jpg\",\"isPrimary\":true}"))
                .andExpect(status().isCreated());

        ProductImage rogue = new ProductImage();
        rogue.setProduct(productRepository.findById(UUID.fromString(productId)).orElseThrow());
        rogue.setImageUrl("https://example.com/rogue.jpg");
        rogue.setSortOrder(99);
        rogue.setPrimary(true);

        assertThatThrownBy(() -> productImageRepository.saveAndFlush(rogue))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void primaryImage_concurrentRequests_alwaysEndUpWithExactlyOnePrimary() throws Exception {
        // What is NOT deterministic here: how many of the 10 concurrent requests
        // return 201 vs 409. A request that arrives after an earlier one has
        // already committed will legitimately see it as "the current primary",
        // unset it, and insert its own as the new primary in the same
        // transaction - that is a normal 201 (the same "second primary unsets
        // first" behavior as image_secondPrimary_unsetsFirstPrimary), not a
        // conflict. Only requests that race to be the FIRST primary at the exact
        // same instant hit the unique index and get 409. An earlier version of
        // this test asserted "exactly 1 success" and was flaky for this reason -
        // caught by running it repeatedly, not by reasoning about it upfront.
        //
        // What MUST always be true, and what this asserts: every response is
        // 201 or 409 (never 500), at least one request succeeds, and the
        // product ends up with exactly one primary image row.
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String productId = createDraftProduct(ctx);
        int concurrency = 10;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            Callable<Integer> call = () -> mockMvc.perform(post("/api/v1/admin/products/" + productId + "/images")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"imageUrl\":\"https://res.cloudinary.com/test/image/upload/race.jpg\",\"isPrimary\":true}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();

            List<Future<Integer>> futures = pool.invokeAll(List.of(
                    call, call, call, call, call, call, call, call, call, call));
            List<Integer> statusCodes = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());

            assertThat(statusCodes).hasSize(concurrency);
            assertThat(statusCodes).allMatch(code -> code == 201 || code == 409);
            assertThat(statusCodes).contains(201);

            List<ProductImage> primaries =
                    productImageRepository.findAllByProductIdAndPrimaryTrue(UUID.fromString(productId));
            assertThat(primaries).hasSize(1);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void publicDetail_includesVariantsAndImages_withAvailableQuantityComputed() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String slug = "prod-detail-" + UUID.randomUUID();
        String productId = createActiveProduct(ctx, slug);

        mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-" + UUID.randomUUID() + "\",\"size\":\"L\",\"color\":\"White\","
                                + "\"price\":100000,\"stockQuantity\":7}"))
                .andExpect(status().isCreated());

        MvcResult detail = mockMvc.perform(get("/api/v1/products/" + slug))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(detail.getResponse().getContentAsString()).at("/data/variants"))
                .anySatisfy(variant -> {
                    assertThat(variant.at("/availableQuantity").asInt()).isEqualTo(7);
                    assertThat(variant.at("/status").asText()).isEqualTo("ACTIVE");
                });
    }

    @Test
    void publicListing_filterByGender_andPaginationMeta() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        String menSlug = "prod-men-" + UUID.randomUUID();
        createActiveProductWithGender(ctx, menSlug, "MEN");
        String womenSlug = "prod-women-" + UUID.randomUUID();
        createActiveProductWithGender(ctx, womenSlug, "WOMEN");
        String unisexSlug = "prod-unisex-" + UUID.randomUUID();
        createActiveProductWithGender(ctx, unisexSlug, "UNISEX");

        mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", ctx.categoryId())
                        .param("gender", "WOMEN")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.slug=='" + womenSlug + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.slug=='" + unisexSlug + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.slug=='" + menSlug + "')]").doesNotExist())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.limit").value(10));
    }

    @Test
    void adminEndpoint_asCustomer_returns403() throws Exception {
        AdminContext ctx = setUpAdminWithCatalogRefs();
        TokenPair customer = registerUser(uniqueEmail("product-customer"));

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Shirt\",\"slug\":\"prod-%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(UUID.randomUUID(), ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    private String createActiveProduct(AdminContext ctx) throws Exception {
        return createActiveProduct(ctx, "prod-" + UUID.randomUUID());
    }

    private String createActiveProduct(AdminContext ctx, String slug) throws Exception {
        String productId = createDraftProduct(ctx, slug);
        createSellableVariant(ctx, productId);
        makeProductPublishableForTest(productId);
        return productId;
    }

    private String createDraftProduct(AdminContext ctx) throws Exception {
        return createDraftProduct(ctx, "prod-" + UUID.randomUUID());
    }

    private String createDraftProduct(AdminContext ctx, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Shirt\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private void createActiveProductWithGender(AdminContext ctx, String slug, String gender) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Shirt\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\","
                                + "\"gender\":\"%s\"}")
                                .formatted(slug, ctx.categoryId(), ctx.brandId(), gender)))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        createSellableVariant(ctx, productId);
        makeProductPublishableForTest(productId);
    }

    private void createSellableVariant(AdminContext ctx, String productId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"FIXTURE-" + UUID.randomUUID()
                                + "\",\"size\":\"M\",\"color\":\"Black\",\"price\":100000,\"stockQuantity\":1}"))
                .andExpect(status().isCreated());
    }
}
