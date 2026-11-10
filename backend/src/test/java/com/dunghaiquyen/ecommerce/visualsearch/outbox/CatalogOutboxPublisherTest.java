package com.dunghaiquyen.ecommerce.visualsearch.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class CatalogOutboxPublisherTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final CatalogOutboxPublisher publisher = new CatalogOutboxPublisher(
            jdbcTemplate, mock(TransactionTemplate.class), rabbitTemplate, 25, 1, 3);

    @Test
    void confirmedPersistentMessageMarksOutboxPublished() {
        UUID eventId = UUID.randomUUID();
        CatalogOutboxPublisher.OutboxRecord record = new CatalogOutboxPublisher.OutboxRecord(
                eventId, CatalogEventType.PRODUCT_ACTIVATED, "{}", 0);
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            org.assertj.core.api.Assertions.assertThat(message.getMessageProperties().getMessageId())
                    .isEqualTo(eventId.toString());
            org.assertj.core.api.Assertions.assertThat(message.getMessageProperties().getType())
                    .isEqualTo("PRODUCT_ACTIVATED");
            org.assertj.core.api.Assertions.assertThat(message.getMessageProperties().getHeaders())
                    .containsEntry("eventVersion", 1);
            return null;
        }).when(rabbitTemplate).send(eq("catalog.events"), eq("product.activated"), any(Message.class), any(CorrelationData.class));

        publisher.publish(record);

        verify(jdbcTemplate).update(contains("status = 'PUBLISHED'"), eq(eventId));
    }

    @Test
    void transientFailureSchedulesBoundedRetry() {
        UUID eventId = UUID.randomUUID();
        CatalogOutboxPublisher.OutboxRecord record = new CatalogOutboxPublisher.OutboxRecord(
                eventId, CatalogEventType.PRODUCT_ACTIVATED, "{}", 0);
        publisher.markFailed(record, new IllegalStateException("broker unavailable"));

        verify(jdbcTemplate).update(
                contains("interval '1 second'"), eq(2L), eq("broker unavailable"), eq(eventId));
    }

    @Test
    void finalFailureIsMarkedExhaustedWithoutAnotherRetry() {
        UUID eventId = UUID.randomUUID();
        CatalogOutboxPublisher.OutboxRecord record = new CatalogOutboxPublisher.OutboxRecord(
                eventId, CatalogEventType.PRODUCT_ACTIVATED, "{}", 2);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);

        publisher.markFailed(record, new IllegalStateException("broker unavailable"));

        verify(jdbcTemplate).update(contains("available_at = now()"), error.capture(), eq(eventId));
        org.assertj.core.api.Assertions.assertThat(error.getValue())
                .startsWith("EXHAUSTED after 3 attempts:");
    }
}
