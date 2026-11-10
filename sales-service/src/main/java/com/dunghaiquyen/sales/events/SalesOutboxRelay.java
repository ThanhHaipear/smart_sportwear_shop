package com.dunghaiquyen.sales.events;

import com.dunghaiquyen.sales.config.SalesProperties;
import java.sql.Timestamp;import java.time.Instant;import java.util.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;import org.springframework.transaction.annotation.Transactional;

@Component
public class SalesOutboxRelay {
 private static final String EXCHANGE="business.events";private final JdbcTemplate jdbc;private final RabbitTemplate rabbit;private final SalesProperties properties;
 public SalesOutboxRelay(JdbcTemplate jdbc,RabbitTemplate rabbit,SalesProperties properties){this.jdbc=jdbc;this.rabbit=rabbit;this.properties=properties;}
 @Scheduled(fixedDelayString="${sales.outbox-delay-ms:1000}") @Transactional public void publish(){
  if(!properties.eventsEnabled())return;
  List<Event> events=jdbc.query("select id,event_type,payload::text,attempts from integration_outbox where status in ('PENDING','RETRY') and (next_attempt_at is null or next_attempt_at<=now()) order by occurred_at limit 50 for update skip locked",(r,n)->new Event(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getInt(4)));
  for(Event e:events)try{rabbit.convertAndSend(EXCHANGE,routingKey(e.type()),e.payload(),m->{m.getMessageProperties().setContentType("application/json");m.getMessageProperties().setMessageId(e.id().toString());return m;});jdbc.update("update integration_outbox set status='PUBLISHED',published_at=now(),attempts=attempts+1 where id=?",e.id());}
  catch(RuntimeException failure){int attempts=e.attempts()+1;String status=attempts>=8?"FAILED":"RETRY";Instant next=Instant.now().plusSeconds(Math.min(3600,1L<<Math.min(attempts,11)));jdbc.update("update integration_outbox set status=?,attempts=?,next_attempt_at=? where id=?",status,attempts,Timestamp.from(next),e.id());}
 }
 private String routingKey(String type){return switch(type){case "sales.order.created.v1"->"order.created";case "sales.order.cancelled.v1"->"order.cancelled";case "sales.payment.completed.v1"->"payment.completed";case "sales.refund.completed.v1"->"refund.completed";default->type;};}
 private record Event(UUID id,String type,String payload,int attempts){}
}
