package com.dunghaiquyen.ecommerce.modules.auth.repository;

import com.dunghaiquyen.ecommerce.modules.auth.entity.RefreshToken;
import com.dunghaiquyen.ecommerce.modules.auth.entity.RefreshTokenRevocationReason;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Used when a refresh token reuse is detected (an already-revoked token is
     * presented again): kill every other active session for that user too,
     * since the token may have been stolen.
     *
     * Known tradeoff: this also fires when a request simply lost the one-time-use
     * race below (see revokeIfActive) rather than a genuine stale-token replay -
     * the schema has no parent/child link between a token and its rotated
     * successor, so there is no race-free way to tell the two apart without
     * adding one. Left unscoped intentionally: narrowing it by timestamp was
     * tried and rejected because it also excluded a user's later legitimate
     * sessions from a genuine theft response (verified by testing), which is a
     * worse failure mode than the rare case this causes (a concurrent duplicate
     * /refresh call forcing re-login).
     */
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now, r.revocationReason = :reason where r.user.id = :userId and r.revokedAt is null")
    void revokeAllActiveForUser(
            @Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("reason") RefreshTokenRevocationReason reason);

    /**
     * Atomic claim-and-revoke for one-time-use rotation: Postgres re-checks the
     * WHERE clause against the committed row when it acquires the row lock, so
     * if two requests race on the same token, exactly one UPDATE matches
     * (returns 1) and the other matches zero rows (returns 0) - there is no
     * read-then-write gap for two concurrent requests to both pass through.
     */
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now, r.revocationReason = :reason where r.id = :id and r.revokedAt is null")
    int revokeIfActive(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("reason") RefreshTokenRevocationReason reason);
}
