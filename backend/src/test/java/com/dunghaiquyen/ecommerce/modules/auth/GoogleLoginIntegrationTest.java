package com.dunghaiquyen.ecommerce.modules.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.user.entity.LoginProvider;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for POST /api/v1/auth/google. WireMock stubs Google's
 * tokeninfo endpoint so tests never make real network calls (and are
 * hermetic even without internet access). The WireMock server starts on a
 * random port and @DynamicPropertySource overrides app.google.tokeninfo-url
 * before the Spring context is created, so AuthService.verifyGoogleToken()
 * calls the stub instead of real Google.
 *
 * The GOOGLE_CLIENT_ID (app.google.client-id) is intentionally left empty
 * (the default) so the "aud" validation is skipped in these tests - the real
 * client ID check is a single string equality already covered by its own
 * unit-level logic (see verifyGoogleToken's aud check) and does not need a
 * full Spring context to test.
 */
class GoogleLoginIntegrationTest extends AbstractIntegrationTest {

    @RegisterExtension
    static WireMockExtension googleStub = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void configureGoogleTokenInfoUrl(DynamicPropertyRegistry registry) {
        registry.add("app.google.tokeninfo-url", googleStub::baseUrl);
        registry.add("app.google.client-id", () -> "");
    }

    private void stubValidToken(String email, String name) {
        googleStub.stubFor(get(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sub":"sub-%s","email":"%s","email_verified":"true",
                                "name":"%s","picture":"https://lh3.googleusercontent.com/photo.jpg",
                                "aud":"test-client-id.apps.googleusercontent.com"}
                                """.formatted(email, email, name))));
    }

    private void stubInvalidToken() {
        googleStub.stubFor(get(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_token\",\"error_description\":\"Invalid Value\"}")));
    }

    // ===== happy path: first-time Google login creates user + issues JWT =====

    @Test
    void googleLogin_newUser_createsAccountAndIssuesJwt() throws Exception {
        String email = uniqueEmail("gl-new");
        stubValidToken(email, "Google User");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"fake-google-id-token\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/tokens/accessToken").asText()).isNotBlank();
        assertThat(body.at("/data/tokens/refreshToken").asText()).isNotBlank();
        assertThat(body.at("/data/user/email").asText()).isEqualTo(email);
        assertThat(body.at("/data/user/loginProvider").asText()).isEqualTo("GOOGLE");

        User created = userRepository.findByEmail(email).orElseThrow();
        assertThat(created.getLoginProvider()).isEqualTo(LoginProvider.GOOGLE);
        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    // ===== returning Google user: second login succeeds without creating a duplicate =====

    @Test
    void googleLogin_returningGoogleUser_succeedsWithoutDuplicate() throws Exception {
        String email = uniqueEmail("gl-returning");
        stubValidToken(email, "Returning User");

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"token1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"token2\"}"))
                .andExpect(status().isOk());

        long count = userRepository.findAll().stream()
                .filter(u -> email.equals(u.getEmail()))
                .count();
        assertThat(count).as("second Google login must reuse the existing account, not create a second row").isEqualTo(1);
    }

    // ===== invalid Google credential: 422 with clear message =====

    @Test
    void googleLogin_invalidToken_returns422() throws Exception {
        stubInvalidToken();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"this-is-not-a-valid-google-token\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .containsIgnoringCase("google");
    }

    // ===== LOCAL account collision: Google login rejected if email already has password account =====

    @Test
    void googleLogin_emailAlreadyExistsAsLocalAccount_returns422() throws Exception {
        String email = uniqueEmail("gl-collision");
        registerUser(email);
        stubValidToken(email, "Collision User");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"fake-token\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .containsIgnoringCase("original sign-in method");
    }

    // ===== Reverse: Google user trying email+password login returns 422 =====

    @Test
    void emailPasswordLogin_forGoogleAccount_returns422() throws Exception {
        String email = uniqueEmail("gl-reverse");
        stubValidToken(email, "Reverse User");
        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"fake-token\"}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"AnyPassword1\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .containsIgnoringCase("google");
    }

    // ===== blank credential: 422 (Bean Validation, no network call) =====

    @Test
    void googleLogin_blankCredential_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ===== no auth required for the endpoint =====

    @Test
    void googleLogin_noJwtRequired_publicEndpoint() throws Exception {
        stubValidToken(uniqueEmail("gl-pub"), "Public User");
        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"token\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("Should never return 401 - Google login is public")
                        .isNotEqualTo(401));
    }

    // ===== locked Google account returns 403 =====

    @Test
    void googleLogin_lockedAccount_returns403() throws Exception {
        String email = uniqueEmail("gl-locked");
        stubValidToken(email, "Locked User");
        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"token\"}"))
                .andExpect(status().isOk());

        User user = userRepository.findByEmail(email).orElseThrow();
        user.setStatus(UserStatus.LOCKED);
        userRepository.save(user);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"token\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    // ===== existing tests that touched /auth/login are still green for LOCAL accounts =====

    @Test
    void existingLocalLogin_stillWorksAfterGoogleMigration() throws Exception {
        String email = uniqueEmail("gl-local-ok");
        TokenPair tokens = registerUser(email);
        assertThat(tokens.accessToken()).isNotBlank();

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(login.getResponse().getContentAsString()).at("/data/user/loginProvider").asText())
                .isEqualTo("LOCAL");
    }
}
