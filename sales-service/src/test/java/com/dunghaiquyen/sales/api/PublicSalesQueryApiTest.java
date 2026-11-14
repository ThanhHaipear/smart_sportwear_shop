package com.dunghaiquyen.sales.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.sales.config.SalesProperties;
import com.dunghaiquyen.sales.persistence.SalesQueryRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicSalesQueryApiTest {
    @Test
    void reloadingVietQrUsesConfiguredMerchantBankDetails() {
        SalesQueryRepository repository = mock(SalesQueryRepository.class);
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("paymentId", paymentId);
        payment.put("bankBin", "970422");
        payment.put("accountNumber", "0123456789");
        payment.put("accountName", "SHOP OWNER");
        when(repository.vietQr(userId, paymentId, "0123456789", "970422", "SHOP OWNER"))
                .thenReturn(Optional.of(payment));

        PublicSalesQueryApi api = api(repository, "0123456789", "970422", "SHOP OWNER");

        assertThat(api.vietQr(userId, paymentId).data()).containsAllEntriesOf(payment);
        verify(repository).vietQr(userId, paymentId, "0123456789", "970422", "SHOP OWNER");
    }

    @Test
    void rejectsIncompleteVietQrConfiguration() {
        PublicSalesQueryApi api = api(mock(SalesQueryRepository.class), "", "", "");

        assertThatThrownBy(() -> api.vietQr(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("VietQR bank configuration is incomplete");
    }

    private PublicSalesQueryApi api(SalesQueryRepository repository, String accountNumber, String bankBin, String accountName) {
        SalesProperties properties = new SalesProperties(
                "internal", "http://catalog", "catalog", false, 1000,
                new SalesProperties.VietQr(accountNumber, 900),
                new SalesProperties.SePay(false, "secret", 300));
        return new PublicSalesQueryApi(repository, properties, bankBin, accountName, mock(NotificationStreamService.class));
    }
}
