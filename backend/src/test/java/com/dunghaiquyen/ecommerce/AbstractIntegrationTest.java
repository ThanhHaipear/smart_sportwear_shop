package com.dunghaiquyen.ecommerce;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.dunghaiquyen.ecommerce.modules.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared base for Phase C integration tests: real Spring context, real
 * Spring Security filter chain (via MockMvc + spring-security-test, not a
 * @WebMvcTest slice), real Postgres - no service-layer mocking.
 *
 * Database: an isolated PostgreSQL Testcontainer. Flyway runs the real migrations
 * against the disposable database on context startup. Environment DB variables
 * cannot redirect tests to Supabase or another shared database.
 *
 * The container is shared by the Spring test context and removed automatically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AbstractIntegrationTest.NoCacheTestConfiguration.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withInitScript("init-pgvector.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @TestConfiguration
    static class NoCacheTestConfiguration {

        @Bean
        @Primary
        CacheManager testCacheManager() {
            return new NoOpCacheManager();
        }
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected String uniqueEmail(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID() + "@example.com";
    }

    protected JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    /**
     * Adds the minimum deterministic image metadata required by the V2
     * publishability constraint, then activates the product. Call this only
     * after the test has created at least one ACTIVE or OUT_OF_STOCK variant.
     *
     * <p>The image is test-only metadata in the disposable Testcontainers
     * database; no remote asset is uploaded and no Supabase data is touched.
     */
    protected void makeProductPublishableForTest(String productId) {
        makeProductPublishableForTest(UUID.fromString(productId));
    }

    protected void makeProductPublishableForTest(UUID productId) {
        Integer primaryCount = jdbcTemplate.queryForObject(
                "select count(*) from product_images where product_id = ? and is_primary",
                Integer.class,
                productId);
        if (primaryCount == null || primaryCount == 0) {
            UUID imageId = UUID.randomUUID();
            jdbcTemplate.update(
                    """
                    insert into product_images (
                        id, product_id, storage_provider, public_id,
                        asset_version, secure_url, format, bytes,
                        sort_order, is_primary, checksum, width, height,
                        source_kind, source_uri, license_code
                    ) values (?, ?, 'CLOUDINARY', ?, 'test-v1', ?, 'jpg', 1,
                              0, true, ?, 1, 1, 'SYNTHETIC', ?, 'TEST_ONLY')
                    """,
                    imageId,
                    productId,
                    "test/products/" + imageId,
                    "https://res.cloudinary.com/test/image/upload/" + imageId + ".jpg",
                    "0".repeat(64),
                    "https://example.test/fixtures/" + imageId + ".jpg");
        }
        jdbcTemplate.update("update products set status = 'ACTIVE', updated_at = now() where id = ?", productId);
    }

    public record TokenPair(String accessToken, String refreshToken) {
    }

    /** Registers a fresh user (default password "Password123") and returns its issued token pair. */
    protected TokenPair registerUser(String email) throws Exception {
        String body = """
                {"fullName":"Test User","email":"%s","password":"Password123"}
                """.formatted(email);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = json(result.getResponse().getContentAsString());
        return new TokenPair(
                node.at("/data/tokens/accessToken").asText(),
                node.at("/data/tokens/refreshToken").asText());
    }

    /**
     * Registers a normal CUSTOMER (the only role register can ever create),
     * promotes it to ADMIN directly via the repository (no admin-creation
     * endpoint exists), then logs in again so the issued access token's "role"
     * claim reflects ADMIN - the claim is fixed at issuance time, so reusing
     * the original registration token would still say CUSTOMER.
     */
    protected String registerAdminAndGetAccessToken(String email) throws Exception {
        registerUser(email);
        var user = userRepository.findByEmail(email).orElseThrow();
        user.setRole(UserRole.ADMIN);
        userRepository.save(user);

        String body = """
                {"email":"%s","password":"Password123"}
                """.formatted(email);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();
    }
}
