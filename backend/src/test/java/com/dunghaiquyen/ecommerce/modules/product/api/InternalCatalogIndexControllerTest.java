package com.dunghaiquyen.ecommerce.modules.product.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.ecommerce.modules.product.service.CatalogIndexDocumentService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InternalCatalogIndexControllerTest {
    @Mock CatalogIndexDocumentService service;
    @InjectMocks InternalCatalogIndexController controller;

    @Test
    void rejectsMissingServiceToken() {
        ReflectionTestUtils.setField(controller, "expectedToken", "secret");
        assertThat(controller.get(UUID.randomUUID(), "").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void returnsVersionedIndexDocument() {
        UUID id = UUID.randomUUID();
        var document = new CatalogIndexDocumentResponse(
                id, 7, "ACTIVE", "Áo chạy", "Nhẹ", UUID.randomUUID(), "Áo", "ao",
                UUID.randomUUID(), "Demo", "demo", "APPAREL",
                "UNISEX", List.of("RUNNING"), Map.of(), List.of(), List.of());
        when(service.find(id)).thenReturn(document);
        ReflectionTestUtils.setField(controller, "expectedToken", "secret");

        var response = controller.get(id, "secret");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().sourceVersion()).isEqualTo(7);
    }
}
