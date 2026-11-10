package com.dunghaiquyen.ecommerce.visualsearch.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class CatalogEventContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void producerEventTypesAndVersionMatchSharedContract() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(locateContract()));
        Set<String> declaredTypes = StreamSupport.stream(
                        schema.at("/properties/eventType/enum").spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
        Set<String> producerTypes = java.util.Arrays.stream(CatalogEventType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(schema.at("/properties/eventVersion/const").asInt()).isEqualTo(1);
        assertThat(declaredTypes).isEqualTo(producerTypes);
        assertThat(schema.at("/additionalProperties").asBoolean()).isFalse();
        assertThat(StreamSupport.stream(schema.path("required").spliterator(), false)
                        .map(JsonNode::asText)
                        .collect(Collectors.toSet()))
                .contains("eventId", "eventType", "eventVersion", "productId", "sourceVersion", "occurredAt", "traceId");
    }

    private Path locateContract() {
        Path fromBackend = Path.of("..", "contracts", "catalog-event-v1.schema.json");
        Path fromRoot = Path.of("contracts", "catalog-event-v1.schema.json");
        if (Files.isRegularFile(fromBackend)) {
            return fromBackend;
        }
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("Cannot locate catalog event v1 contract");
    }
}
