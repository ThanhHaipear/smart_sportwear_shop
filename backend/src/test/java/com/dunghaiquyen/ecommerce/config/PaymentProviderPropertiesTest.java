package com.dunghaiquyen.ecommerce.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PaymentProviderPropertiesTest {

    @Test
    void disabledFeaturesAllowEmptyCredentialsAndUseSafeDefaults() {
        AppVietQrProperties vietQr = assertDoesNotThrow(
                () -> new AppVietQrProperties(false, "", "", "", null));
        AppSePayProperties sePay = assertDoesNotThrow(
                () -> new AppSePayProperties(false, "", null, "", "", false, null));

        assertFalse(vietQr.isEnabled());
        assertEquals(30, vietQr.effectivePaymentExpiryMinutes());
        assertFalse(sePay.isWebhookEnabled());
        assertFalse(sePay.isReconciliationEnabled());
        assertEquals(300, sePay.effectiveWebhookTimestampToleranceSeconds());
        assertEquals(300_000L, sePay.effectiveReconciliationDelayMs());
    }

    @Test
    void enabledVietQrRequiresValidBankConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new AppVietQrProperties(true, "", "1326045980", "TEST USER", 30));
        assertThrows(IllegalArgumentException.class,
                () -> new AppVietQrProperties(true, "970422", "not-an-account", "TEST USER", 30));
        assertThrows(IllegalArgumentException.class,
                () -> new AppVietQrProperties(true, "970422", "1326045980", "", 30));

        AppVietQrProperties properties = assertDoesNotThrow(
                () -> new AppVietQrProperties(true, "970422", "1326045980", "TEST USER", 30));
        assertEquals(30, properties.effectivePaymentExpiryMinutes());
    }

    @Test
    void enabledSepayFeaturesRequireTheirOwnCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> new AppSePayProperties(true, "", 300, "", "", false, 300_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new AppSePayProperties(false, "", 300, "", "", true, 300_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new AppSePayProperties(false, "", 300, "token", "http://localhost", true, 300_000L));

        assertDoesNotThrow(() -> new AppSePayProperties(
                true,
                "test-webhook-secret",
                300,
                "test-api-token",
                "https://userapi-sandbox.sepay.vn/v2",
                true,
                300_000L));
    }

    @Test
    void unsafeTimingConfigurationFailsFastEvenBeforeFeatureEnablement() {
        assertThrows(IllegalArgumentException.class,
                () -> new AppVietQrProperties(false, "", "", "", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AppSePayProperties(false, "", 10, "", "", false, 300_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new AppSePayProperties(false, "", 300, "", "", false, 10_000L));
    }
}
