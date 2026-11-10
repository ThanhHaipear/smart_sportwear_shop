package com.dunghaiquyen.identity.events;

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
@ConditionalOnProperty(name="app.events.enabled",havingValue="true")
public class IdentityOutboxRelay {
    private final JdbcClient jdbc; private final RabbitTemplate rabbit; private final ObjectMapper mapper; private final String exchange;
    public IdentityOutboxRelay(JdbcClient jdbc,RabbitTemplate rabbit,ObjectMapper mapper,@Value("${app.events.exchange:identity.events}")String exchange){this.jdbc=jdbc;this.rabbit=rabbit;this.mapper=mapper;this.exchange=exchange;rabbit.setMandatory(true);}
    record Event(UUID id,String type,String payload){}
    @Scheduled(fixedDelayString="${app.events.poll-delay-ms:1000}") @Transactional public void relay(){
        List<Event> events=jdbc.sql("select id,event_type,payload::text payload from integration_outbox where status='PENDING' and (next_attempt_at is null or next_attempt_at<=now()) order by occurred_at limit 25 for update skip locked").query((r,n)->new Event(r.getObject(1,UUID.class),r.getString(2),r.getString(3))).list();
        events.forEach(this::publish);
    }
    private void publish(Event event){try{JsonNode payload=mapper.readTree(event.payload());CorrelationData c=new CorrelationData(event.id().toString());rabbit.convertAndSend(exchange,event.type(),payload,m->{m.getMessageProperties().setMessageId(event.id().toString());m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);return m;},c);var confirm=c.getFuture().get(Duration.ofSeconds(5).toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);if(!confirm.isAck()||c.getReturned()!=null)throw new IllegalStateException("RabbitMQ did not confirm event");jdbc.sql("update integration_outbox set status='PUBLISHED',published_at=now() where id=:id").param("id",event.id()).update();}catch(Exception e){jdbc.sql("update integration_outbox set attempts=attempts+1,next_attempt_at=now()+interval '30 seconds' where id=:id").param("id",event.id()).update();}}
}
