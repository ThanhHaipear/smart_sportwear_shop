package com.dunghaiquyen.catalog;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.catalog.api.CatalogApi;
import com.dunghaiquyen.catalog.config.InternalSecurityFilter;
import com.dunghaiquyen.catalog.domain.InventoryReservationService;
import com.dunghaiquyen.catalog.persistence.CatalogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogApiSecurityTest {
    @Test void internalApiRejectsMissingTokenAndReturnsCorrelationId() throws Exception {
        var controller = new CatalogApi(mock(CatalogRepository.class), mock(InventoryReservationService.class));
        var filter = new InternalSecurityFilter("secret", new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();
        mvc.perform(get("/internal/v1/variants/10000000-0000-0000-0000-000000000001/sale-snapshot"))
                .andExpect(status().isUnauthorized()).andExpect(header().exists("X-Correlation-Id"));
    }
}
