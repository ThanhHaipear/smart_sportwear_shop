package com.dunghaiquyen.catalog.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class CatalogOutboxRelayTest {
    @Test
    void publishesUtf8JsonInsteadOfJavaSerializedJsonNode() throws Exception {
        var mapper = new ObjectMapper();
        var relay = new CatalogOutboxRelay(
                mock(JdbcClient.class), mock(RabbitTemplate.class), mapper, "catalog.events");
        var id = UUID.fromString("90000000-0000-0000-0000-000000000001");
        var event = new CatalogOutboxRelay.Event(
                id,
                "product.reindex.requested",
                "{\"eventId\":\"90000000-0000-0000-0000-000000000001\",\"eventVersion\":1}");

        var message = relay.jsonMessage(event);

        assertThat(message.getBody()[0]).isEqualTo((byte) '{');
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"eventId\":\"90000000-0000-0000-0000-000000000001\",\"eventVersion\":1}");
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id.toString());
    }
}
