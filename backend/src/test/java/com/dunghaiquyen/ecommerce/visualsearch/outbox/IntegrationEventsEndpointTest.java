package com.dunghaiquyen.ecommerce.visualsearch.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IntegrationEventsEndpointTest {

    @Test
    void reportsRetryableAndExhaustedOutboxSeparately() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Map<String, Object> expected = Map.of(
                "pending", 2L,
                "publishing", 1L,
                "retryable_failed", 3L,
                "exhausted", 4L,
                "published", 5L);
        when(jdbcTemplate.queryForMap(contains("attempts >= ?"), eq(10), eq(10))).thenReturn(expected);
        IntegrationEventsEndpoint endpoint = new IntegrationEventsEndpoint(jdbcTemplate, 10);

        assertThat(endpoint.snapshot()).isEqualTo(expected);
        verify(jdbcTemplate).queryForMap(contains("attempts < ?"), eq(10), eq(10));
    }
}
