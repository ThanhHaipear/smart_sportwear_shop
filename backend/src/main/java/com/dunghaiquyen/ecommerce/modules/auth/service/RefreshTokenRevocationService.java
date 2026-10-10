package com.dunghaiquyen.ecommerce.modules.auth.service;

import com.dunghaiquyen.ecommerce.modules.auth.entity.RefreshTokenRevocationReason;
import com.dunghaiquyen.ecommerce.modules.auth.repository.RefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean/transaction on purpose: AuthService.refresh() throws right
 * after detecting reuse of a revoked token, and a thrown RuntimeException
 * rolls back the @Transactional method it was thrown from. Without
 * REQUIRES_NEW here, that rollback would silently undo the revoke-all
 * security action together with the rejection - the bug this class exists to
 * avoid (caught by manual reuse-detection testing, not by compilation).
 */
@Service
public class RefreshTokenRevocationService {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenRevocationService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllActiveForUser(UUID userId) {
        refreshTokenRepository.revokeAllActiveForUser(
                userId, Instant.now(), RefreshTokenRevocationReason.REUSE_DETECTED);
    }
}
