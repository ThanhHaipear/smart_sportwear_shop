package com.dunghaiquyen.ecommerce.modules.payment.sepay.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.config.AppSePayProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SePayWebhookSignatureService {

    private static final String SIGNATURE_PREFIX = "sha256=";
    private final AppSePayProperties properties;

    public SePayWebhookSignatureService(AppSePayProperties properties) {
        this.properties = properties;
    }

    public void verify(String signature, String timestampValue, byte[] rawBody) {
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampValue);
        } catch (NumberFormatException exception) {
            throw unauthorized("Invalid SePay webhook timestamp");
        }

        long now = Instant.now().getEpochSecond();
        long tolerance = properties.effectiveWebhookTimestampToleranceSeconds();
        if (timestamp < now - tolerance || timestamp > now + tolerance) {
            throw unauthorized("Expired SePay webhook timestamp");
        }
        if (signature == null || !signature.startsWith(SIGNATURE_PREFIX)
                || signature.length() != SIGNATURE_PREFIX.length() + 64) {
            throw unauthorized("Invalid SePay webhook signature");
        }

        byte[] suppliedDigest;
        try {
            suppliedDigest = HexFormat.of().parseHex(signature.substring(SIGNATURE_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw unauthorized("Invalid SePay webhook signature");
        }
        byte[] expectedDigest = sign(timestampValue, rawBody);
        if (!MessageDigest.isEqual(expectedDigest, suppliedDigest)) {
            throw unauthorized("Invalid SePay webhook signature");
        }
    }

    private byte[] sign(String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private BusinessRuleException unauthorized(String message) {
        return new BusinessRuleException(HttpStatus.UNAUTHORIZED, message);
    }
}
