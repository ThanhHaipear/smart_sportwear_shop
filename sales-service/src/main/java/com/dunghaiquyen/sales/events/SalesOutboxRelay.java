package com.dunghaiquyen.sales.events;

import com.dunghaiquyen.sales.config.SalesProperties;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SalesOutboxRelay {
    private static final String EXCHANGE = "business.events";
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final SalesProperties properties;

    public SalesOutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, SalesProperties properties) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${sales.outbox-delay-ms:1000}")
    @Transactional
    public void publish() {
        if (!properties.eventsEnabled()) return;

        var events = jdbc.query("""
                select id,event_type,payload::text,attempts from integration_outbox
                where status in ('PENDING','RETRY') and (next_attempt_at is null or next_attempt_at<=now())
                order by occurred_at limit 50 for update skip locked
                """, (row, index) -> new Event(row.getObject(1, UUID.class), row.getString(2),
                    row.getString(3), row.getInt(4)));
        for (Event event : events) {
            try {
                send(event);
            } catch (InterruptedException interrupted) {
                retry(event);
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException failure) {
                retry(event);
                continue;
            }
            jdbc.update("""
                    update integration_outbox set status='PUBLISHED',published_at=now(),attempts=attempts+1 where id=?
                    """, event.id());
        }
    }

    private void send(Event event) throws InterruptedException, ExecutionException, TimeoutException {
        var correlation = new CorrelationData(event.id().toString());
        var properties = new MessageProperties();
        properties.setMessageId(event.id().toString());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var message = new Message(event.payload().getBytes(StandardCharsets.UTF_8), properties);
        rabbit.send(EXCHANGE, routingKey(event.type()), message, correlation);
        var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        // Order creation has a required Recommendation subscriber. Other Sales topics currently allow
        // zero subscribers so adding confirms does not change their existing delivery semantics.
        if (!confirm.isAck() || (event.type().equals("sales.order.created.v1") && correlation.getReturned() != null)) {
            throw new IllegalStateException("RabbitMQ did not confirm Sales event delivery");
        }
    }

    private void retry(Event event) {
        int attempts = event.attempts() + 1;
        String status = attempts >= 8 ? "FAILED" : "RETRY";
        Instant next = Instant.now().plusSeconds(Math.min(3600, 1L << Math.min(attempts, 11)));
        jdbc.update("update integration_outbox set status=?,attempts=?,next_attempt_at=? where id=?",
                status, attempts, Timestamp.from(next), event.id());
    }

    private String routingKey(String type) {
        return switch (type) {
            case "sales.order.created.v1" -> "order.created";
            case "sales.order.cancelled.v1" -> "order.cancelled";
            case "sales.payment.completed.v1" -> "payment.completed";
            case "sales.refund.completed.v1" -> "refund.completed";
            default -> type;
        };
    }

    private record Event(UUID id, String type, String payload, int attempts) {}
}
