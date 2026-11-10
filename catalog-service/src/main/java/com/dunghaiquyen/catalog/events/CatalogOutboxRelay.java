package com.dunghaiquyen.catalog.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true")
public class CatalogOutboxRelay {
    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final String exchange;

    public CatalogOutboxRelay(JdbcClient jdbc, RabbitTemplate rabbit, ObjectMapper mapper,
                              @Value("${app.events.exchange:catalog.events}") String exchange) {
        this.jdbc = jdbc; this.rabbit = rabbit; this.mapper = mapper; this.exchange = exchange;
        rabbit.setMandatory(true);
    }

    record Event(UUID id, String eventType, String payload) {}

    @Scheduled(fixedDelayString = "${app.events.poll-delay-ms:1000}")
    @Transactional
    public void relay() {
        List<Event> events = jdbc.sql("""
                select id,event_type,payload::text payload from integration_outbox
                 where status='PENDING' and (next_attempt_at is null or next_attempt_at<=now())
                 order by occurred_at limit 25 for update skip locked
                """).query((rs,n) -> new Event(rs.getObject("id",UUID.class),rs.getString("event_type"),rs.getString("payload"))).list();
        for (Event event : events) publish(event);
    }

    private void publish(Event event) {
        try {
            JsonNode payload = mapper.readTree(event.payload());
            CorrelationData correlation = new CorrelationData(event.id().toString());
            rabbit.convertAndSend(exchange, event.eventType(), payload, message -> {
                message.getMessageProperties().setMessageId(event.id().toString());
                message.getMessageProperties().setContentType("application/json");
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }, correlation);
            var confirm = correlation.getFuture().get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("RabbitMQ did not confirm event");
            jdbc.sql("update integration_outbox set status='PUBLISHED',published_at=now() where id=:id")
                    .param("id",event.id()).update();
        } catch (Exception failure) {
            jdbc.sql("update integration_outbox set attempts=attempts+1,next_attempt_at=now()+interval '30 seconds' where id=:id")
                    .param("id",event.id()).update();
        }
    }
}
