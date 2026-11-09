package com.dunghaiquyen.ecommerce.modules.review;

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

@Tag("phase0-excluded")
@Tag("dormant")
class ReviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("rv-admin"));
        String categorySlug = "rv-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "rv-brand-" + UUID.randomUUID();
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

    private CreatedProduct createActiveProduct(AdminContext ctx, String name) throws Exception {
        String slug = "rv-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\",\"status\":\"ACTIVE\"}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        return new CreatedProduct(
                json(result.getResponse().getContentAsString()).at("/data/id").asText(),
                json(result.getResponse().getContentAsString()).at("/data/slug").asText());
    }

    private String createVariant(AdminContext ctx, String productId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"SKU-%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":100000,\"stockQuantity\":10}")
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

    /** Buys one unit of variantId for buyerEmail/buyerToken and drives the order all the way to DELIVERED. */
    private String buyAndDeliver(AdminContext ctx, String buyerEmail, String buyerToken, String variantId) throws Exception {
        addToCart(buyerToken, variantId);
        String addressId = createAddressForUser(buyerEmail);
        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
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
        return orderId;
    }

    private String reviewBody(int rating, String title) {
        return "{\"rating\":" + rating + ",\"title\":\"" + title + "\",\"content\":\"Great product\"}";
    }

    // ===== create review success when product was purchased and delivered =====

    @Test
    void createReview_afterDelivered_succeeds_andDefaultsToPending() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review Shirt");
        String variantId = createVariant(ctx, product.id());

        String buyerEmail = uniqueEmail("rv-buyer-ok");
        TokenPair buyer = registerUser(buyerEmail);
        buyAndDeliver(ctx, buyerEmail, buyer.accessToken(), variantId);

        MvcResult result = mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(5, "Excellent")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(body.at("/data/rating").asInt()).isEqualTo(5);
        assertThat(body.at("/data/productId").asText()).isEqualTo(product.id());
    }

    // ===== create review fails when not purchased =====

    @Test
    void createReview_neverPurchased_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review NoPurchase");
        TokenPair buyer = registerUser(uniqueEmail("rv-no-purchase"));

        MvcResult result = mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(4, "Nice")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    // ===== create review fails when purchased but not yet delivered =====

    @Test
    void createReview_purchasedButNotDelivered_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review Pending");
        String variantId = createVariant(ctx, product.id());

        String buyerEmail = uniqueEmail("rv-buyer-pending");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId);
        String addressId = createAddressForUser(buyerEmail);
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(3, "Waiting")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    // ===== rating out of range rejected =====

    @Test
    void createReview_ratingOutOfRange_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review BadRating");
        String variantId = createVariant(ctx, product.id());

        String buyerEmail = uniqueEmail("rv-bad-rating");
        TokenPair buyer = registerUser(buyerEmail);
        buyAndDeliver(ctx, buyerEmail, buyer.accessToken(), variantId);

        MvcResult result = mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(6, "Too high")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    // ===== public list only returns APPROVED reviews =====

    @Test
    void publicList_onlyReturnsApprovedReviews() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review Public");
        String variantId1 = createVariant(ctx, product.id());
        String variantId2 = createVariant(ctx, product.id());

        String approvedEmail = uniqueEmail("rv-approved");
        TokenPair approvedBuyer = registerUser(approvedEmail);
        buyAndDeliver(ctx, approvedEmail, approvedBuyer.accessToken(), variantId1);
        MvcResult approvedCreate = mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + approvedBuyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(5, "Approved review")))
                .andExpect(status().isCreated())
                .andReturn();
        String approvedReviewId = json(approvedCreate.getResponse().getContentAsString()).at("/data/id").asText();

        String pendingEmail = uniqueEmail("rv-pending");
        TokenPair pendingBuyer = registerUser(pendingEmail);
        buyAndDeliver(ctx, pendingEmail, pendingBuyer.accessToken(), variantId2);
        mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pendingBuyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(2, "Still pending review")))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/admin/reviews/" + approvedReviewId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        MvcResult list = mockMvc.perform(get("/api/v1/products/" + product.slug() + "/reviews"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(list.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/id").asText()).isEqualTo(approvedReviewId);
        assertThat(body.at("/data/0/status").asText()).isEqualTo("APPROVED");
    }

    // ===== admin update status success =====

    @Test
    void adminUpdateStatus_toRejected_succeeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review Reject");
        String variantId = createVariant(ctx, product.id());

        String buyerEmail = uniqueEmail("rv-reject-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        buyAndDeliver(ctx, buyerEmail, buyer.accessToken(), variantId);
        MvcResult created = mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(1, "Bad")))
                .andExpect(status().isCreated())
                .andReturn();
        String reviewId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult result = mockMvc.perform(patch("/api/v1/admin/reviews/" + reviewId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(result.getResponse().getContentAsString()).at("/data/status").asText())
                .isEqualTo("REJECTED");
    }

    @Test
    void adminListAndDetail_reviews_areReadableForAdmin() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review Admin List");
        String variantId = createVariant(ctx, product.id());

        String buyerEmail = uniqueEmail("rv-admin-list");
        TokenPair buyer = registerUser(buyerEmail);
        buyAndDeliver(ctx, buyerEmail, buyer.accessToken(), variantId);
        MvcResult created = mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(4, "Visible to admin")))
                .andExpect(status().isCreated())
                .andReturn();
        String reviewId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult list = mockMvc.perform(get("/api/v1/admin/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listBody = json(list.getResponse().getContentAsString());
        assertThat(listBody.at("/data").isArray()).isTrue();
        assertThat(listBody.at("/meta/page").asInt()).isEqualTo(1);
        assertThat(listBody.at("/data").toString()).contains(reviewId);

        MvcResult detail = mockMvc.perform(get("/api/v1/admin/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detailBody = json(detail.getResponse().getContentAsString());
        assertThat(detailBody.at("/data/id").asText()).isEqualTo(reviewId);
        assertThat(detailBody.at("/data/title").asText()).isEqualTo("Visible to admin");
    }

    // ===== non-admin gets 403 on admin endpoint =====

    @Test
    void nonAdmin_cannotUpdateReviewStatus_returns403() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review Forbidden");
        String variantId = createVariant(ctx, product.id());

        String buyerEmail = uniqueEmail("rv-forbidden-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        buyAndDeliver(ctx, buyerEmail, buyer.accessToken(), variantId);
        MvcResult created = mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(3, "Ok")))
                .andExpect(status().isCreated())
                .andReturn();
        String reviewId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/admin/reviews/" + reviewId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
    }

    // ===== reviewing the same delivered purchase twice is blocked (uniqueness rule) =====

    @Test
    void createReview_sameOrderItemTwice_secondAttemptReturns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review Twice");
        String variantId = createVariant(ctx, product.id());

        String buyerEmail = uniqueEmail("rv-twice-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        buyAndDeliver(ctx, buyerEmail, buyer.accessToken(), variantId);

        mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(4, "First review")))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/v1/products/" + product.id() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(5, "Second review attempt")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void createReview_bySlugAfterDelivered_succeeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        CreatedProduct product = createActiveProduct(ctx, "Review By Slug");
        String variantId = createVariant(ctx, product.id());

        String buyerEmail = uniqueEmail("rv-buyer-slug");
        TokenPair buyer = registerUser(buyerEmail);
        buyAndDeliver(ctx, buyerEmail, buyer.accessToken(), variantId);

        mockMvc.perform(post("/api/v1/products/" + product.slug() + "/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(5, "Slug review")))
                .andExpect(status().isCreated());
    }

    @Test
    void adminUpdateStatus_invalidReviewIdFormat_returns422WithFieldError() throws Exception {
        AdminContext ctx = setUpAdmin();

        MvcResult result = mockMvc.perform(patch("/api/v1/admin/reviews/not-a-uuid/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/message").asText()).isEqualTo("Validation error");
        assertThat(body.at("/errors/0/field").asText()).isEqualTo("reviewId");
    }
}
