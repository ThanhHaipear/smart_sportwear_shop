package com.dunghaiquyen.ecommerce.modules.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Phase N4 - PDP completion: review summary, related products. */
class ProductDetailCompletionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("pdp-admin"));
        String categorySlug = "pdp-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "pdp-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    private record CreatedProduct(String id, String slug) {
    }

    private CreatedProduct createDraftProduct(AdminContext ctx, String name) throws Exception {
        String slug = "pdp-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        String id = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        return new CreatedProduct(id, slug);
    }

    private String createVariant(AdminContext ctx, String productId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"SKU-%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":80000,\"stockQuantity\":10}")
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
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

    /** Buys + delivers, then submits a review with the given rating, returning the new review's id. */
    private String buyDeliverAndReview(AdminContext ctx, String productId, String variantId, int rating) throws Exception {
        String buyerEmail = uniqueEmail("pdp-reviewer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId);
        String addressId = createAddressForUser(buyerEmail);
        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        for (String next : new String[] {"CONFIRMED", "PACKING", "SHIPPING", "DELIVERED"}) {
            mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + next + "\"}"))
                    .andExpect(status().isOk());
        }

        MvcResult review = mockMvc.perform(post("/api/v1/products/" + productId + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":" + rating + ",\"title\":\"T\",\"content\":\"C\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json(review.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private void approveReview(AdminContext ctx, String reviewId) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/reviews/" + reviewId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());
    }

    private void rejectReview(AdminContext ctx, String reviewId) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/reviews/" + reviewId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isOk());
    }

    // ===== review summary counts only APPROVED reviews =====

    @Test
    @Tag("phase0-excluded")
    @Tag("dormant")
    void productDetail_reviewSummary_onlyCountsApprovedReviews() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createDraftProduct(ctx, "Reviewed Shirt");
        String variantId = createVariant(ctx, product.id());
        makeProductPublishableForTest(product.id());

        String approvedReviewId = buyDeliverAndReview(ctx, product.id(), variantId, 5);
        approveReview(ctx, approvedReviewId);

        String secondApprovedId = buyDeliverAndReview(ctx, product.id(), variantId, 3);
        approveReview(ctx, secondApprovedId);

        String rejectedReviewId = buyDeliverAndReview(ctx, product.id(), variantId, 1);
        rejectReview(ctx, rejectedReviewId);
        // a freshly-submitted (still-PENDING) review must also not count
        buyDeliverAndReview(ctx, product.id(), variantId, 2);

        MvcResult result = mockMvc.perform(get("/api/v1/products/" + product.slug()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/reviewSummary/reviewCount").asLong()).isEqualTo(2);
        assertThat(body.at("/data/reviewSummary/averageRating").asDouble()).isEqualTo(4.0);
    }

    @Test
    void productDetail_noReviews_summaryIsZeroNotNullOrError() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createDraftProduct(ctx, "Unreviewed Shirt");
        createVariant(ctx, product.id());
        makeProductPublishableForTest(product.id());

        MvcResult result = mockMvc.perform(get("/api/v1/products/" + product.slug()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/reviewSummary/reviewCount").asLong()).isEqualTo(0);
        assertThat(body.at("/data/reviewSummary/averageRating").asDouble()).isEqualTo(0.0);
    }

    // ===== related products: same category or brand, excludes self, ACTIVE only =====

    @Test
    void productDetail_relatedProducts_sameCategoryOrBrand_excludesSelfAndInactive() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct main = createDraftProduct(ctx, "Main Product");
        CreatedProduct sameCategory = createDraftProduct(ctx, "Same Category Product");
        createVariant(ctx, main.id());
        createVariant(ctx, sameCategory.id());
        makeProductPublishableForTest(main.id());
        makeProductPublishableForTest(sameCategory.id());

        // A second category/brand combo for a product that must NOT show up as related.
        String otherCategorySlug = "pdp-other-cat-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult otherCat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OtherCat\",\"slug\":\"" + otherCategorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String otherCategoryId = json(otherCat.getResponse().getContentAsString()).at("/data/id").asText();
        String otherBrandSlug = "pdp-other-brand-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult otherBrand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OtherBrand\",\"slug\":\"" + otherBrandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String otherBrandId = json(otherBrand.getResponse().getContentAsString()).at("/data/id").asText();

        String unrelatedSlug = "pdp-unrelated-" + UUID.randomUUID();
        MvcResult unrelatedResult = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Unrelated Product\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(unrelatedSlug, otherCategoryId, otherBrandId)))
                .andExpect(status().isCreated())
                .andReturn();
        String unrelatedId = json(unrelatedResult.getResponse().getContentAsString()).at("/data/id").asText();
        createVariant(ctx, unrelatedId);
        makeProductPublishableForTest(unrelatedId);

        // DRAFT product in the same category must not appear either.
        String draftSlug = "pdp-draft-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Draft Product\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(draftSlug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/v1/products/" + main.slug()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        JsonNode related = body.at("/data/relatedProducts");

        boolean foundSameCategory = false;
        for (JsonNode item : related) {
            String id = item.at("/id").asText();
            assertThat(id).as("related products must never include the product itself").isNotEqualTo(main.id());
            assertThat(item.at("/name").asText()).isNotEqualTo("Unrelated Product");
            assertThat(item.at("/name").asText()).isNotEqualTo("Draft Product");
            if (id.equals(sameCategory.id())) {
                foundSameCategory = true;
            }
        }
        assertThat(foundSameCategory).isTrue();
    }
}
