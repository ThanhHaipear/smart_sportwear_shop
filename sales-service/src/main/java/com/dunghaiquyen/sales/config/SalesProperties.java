package com.dunghaiquyen.sales.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sales")
public record SalesProperties(
        String internalToken, String catalogBaseUrl, String catalogToken, boolean eventsEnabled, long outboxDelayMs,
        VietQr vietqr, SePay sepay) {
    public record VietQr(String accountNumber, long paymentTtlSeconds) {}
    public record SePay(boolean webhookEnabled, String webhookSecret, long timestampToleranceSeconds) {}
}
