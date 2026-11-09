package com.dunghaiquyen.ecommerce.modules.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AdminProductListIntegrationTest extends AbstractIntegrationTest {

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("adminlist-admin"));
        String categorySlug = "adminlist-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat " + categorySlug.substring(categorySlug.length() - 8)
                                + "\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "adminlist-brand-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand " + brandSlug.substring(brandSlug.length() - 8)
                                + "\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    private String createProduct(AdminContext ctx, String name, String status, boolean featured) throws Exception {
        String slug = "adminlist-prod-" + UUID.randomUUID();
        String initialStatus = "ACTIVE".equals(status) ? "DRAFT" : status;
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\","
                                        + "\"status\":\"%s\",\"isFeatured\":%s}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId(), initialStatus, featured))
                )
                .andExpect(status().isCreated())
                .andReturn();
        String productId = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        if ("ACTIVE".equals(status)) {
            mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sku\":\"SKU-" + UUID.randomUUID()
                                    + "\",\"size\":\"M\",\"color\":\"Black\",\"price\":100000,\"stockQuantity\":1}"))
                    .andExpect(status().isCreated());
            makeProductPublishableForTest(productId);
        }
        return productId;
    }

    // ===== sees draft/inactive, not just active =====

    @Test
    void list_returnsDraftAndInactiveProducts_notJustActive() throws Exception {
        AdminContext ctx = setUpAdmin();
        String draftId = createProduct(ctx, "Admin Draft Product", "DRAFT", false);
        String activeId = createProduct(ctx, "Admin Active Product", "ACTIVE", false);
        String inactiveId = createProduct(ctx, "Admin Inactive Product", "INACTIVE", false);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .param("categoryId", ctx.categoryId()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        var ids = body.at("/data");
        boolean foundDraft = false;
        boolean foundActive = false;
        boolean foundInactive = false;
        for (JsonNode item : ids) {
            if (item.at("/id").asText().equals(draftId)) {
                foundDraft = true;
                assertThat(item.at("/status").asText()).isEqualTo("DRAFT");
            }
            if (item.at("/id").asText().equals(activeId)) {
                foundActive = true;
            }
            if (item.at("/id").asText().equals(inactiveId)) {
                foundInactive = true;
            }
        }
        assertThat(foundDraft).isTrue();
        assertThat(foundActive).isTrue();
        assertThat(foundInactive).isTrue();
    }

    // ===== filter by status =====

    @Test
    void list_filterByStatus_returnsOnlyThatStatus() throws Exception {
        AdminContext ctx = setUpAdmin();
        String draftId = createProduct(ctx, "Filter Draft", "DRAFT", false);
        createProduct(ctx, "Filter Active", "ACTIVE", false);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .param("categoryId", ctx.categoryId())
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/id").asText()).isEqualTo(draftId);
        assertThat(body.at("/data/0/status").asText()).isEqualTo("DRAFT");
    }

    // ===== filter by keyword =====

    @Test
    void list_filterByKeyword_matches() throws Exception {
        AdminContext ctx = setUpAdmin();
        String uniqueWord = "Adminkw" + UUID.randomUUID().toString().substring(0, 8);
        String matchId = createProduct(ctx, uniqueWord + " Shirt", "DRAFT", false);
        createProduct(ctx, "Unrelated Product", "DRAFT", false);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .param("keyword", uniqueWord))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/id").asText()).isEqualTo(matchId);
    }

    // ===== filter by featured =====

    @Test
    @Tag("phase0-excluded")
    @Tag("schema-decision")
    void list_filterByFeatured_returnsOnlyFeatured() throws Exception {
        AdminContext ctx = setUpAdmin();
        String featuredId = createProduct(ctx, "Featured Product", "ACTIVE", true);
        createProduct(ctx, "Not Featured Product", "ACTIVE", false);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .param("categoryId", ctx.categoryId())
                        .param("featured", "true"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).hasSize(1);
        assertThat(body.at("/data/0/id").asText()).isEqualTo(featuredId);
        assertThat(body.at("/data/0/isFeatured").asBoolean()).isTrue();
    }

    // ===== filter by brandId =====

    @Test
    void list_filterByBrandId_returnsOnlyThatBrand() throws Exception {
        AdminContext ctx = setUpAdmin();
        String matchId = createProduct(ctx, "Brand Match", "ACTIVE", false);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .param("brandId", ctx.brandId())
                        .param("categoryId", ctx.categoryId()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode item : body.at("/data")) {
            if (item.at("/id").asText().equals(matchId)) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    // ===== pagination =====

    @Test
    void list_pagination_returnsCorrectMetaAndSlice() throws Exception {
        AdminContext ctx = setUpAdmin();
        createProduct(ctx, "Page Item One", "DRAFT", false);
        createProduct(ctx, "Page Item Two", "DRAFT", false);
        createProduct(ctx, "Page Item Three", "DRAFT", false);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
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
    }

    // ===== non-admin forbidden =====

    @Test
    void list_nonAdmin_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("adminlist-not-admin"));
        mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }
}
