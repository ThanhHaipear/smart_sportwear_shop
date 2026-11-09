package com.dunghaiquyen.ecommerce.modules.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.common.storage.ImageStorageService;
import com.dunghaiquyen.ecommerce.common.storage.UploadedImage;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Cloudinary integration - real HTTP layer (MockMvc) + real Postgres, but a
 * fake ImageStorageService stands in for the real Cloudinary SDK at the
 * integration boundary (same pattern as ForgotPasswordIntegrationTest's
 * CapturingMailService): no network call to Cloudinary happens in this
 * suite. See ImageStorageProviderSelectionTest/ProductImageServiceTest for
 * what is NOT re-covered here (provider wiring, fail-fast, cleanup-on-
 * failure edge cases) - this class only proves the HTTP-level contract:
 * multipart binding, status codes, response shape, security, and that the
 * fake's upload()/delete() are actually invoked from the real controller.
 */
class ProductImageUploadIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private FakeImageStorageService fakeImageStorageService;

    @Autowired
    private ProductImageRepository productImageRepository;

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        ImageStorageService fakeImageStorageService() {
            return new FakeImageStorageService();
        }
    }

    static class FakeImageStorageService implements ImageStorageService {
        final List<String> deletedPublicIds = new CopyOnWriteArrayList<>();

        @Override
        public UploadedImage upload(MultipartFile file) {
            String publicId = "products/fake-" + UUID.randomUUID();
            return new UploadedImage(publicId, "https://fake.cloudinary.test/" + publicId + ".jpg", 800, 600);
        }

        @Override
        public void delete(String publicId) {
            deletedPublicIds.add(publicId);
        }
    }

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("img-admin"));
        String categorySlug = "img-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "img-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    private String createActiveProduct(AdminContext ctx) throws Exception {
        String slug = "img-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Shirt\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\"}")
                                .formatted(slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    // ===== upload success =====

    @Test
    void uploadImage_validFile_succeeds_andReturnsWidthHeight() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        MockMultipartFile file = new MockMultipartFile("file", "shirt.jpg", "image/jpeg", new byte[] {1, 2, 3, 4, 5});

        MvcResult result = mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/images/upload")
                        .file(file)
                        .param("altText", "Front view")
                        .param("isPrimary", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/publicId").asText()).startsWith("products/fake-");
        assertThat(body.at("/data/imageUrl").asText()).startsWith("https://fake.cloudinary.test/");
        assertThat(body.at("/data/width").asInt()).isEqualTo(800);
        assertThat(body.at("/data/height").asInt()).isEqualTo(600);
        assertThat(body.at("/data/isPrimary").asBoolean()).isTrue();

        assertThat(productImageRepository.findAllByProductIdOrderBySortOrderAsc(UUID.fromString(productId)))
                .hasSize(1);
    }

    // ===== reject non-image content type =====

    @Test
    void uploadImage_nonImageFile_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/images/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isUnprocessableEntity());
    }

    // ===== reject empty file =====

    @Test
    void uploadImage_emptyFile_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        MockMultipartFile file = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/images/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isUnprocessableEntity());
    }

    // ===== non-admin forbidden =====

    @Test
    void uploadImage_nonAdmin_returns403() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        TokenPair customer = registerUser(uniqueEmail("img-not-admin"));
        MockMultipartFile file = new MockMultipartFile("file", "shirt.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/images/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteImage_nonAdmin_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("img-delete-not-admin"));
        mockMvc.perform(delete("/api/v1/admin/products/" + UUID.randomUUID() + "/images/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ===== delete success: DB row removed + fake remote delete invoked with the right publicId =====

    @Test
    void deleteImage_success_removesDbRow_andCallsRemoteDeleteWithCorrectPublicId() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        MockMultipartFile file = new MockMultipartFile("file", "shirt.jpg", "image/jpeg", new byte[] {1, 2, 3});

        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/images/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode uploadBody = json(uploaded.getResponse().getContentAsString());
        String imageId = uploadBody.at("/data/id").asText();
        String publicId = uploadBody.at("/data/publicId").asText();

        MvcResult result = mockMvc.perform(delete("/api/v1/admin/products/" + productId + "/images/" + imageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data")).isEmpty();
        assertThat(productImageRepository.findById(UUID.fromString(imageId))).isEmpty();
        assertThat(fakeImageStorageService.deletedPublicIds).contains(publicId);
    }

    // ===== deleting an image that does not belong to this product returns 404 =====

    @Test
    void deleteImage_wrongProduct_returns404() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String otherProductId = createActiveProduct(ctx);
        MockMultipartFile file = new MockMultipartFile("file", "shirt.jpg", "image/jpeg", new byte[] {1, 2, 3});

        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/images/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isCreated())
                .andReturn();
        String imageId = json(uploaded.getResponse().getContentAsString()).at("/data/id").asText();

        mockMvc.perform(delete("/api/v1/admin/products/" + otherProductId + "/images/" + imageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isNotFound());
    }
}
