package com.dunghaiquyen.identity.security;

import com.dunghaiquyen.identity.config.IdentityProperties;
import com.dunghaiquyen.identity.domain.IdentityModels.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
    private final IdentityProperties.Jwt properties;
    private final SecretKey accessKey;
    private final SecretKey refreshKey;
    private final List<SecretKey> accessVerificationKeys;
    private final List<SecretKey> refreshVerificationKeys;

    public JwtService(IdentityProperties.Jwt properties) {
        this.properties = properties;
        this.accessKey = key(properties.accessSecret());
        this.refreshKey = key(properties.refreshSecret());
        this.accessVerificationKeys = keys(properties.accessSecret(), properties.previousAccessSecret());
        this.refreshVerificationKeys = keys(properties.refreshSecret(), properties.previousRefreshSecret());
    }

    public String access(UUID userId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder().id(UUID.randomUUID().toString()).subject(userId.toString())
                .claim("role", role.name()).claim("type", "access").issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtlMinutes(), ChronoUnit.MINUTES)))
                .signWith(accessKey).compact();
    }

    public String refresh(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder().id(UUID.randomUUID().toString()).subject(userId.toString())
                .claim("type", "refresh").issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.refreshTokenTtlDays(), ChronoUnit.DAYS)))
                .signWith(refreshKey).compact();
    }

    public Optional<Principal> parseAccess(String token) {
        return parse(token, accessVerificationKeys, "access").map(c ->
                new Principal(UUID.fromString(c.getSubject()), Role.valueOf(c.get("role", String.class))));
    }

    public Optional<UUID> parseRefresh(String token) {
        return parse(token, refreshVerificationKeys, "refresh").map(c -> UUID.fromString(c.getSubject()));
    }

    public long refreshTtlDays() { return properties.refreshTokenTtlDays(); }

    private Optional<Claims> parse(String token, List<SecretKey> keys, String type) {
        for (SecretKey candidate : keys) {
            try {
                Claims claims = Jwts.parser().verifyWith(candidate).build().parseSignedClaims(token).getPayload();
                if (type.equals(claims.get("type", String.class))) return Optional.of(claims);
            } catch (JwtException | IllegalArgumentException ignored) { }
        }
        return Optional.empty();
    }

    private static List<SecretKey> keys(String current, String previous) {
        List<SecretKey> result = new ArrayList<>(); result.add(key(current));
        if (previous != null && !previous.isBlank() && !previous.equals(current)) result.add(key(previous));
        return List.copyOf(result);
    }
    private static SecretKey key(String value) { return Keys.hmacShaKeyFor(value.getBytes(StandardCharsets.UTF_8)); }
    public record Principal(UUID userId, Role role) {}
}
