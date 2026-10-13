package com.dunghaiquyen.ecommerce.visualsearch.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class CatalogOutboxServiceTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EntityManager entityManager = org.mockito.Mockito.mock(EntityManager.class);
    private final CatalogOutboxService service =
            new CatalogOutboxService(jdbcTemplate, objectMapper, entityManager);

    @Test
    void imageEventMatchesVersionOneContract() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(productId))).thenReturn(2L);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        UUID eventId = service.append(CatalogEventType.PRODUCT_IMAGE_CREATED, productId, imageId);

        verify(jdbcTemplate).update(any(String.class), args.capture());
        Object[] values = args.getValue();
        assertThat(values[0]).isEqualTo(eventId);
        assertThat(values[1]).isEqualTo("PRODUCT_IMAGE_CREATED:" + productId + ":" + eventId);
        assertThat(values[2]).isEqualTo("PRODUCT_IMAGE_CREATED");
        assertThat(values[3]).isEqualTo(1);
        assertThat(values[4]).isEqualTo(productId);
        assertThat(values[5]).isEqualTo(2L);
        JsonNode payload = objectMapper.readTree((String) values[6]);
        assertThat(payload.path("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(payload.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("productId").asText()).isEqualTo(productId.toString());
        assertThat(payload.path("sourceVersion").asLong()).isEqualTo(2L);
        assertThat(payload.path("imageId").asText()).isEqualTo(imageId.toString());
        assertThat(payload.path("traceId").asText()).isNotBlank();
        assertThat(payload.path("occurredAt").asText()).isNotBlank();
    }

    @Test
    void productEventOmitsImageId() throws Exception {
        UUID productId = UUID.randomUUID();
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(productId))).thenReturn(9L);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        service.append(CatalogEventType.PRODUCT_ACTIVATED, productId, null);

        verify(jdbcTemplate).update(any(String.class), args.capture());
        JsonNode payload = objectMapper.readTree((String) args.getValue()[6]);
        assertThat(payload.path("eventType").asText()).isEqualTo("PRODUCT_ACTIVATED");
        assertThat(payload.has("imageId")).isFalse();
    }
}
