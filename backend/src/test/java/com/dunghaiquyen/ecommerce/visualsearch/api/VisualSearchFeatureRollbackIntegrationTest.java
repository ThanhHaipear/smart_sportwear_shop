package com.dunghaiquyen.ecommerce.visualsearch.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = {
        "app.visual-search.enabled=false",
        "app.catalog-events.enabled=false"
})
class VisualSearchFeatureRollbackIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    private VisualSearchClient visualSearchClient;

    @Test
    void disabledFlagClosesImageSearchWithoutCallingTheInternalService() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "query.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/products/search-by-image").file(image))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/v1/visual-search/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.available").value(false));

        verify(visualSearchClient, never()).search(any(), anyInt());
        verify(visualSearchClient, never()).isReady();
    }

    @Test
    void disabledFlagLeavesPublicCatalogSearchAvailable() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("q", "shirt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }
}
