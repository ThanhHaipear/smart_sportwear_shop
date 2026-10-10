package com.dunghaiquyen.ecommerce.modules.auth.service;

import com.dunghaiquyen.ecommerce.common.exception.InvalidTokenException;
import com.dunghaiquyen.ecommerce.common.mail.MailService;
import com.dunghaiquyen.ecommerce.common.security.TokenHasher;
import com.dunghaiquyen.ecommerce.config.AppPasswordResetProperties;
import com.dunghaiquyen.ecommerce.modules.auth.entity.PasswordResetToken;
import com.dunghaiquyen.ecommerce.modules.auth.entity.RefreshTokenRevocationReason;
import com.dunghaiquyen.ecommerce.modules.auth.repository.PasswordResetTokenRepository;
import com.dunghaiquyen.ecommerce.modules.auth.repository.RefreshTokenRepository;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserStatus;
import com.dunghaiquyen.ecommerce.modules.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deliberately its own service, not folded into AuthService - register/login/
 * refresh/logout already make that class large enough on their own, and
 * forgot/reset password (token lifecycle + mail) does not share any state
 * with the rest of AuthService beyond UserRepository/TokenHasher.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int DEFAULT_TTL_MINUTES = 15;
    private static final int TOKEN_BYTE_LENGTH = 32;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenHasher tokenHasher;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final AppPasswordResetProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            TokenHasher tokenHasher,
            PasswordEncoder passwordEncoder,
            MailService mailService,
            AppPasswordResetProperties properties) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenHasher = tokenHasher;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.properties = properties;
    }

    /**
     * API_SPEC_PHASE1.md 3.5 + PHASE1_SPEC.md: "luôn trả response thành công
     * chung, không tiết lộ email có tồn tại hay không". This method itself
     * never throws for "email not found" - it is a silent no-op, and the
     * controller returns the exact same success response either way. Callers
     * must not branch on whether this method "did anything".
     */
    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return;
        }
        User user = userOpt.get();
        if (user.getPasswordHash() == null || user.getStatus() != UserStatus.ACTIVE) {
            return;
        }

        String rawToken = generateRawToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(tokenHasher.hash(rawToken));
        token.setExpiresAt(Instant.now().plus(tokenTtlMinutes(), ChronoUnit.MINUTES));
        try {
            passwordResetTokenRepository.save(token);
        } catch (DataIntegrityViolationException ex) {
            // token_hash collision (vanishingly unlikely with 256 bits of entropy) -
            // retry once with a freshly generated token rather than fail the request.
            rawToken = generateRawToken();
            token.setTokenHash(tokenHasher.hash(rawToken));
            passwordResetTokenRepository.save(token);
        }

        String link = properties.resetUrl() + "?token=" + rawToken;
        // Delivery failure must not roll back the one-time token just issued.
        try {
            mailService.send(
                    user.getEmail(),
                    "Đặt lại mật khẩu",
                    "Mở liên kết sau để đặt lại mật khẩu: " + link
                            + "\nLiên kết hết hạn sau " + tokenTtlMinutes() + " phút.");
        } catch (RuntimeException ex) {
            // Email delivery must not roll back the reset token that was issued.
            log.warn("Password reset email delivery failed: {}", ex.getClass().getSimpleName());
        }
    }

    /**
     * API_SPEC_PHASE1.md 3.6. All three failure modes (unknown/garbage token,
     * expired, already used) deliberately throw the exact same message -
     * distinguishing them would let a caller probe which reason applies to a
     * given token, the same kind of information leak forgot-password is
     * already required to avoid. Not explicitly demanded by the spec text for
     * reset-password, but chosen to hold this endpoint to the same bar
     * ("ưu tiên hướng an toàn").
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(tokenHasher.hash(rawToken))
                .filter(t -> t.getUsedAt() == null)
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired reset token"));

        // Atomic claim - see PasswordResetTokenRepository#markUsedIfUnused javadoc.
        // The filters above are a fail-fast read; this is what actually closes
        // the race against a second concurrent call for the same token.
        int claimed = passwordResetTokenRepository.markUsedIfUnused(token.getId(), Instant.now());
        if (claimed == 0) {
            throw new InvalidTokenException("Invalid or expired reset token");
        }

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Not stated explicitly in the spec for reset-password (only for
        // refresh-token reuse) - chosen deliberately: a refresh token issued
        // before the reset surviving it is exactly the "attacker who stole the
        // old password still has a working session" scenario a password reset
        // is meant to close. Same transaction as the password change itself
        // (plain repository call, not the REQUIRES_NEW RefreshTokenRevocationService
        // wrapper used by AuthService.refresh) - nothing after this throws, so
        // there is no rollback-vs-revoke ordering problem to guard against here.
        refreshTokenRepository.revokeAllActiveForUser(
                user.getId(), Instant.now(), RefreshTokenRevocationReason.PASSWORD_RESET);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private int tokenTtlMinutes() {
        Integer configured = properties.tokenTtlMinutes();
        return configured != null ? configured : DEFAULT_TTL_MINUTES;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}
