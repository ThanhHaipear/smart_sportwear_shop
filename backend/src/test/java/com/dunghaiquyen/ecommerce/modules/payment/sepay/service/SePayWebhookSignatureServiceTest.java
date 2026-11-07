package com.dunghaiquyen.ecommerce.modules.payment.sepay.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.config.AppSePayProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SePayWebhookSignatureServiceTest {

    private static final String SECRET = "unit-test-sepay-webhook-secret";
    private final SePayWebhookSignatureService service = new SePayWebhookSignatureService(
            new AppSePayProperties(true, SECRET, 300, null, null, false, 300_000L));

    @Test
    void acceptsSignatureOverTimestampDotExactRawBody() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] rawBody = "{\"id\":1, \"content\":\"DHABCDEFG234\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatCode(() -> service.verify(sign(timestamp, rawBody), timestamp, rawBody))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBodyThatChangedAfterSigning() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] signedBody = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] changedBody = "{ \"id\":1}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.verify(sign(timestamp, signedBody), timestamp, changedBody))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Invalid SePay webhook signature");
    }

    @Test
    void rejectsExpiredOrMalformedTimestamp() throws Exception {
        String expired = Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        byte[] rawBody = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.verify(sign(expired, rawBody), expired, rawBody))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Expired SePay webhook timestamp");
        assertThatThrownBy(() -> service.verify("sha256=" + "0".repeat(64), "invalid", rawBody))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Invalid SePay webhook timestamp");
    }

    private String sign(String timestamp, byte[] rawBody) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
    }
}
