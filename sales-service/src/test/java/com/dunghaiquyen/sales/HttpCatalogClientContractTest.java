package com.dunghaiquyen.sales;

import static com.dunghaiquyen.sales.domain.SalesModels.OrderLineRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dunghaiquyen.sales.catalog.HttpCatalogClient;
import com.dunghaiquyen.sales.config.SalesProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpCatalogClientContractTest {
    @Test
    void reservationUsesCatalogV1FieldNames() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = new SalesProperties("sales-token", "http://catalog", "catalog-token", false, 1000,
                new SalesProperties.VietQr("123", 900), new SalesProperties.SePay(false, "secret", 300));
        var client = new HttpCatalogClient(builder, properties);
        UUID orderId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        server.expect(requestTo("http://catalog/internal/v1/inventory/reservations"))
                .andExpect(header("X-Internal-Service-Token", "catalog-token"))
                .andExpect(content().json("""
                        {"orderId":"%s","idempotencyKey":"sales-order:%s","items":[{"variantId":"%s","quantity":2}]}
                        """.formatted(orderId, orderId, variantId)))
                .andRespond(withSuccess("""
                        {"data":{"reservationId":"%s","expiresAt":"2026-11-11T12:00:00Z"}}
                        """.formatted(reservationId), MediaType.APPLICATION_JSON));

        var reservation = client.reserve(orderId, List.of(new OrderLineRequest(variantId, 2)));

        assertThat(reservation.id()).isEqualTo(reservationId);
        server.verify();
    }
}
