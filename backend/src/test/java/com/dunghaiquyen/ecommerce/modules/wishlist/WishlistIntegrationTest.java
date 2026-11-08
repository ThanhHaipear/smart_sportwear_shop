package com.dunghaiquyen.ecommerce.modules.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class WishlistIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductImageRepository productImageRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("wl-admin"));
        String categorySlug = "wl-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "wl-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    private String createProduct(AdminContext ctx, String name, String status) throws Exception {
        String slug = "wl-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\",\"status\":\"DRAFT\"}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        if ("ACTIVE".equals(status)) {
            mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(("{\"sku\":\"SKU-%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":80000,\"stockQuantity\":10}")
                                    .formatted(UUID.randomUUID())))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/v1/admin/products/" + productId + "/images")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(("{\"imageUrl\":\"https://res.cloudinary.com/demo/image/upload/%s.jpg\",\"publicId\":\"wishlist/%s\",\"isPrimary\":true}")
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
        return productId;
    }

    private String addItemBody(String productId) {
        return "{\"productId\":\"" + productId + "\"}";
    }

    // ===== get wishlist returns empty when none exists =====

    @Test
    void getWishlist_whenNoneExists_returnsEmptyWishlist() throws Exception {
        TokenPair buyer = registerUser(uniqueEmail("wl-empty"));

        MvcResult result = mockMvc.perform(get("/api/v1/wishlist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/id").isNull()).isTrue();
        assertThat(body.at("/data/items")).isEmpty();
    }

    // ===== add wishlist item success =====

    @Test
    void addItem_activeProduct_succeeds_andCreatesWishlistLazily() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createProduct(ctx, "Wishlist Shirt", "ACTIVE");
        TokenPair buyer = registerUser(uniqueEmail("wl-add"));

        MvcResult result = mockMvc.perform(post("/api/v1/wishlist/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(productId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/id").asText()).isNotBlank();
        assertThat(body.at("/data/items")).hasSize(1);
        assertThat(body.at("/data/items/0/productId").asText()).isEqualTo(productId);
        assertThat(body.at("/data/items/0/productName").asText()).isEqualTo("Wishlist Shirt");
    }

    // ===== add duplicate wishlist item blocked =====

    @Test
    void addItem_duplicateProduct_returns409() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createProduct(ctx, "Wishlist Dup", "ACTIVE");
        TokenPair buyer = registerUser(uniqueEmail("wl-dup"));

        mockMvc.perform(post("/api/v1/wishlist/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(productId)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/v1/wishlist/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(productId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    // ===== add inactive product blocked =====

    @Test
    void addItem_inactiveProduct_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createProduct(ctx, "Wishlist Draft", "DRAFT");
        TokenPair buyer = registerUser(uniqueEmail("wl-inactive"));

        MvcResult result = mockMvc.perform(post("/api/v1/wishlist/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(productId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    // ===== add non-existent product returns 404 =====

    @Test
    void addItem_productNotFound_returns404() throws Exception {
        TokenPair buyer = registerUser(uniqueEmail("wl-404"));

        MvcResult result = mockMvc.perform(post("/api/v1/wishlist/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(UUID.randomUUID().toString())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ===== delete wishlist item success =====

    @Test
    void removeItem_existingItem_succeeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createProduct(ctx, "Wishlist Remove", "ACTIVE");
        TokenPair buyer = registerUser(uniqueEmail("wl-remove"));

        mockMvc.perform(post("/api/v1/wishlist/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(productId)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(delete("/api/v1/wishlist/items/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/items")).isEmpty();
    }

    // ===== delete on a non-existent wishlist item returns 404 =====

    @Test
    void removeItem_notInWishlist_returns404() throws Exception {
        TokenPair buyer = registerUser(uniqueEmail("wl-remove-404"));

        MvcResult result = mockMvc.perform(delete("/api/v1/wishlist/items/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ===== unauthenticated caller is rejected =====

    @Test
    void getWishlist_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/wishlist")).andExpect(status().isUnauthorized());
    }
}
