package com.dunghaiquyen.ecommerce.visualsearch.outbox;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only operational snapshot for the shared integration-event platform. */
@Component
@Endpoint(id = "integrationevents")
public class IntegrationEventsEndpoint {

    private final JdbcTemplate jdbcTemplate;
    private final int maxAttempts;

    public IntegrationEventsEndpoint(
            JdbcTemplate jdbcTemplate,
            @Value("${app.catalog-events.outbox.max-attempts:10}") int maxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxAttempts = maxAttempts;
    }

    @ReadOperation
    public Map<String, Object> snapshot() {
        return jdbcTemplate.queryForMap("""
                select count(*) filter (where status = 'PENDING') as pending,
                       count(*) filter (where status = 'PUBLISHING') as publishing,
                       count(*) filter (where status = 'FAILED' and attempts < ?) as retryable_failed,
                       count(*) filter (where status = 'FAILED' and attempts >= ?) as exhausted,
                       count(*) filter (where status = 'PUBLISHED') as published
                  from integration_outbox
                """, maxAttempts, maxAttempts);
    }
}
