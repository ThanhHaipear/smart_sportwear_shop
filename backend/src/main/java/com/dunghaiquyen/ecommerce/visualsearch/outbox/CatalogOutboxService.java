package com.dunghaiquyen.ecommerce.visualsearch.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CatalogOutboxService {

    private static final int EVENT_VERSION = 1;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public CatalogOutboxService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    /**
     * Must be called from the catalog mutation transaction. JdbcTemplate joins
     * that transaction, so the catalog row and its event commit or roll back together.
     */
    public UUID append(CatalogEventType type, UUID productId, UUID imageId) {
        // In particular, a newly-created ACTIVE product may not have been
        // INSERTed yet. Flush first so the following atomic version bump always
        // sees the aggregate and all event-visible catalog mutations.
        entityManager.flush();
        UUID eventId = UUID.randomUUID();
        Long sourceVersion = jdbcTemplate.queryForObject("""
                update products
                   set catalog_version = catalog_version + 1
                 where id = ?
             returning catalog_version
                """, Long.class, productId);
        if (sourceVersion == null) {
            throw new IllegalStateException("Cannot append catalog event for missing product " + productId);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", type.name());
        payload.put("eventVersion", EVENT_VERSION);
        payload.put("productId", productId);
        payload.put("sourceVersion", sourceVersion);
        if (imageId != null) {
            payload.put("imageId", imageId);
        }
        payload.put("occurredAt", Instant.now().toString());
        payload.put("traceId", currentTraceId(eventId));

        jdbcTemplate.update("""
                insert into integration_outbox
                    (id, idempotency_key, event_type, event_version, aggregate_type,
                     aggregate_id, aggregate_version, payload)
                values (?, ?, ?, ?, 'PRODUCT', ?, ?, cast(? as jsonb))
                """,
                eventId, type.name() + ":" + productId + ":" + eventId,
                type.name(), EVENT_VERSION, productId, sourceVersion, toJson(payload));
        return eventId;
    }

    private String currentTraceId(UUID fallback) {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? fallback.toString() : traceId;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize catalog event", ex);
        }
    }
}
