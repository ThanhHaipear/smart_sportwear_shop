package com.dunghaiquyen.ecommerce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.vietqr")
public record AppVietQrProperties(
        Boolean enabled,
        String bankBin,
        String accountNumber,
        String accountName,
        Integer paymentExpiryMinutes) {

    private static final int DEFAULT_EXPIRY_MINUTES = 30;
    private static final int MAX_EXPIRY_MINUTES = 24 * 60;

    public AppVietQrProperties {
        if (Boolean.TRUE.equals(enabled)) {
            requireMatches(bankBin, "\\d{6}", "app.vietqr.bank-bin must contain exactly 6 digits");
            requireMatches(accountNumber, "\\d{6,19}",
                    "app.vietqr.account-number must contain between 6 and 19 digits");
            requireText(accountName, "app.vietqr.account-name is required when VietQR is enabled");
        }
        if (paymentExpiryMinutes != null
                && (paymentExpiryMinutes < 1 || paymentExpiryMinutes > MAX_EXPIRY_MINUTES)) {
            throw new IllegalArgumentException(
                    "app.vietqr.payment-expiry-minutes must be between 1 and " + MAX_EXPIRY_MINUTES);
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public int effectivePaymentExpiryMinutes() {
        return paymentExpiryMinutes == null ? DEFAULT_EXPIRY_MINUTES : paymentExpiryMinutes;
    }

    private static void requireMatches(String value, String regex, String message) {
        if (value == null || !value.matches(regex)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
