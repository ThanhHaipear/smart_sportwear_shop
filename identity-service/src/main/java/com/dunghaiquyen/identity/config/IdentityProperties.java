package com.dunghaiquyen.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

public final class IdentityProperties {
    private IdentityProperties() {}
    @ConfigurationProperties("app.jwt")
    public record Jwt(String accessSecret, String refreshSecret, String previousAccessSecret,
                      String previousRefreshSecret, long accessTokenTtlMinutes, long refreshTokenTtlDays) {}
    @ConfigurationProperties("app.google")
    public record Google(String clientId, String tokenInfoUrl) {}
    @ConfigurationProperties("app.events")
    public record Events(boolean enabled, String exchange, long pollDelayMs) {}
    @ConfigurationProperties("app.password-reset")
    public record PasswordReset(boolean mailEnabled, String from, String publicUrl) {}
}
