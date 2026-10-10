package com.dunghaiquyen.ecommerce.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.common.mail.MailService;
import com.dunghaiquyen.ecommerce.common.security.TokenHasher;
import com.dunghaiquyen.ecommerce.modules.auth.repository.PasswordResetTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class ForgotPasswordIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CapturingMailService mailService;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private TokenHasher tokenHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Overrides the real LoggingMailService with a capturing fake so tests can
     * read the raw reset token out of the "sent" email body - the production
     * API response never contains it (forgot-password's whole point is to not
     * leak whether the email exists, let alone hand back the token itself).
     */
    @TestConfiguration
    static class MailTestConfig {
        @Bean
        @Primary
        MailService capturingMailService() {
            return new CapturingMailService();
        }
    }

    static class CapturingMailService implements MailService {
        private final List<String[]> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String to, String subject, String body) {
            sent.add(new String[] {to, subject, body});
        }

        String lastTokenFor(String to) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i)[0].equals(to)) {
                    Matcher matcher = Pattern.compile("token=([^\\s&]+)").matcher(sent.get(i)[2]);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
            return null;
        }
    }

    private String forgotPassword(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/success").asBoolean()).isTrue();
        assertThat(body.at("/message").asText()).isEqualTo("If the email exists, a reset instruction has been sent");
        return body.toString();
    }

    private MvcResult resetPassword(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn();
    }

    // ===== forgot-password: identical response for existing vs unknown email =====

    @Test
    void forgotPassword_existingEmail_returnsGenericSuccess() throws Exception {
        String email = uniqueEmail("fp-exists");
        registerUser(email);

        String responseBody = forgotPassword(email);
        assertThat(responseBody).contains("\"success\":true");

        assertThat(mailService.lastTokenFor(email))
                .as("a reset token must have actually been generated and sent for a real account")
                .isNotBlank();
    }

    @Test
    void forgotPassword_unknownEmail_returnsSameGenericSuccess() throws Exception {
        String unknownEmail = uniqueEmail("fp-unknown");

        MvcResult known = mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail("fp-control") + "\"}"))
                .andReturn();
        MvcResult unknown = mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + unknownEmail + "\"}"))
                .andReturn();

        assertThat(unknown.getResponse().getStatus()).isEqualTo(known.getResponse().getStatus());
        assertThat(unknown.getResponse().getContentAsString())
                .as("response body must be identical regardless of whether the email exists")
                .isEqualTo(known.getResponse().getContentAsString());
        assertThat(mailService.lastTokenFor(unknownEmail))
                .as("no token/mail must be generated for an email that does not exist")
                .isNull();
    }

    // ===== reset-password: success path =====

    @Test
    void resetPassword_validToken_success_thenNewPasswordLogsInAndOldPasswordFails() throws Exception {
        String email = uniqueEmail("fp-reset-success");
        registerUser(email);
        forgotPassword(email);
        String token = mailService.lastTokenFor(email);

        MvcResult result = resetPassword(token, "NewPassword456");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/message").asText()).isEqualTo("Password reset successful");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"NewPassword456\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ===== reset-password: invalid / expired / reused token =====

    @Test
    void resetPassword_garbageToken_fails() throws Exception {
        MvcResult result = resetPassword("not-a-real-token-at-all", "NewPassword456");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void resetPassword_expiredToken_fails() throws Exception {
        String email = uniqueEmail("fp-expired");
        registerUser(email);
        forgotPassword(email);
        String token = mailService.lastTokenFor(email);

        var resetToken = passwordResetTokenRepository.findByTokenHash(tokenHasher.hash(token)).orElseThrow();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "update password_reset_tokens set created_at = ?, expires_at = ? where id = ?",
                Timestamp.from(now.minusSeconds(120)), Timestamp.from(now.minusSeconds(60)), resetToken.getId());

        MvcResult result = resetPassword(token, "NewPassword456");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);

        // The password must be untouched by the rejected attempt.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void resetPassword_reusedToken_fails() throws Exception {
        String email = uniqueEmail("fp-reuse");
        registerUser(email);
        forgotPassword(email);
        String token = mailService.lastTokenFor(email);

        resetPassword(token, "NewPassword456");

        MvcResult secondAttempt = resetPassword(token, "AnotherPassword789");
        assertThat(secondAttempt.getResponse().getStatus()).isEqualTo(401);

        // The first reset's password must still be the active one - the reuse
        // attempt must not have changed anything.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"NewPassword456\"}"))
                .andExpect(status().isOk());
    }

    // ===== reset-password revokes old sessions =====

    @Test
    void resetPassword_revokesExistingRefreshTokens_oldRefreshTokenCannotBeReused() throws Exception {
        String email = uniqueEmail("fp-revoke-session");
        TokenPair original = registerUser(email);

        forgotPassword(email);
        String token = mailService.lastTokenFor(email);
        resetPassword(token, "NewPassword456").getResponse();

        MvcResult refreshAttempt = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + original.refreshToken() + "\"}"))
                .andReturn();
        assertThat(refreshAttempt.getResponse().getStatus())
                .as("a refresh token issued before the password reset must not still work after it")
                .isEqualTo(401);
    }

    // ===== regression: concurrent reset attempts with the same token must not double-apply =====

    @Test
    void resetPassword_concurrentAttemptsWithSameToken_onlyOneSucceeds() throws Exception {
        String email = uniqueEmail("fp-race");
        registerUser(email);
        forgotPassword(email);
        String token = mailService.lastTokenFor(email);

        java.util.concurrent.Callable<Integer> attempt = () -> resetPassword(token, "RacePassword456")
                .getResponse()
                .getStatus();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var results = pool.invokeAll(List.of(attempt, attempt));
        pool.shutdown();

        int successCount = 0;
        for (var f : results) {
            int statusCode = f.get();
            assertThat(statusCode).as("loser must fail in a controlled way, never 500").isIn(200, 401);
            if (statusCode == 200) {
                successCount++;
            }
        }
        assertThat(successCount).as("exactly one of the two concurrent resets may succeed").isEqualTo(1);
    }
}
