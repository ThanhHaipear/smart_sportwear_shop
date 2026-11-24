package com.dunghaiquyen.sales.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.sales.config.SalesProperties;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SalesOutboxRelayTest {
    @ParameterizedTest
    @CsvSource({"true,false,0,PUBLISHED", "false,false,0,RETRY", "true,true,0,RETRY", "true,true,7,FAILED"})
    void updatesOutboxOnlyAfterBrokerConfirmation(boolean ack, boolean returned, int attempts, String expected)
            throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        SalesProperties properties = mock(SalesProperties.class);
        ResultSet row = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        String payload = "{\"eventType\":\"ORDER_CREATED\"}";
        when(properties.eventsEnabled()).thenReturn(true);
        when(row.getObject(1, UUID.class)).thenReturn(id);
        when(row.getString(2)).thenReturn("sales.order.created.v1");
        when(row.getString(3)).thenReturn(payload);
        when(row.getInt(4)).thenReturn(attempts);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(row, 0)));
        doAnswer(call -> {
            Message message = call.getArgument(2);
            CorrelationData correlation = call.getArgument(3);
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id.toString());
            verify(jdbc, never()).update(contains("status='PUBLISHED'"), eq(id));
            if (returned) correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "business.events", "order.created"));
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(rabbit).send(eq("business.events"), eq("order.created"), any(Message.class), any(CorrelationData.class));

        new SalesOutboxRelay(jdbc, rabbit, properties).publish();

        if (expected.equals("PUBLISHED")) {
            verify(jdbc).update(contains("status='PUBLISHED'"), eq(id));
        } else {
            verify(jdbc, never()).update(contains("status='PUBLISHED'"), eq(id));
            verify(jdbc).update(contains("status=?"), eq(expected), eq(attempts + 1), any(Timestamp.class), eq(id));
        }
    }
}
