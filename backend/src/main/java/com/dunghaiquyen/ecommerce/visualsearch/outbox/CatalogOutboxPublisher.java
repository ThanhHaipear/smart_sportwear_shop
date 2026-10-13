package com.dunghaiquyen.ecommerce.visualsearch.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix = "app.catalog-events", name = "enabled", havingValue = "true")
public class CatalogOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(CatalogOutboxPublisher.class);
    private static final String EXCHANGE = "catalog.events";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final int confirmTimeoutSeconds;
    private final int maxAttempts;

    public CatalogOutboxPublisher(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            RabbitTemplate rabbitTemplate,
            @Value("${app.catalog-events.outbox.batch-size:25}") int batchSize,
            @Value("${app.catalog-events.outbox.confirm-timeout-seconds:5}") int confirmTimeoutSeconds,
            @Value("${app.catalog-events.outbox.max-attempts:10}") int maxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;
        this.confirmTimeoutSeconds = confirmTimeoutSeconds;
        this.maxAttempts = maxAttempts;
        this.rabbitTemplate.setMandatory(true);
    }

    @Scheduled(fixedDelayString = "${app.catalog-events.outbox.poll-delay-ms:1000}")
    public void publishAvailable() {
        for (OutboxRecord record : claimBatch()) {
            publish(record);
        }
    }

    List<OutboxRecord> claimBatch() {
        return transactionTemplate.execute(status -> {
            List<OutboxRecord> records = jdbcTemplate.query("""
                    select id, event_type, payload::text, attempts
                    from integration_outbox
                    where status in ('PENDING', 'FAILED', 'PUBLISHING')
                      and aggregate_type = 'PRODUCT'
                      and event_type in (
                          'PRODUCT_IMAGE_CREATED', 'PRODUCT_IMAGE_DELETED',
                          'PRODUCT_ACTIVATED', 'PRODUCT_DEACTIVATED',
                          'PRODUCT_DELETED', 'PRODUCT_REINDEX_REQUESTED'
                      )
                      and available_at <= now()
                      and attempts < ?
                    order by created_at
                    for update skip locked
                    limit ?
                    """,
                    (ResultSet rs, int rowNum) -> new OutboxRecord(
                            rs.getObject("id", UUID.class),
                            CatalogEventType.valueOf(rs.getString("event_type")),
                            rs.getString("payload"),
                            rs.getInt("attempts")),
                    maxAttempts, batchSize);
            for (OutboxRecord record : records) {
                jdbcTemplate.update("""
                        update integration_outbox
                        set status = 'PUBLISHING', attempts = attempts + 1,
                            available_at = now() + interval '30 seconds', last_error = null
                        where id = ?
                        """, record.id());
            }
            return records;
        });
    }

    private void publish(OutboxRecord record) {
        try {
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(record.id().toString());
            Message message = new Message(record.payload().getBytes(StandardCharsets.UTF_8), properties);
            CorrelationData correlation = new CorrelationData(record.id().toString());

            rabbitTemplate.send(EXCHANGE, record.type().routingKey(), message, correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeoutSeconds, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("Broker nack: " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("Message was unroutable: " + correlation.getReturned().getReplyText());
            }
            jdbcTemplate.update("""
                    update integration_outbox
                    set status = 'PUBLISHED', published_at = now(), last_error = null
                    where id = ? and status = 'PUBLISHING'
                    """, record.id());
        } catch (Exception ex) {
            int attempt = record.attempts() + 1;
            long backoffSeconds = Math.min(300, 1L << Math.min(attempt, 8));
            jdbcTemplate.update("""
                    update integration_outbox
                    set status = 'FAILED',
                        available_at = now() + (? * interval '1 second'),
                        last_error = left(?, 4000)
                    where id = ? and status = 'PUBLISHING'
                    """, backoffSeconds, ex.getMessage(), record.id());
            log.warn("Catalog outbox event {} publish failed (attempt {}): {}", record.id(), attempt, ex.getMessage());
        }
    }

    record OutboxRecord(UUID id, CatalogEventType type, String payload, int attempts) {
    }
}
