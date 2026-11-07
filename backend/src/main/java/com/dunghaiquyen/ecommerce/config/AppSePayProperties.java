package com.dunghaiquyen.ecommerce.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.sepay")
public record AppSePayProperties(
        Boolean webhookEnabled,
        String webhookSecret,
        Integer webhookTimestampToleranceSeconds,
        String apiToken,
        String apiBaseUrl,
        Boolean reconciliationEnabled,
        Long reconciliationDelayMs) {

    private static final int DEFAULT_TIMESTAMP_TOLERANCE_SECONDS = 300;
    private static final long DEFAULT_RECONCILIATION_DELAY_MS = 300_000L;

    public AppSePayProperties {
        if (webhookTimestampToleranceSeconds != null
                && (webhookTimestampToleranceSeconds < 30 || webhookTimestampToleranceSeconds > 900)) {
            throw new IllegalArgumentException(
                    "app.sepay.webhook-timestamp-tolerance-seconds must be between 30 and 900");
        }
        if (reconciliationDelayMs != null && reconciliationDelayMs < 60_000L) {
            throw new IllegalArgumentException("app.sepay.reconciliation-delay-ms must be at least 60000");
        }
        if (Boolean.TRUE.equals(webhookEnabled)) {
            requireText(webhookSecret, "app.sepay.webhook-secret is required when the webhook is enabled");
        }
        if (Boolean.TRUE.equals(reconciliationEnabled)) {
            requireText(apiToken, "app.sepay.api-token is required when reconciliation is enabled");
            requireHttpsUrl(apiBaseUrl);
        }
    }

    public boolean isWebhookEnabled() {
        return Boolean.TRUE.equals(webhookEnabled);
    }

    public boolean isReconciliationEnabled() {
        return Boolean.TRUE.equals(reconciliationEnabled);
    }

    public int effectiveWebhookTimestampToleranceSeconds() {
        return webhookTimestampToleranceSeconds == null
                ? DEFAULT_TIMESTAMP_TOLERANCE_SECONDS : webhookTimestampToleranceSeconds;
    }

    public long effectiveReconciliationDelayMs() {
        return reconciliationDelayMs == null ? DEFAULT_RECONCILIATION_DELAY_MS : reconciliationDelayMs;
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireHttpsUrl(String value) {
        requireText(value, "app.sepay.api-base-url is required when reconciliation is enabled");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("app.sepay.api-base-url must be a valid HTTPS URL", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("app.sepay.api-base-url must be a valid HTTPS URL");
        }
    }
}
