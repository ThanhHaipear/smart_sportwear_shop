package com.dunghaiquyen.ecommerce.visualsearch.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = {
        "app.visual-search.enabled=true",
        "app.catalog-events.enabled=false"
})
class VisualSearchPublicApiIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    private VisualSearchClient visualSearchClient;

    @Test
    void publicMultipartEndpointIsAnonymousBoundsLimitAndReturnsEnvelope() throws Exception {
        when(visualSearchClient.search(any(), eq(VisualSearchService.CANDIDATE_LIMIT))).thenReturn(List.of());
        MockMultipartFile image = image("query.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/products/search-by-image")
                        .file(image)
                        .param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(visualSearchClient).search(any(), eq(VisualSearchService.CANDIDATE_LIMIT));
    }

    @Test
    void invalidMimeTypeReturns422BeforeCallingInternalService() throws Exception {
        mockMvc.perform(multipart("/api/v1/products/search-by-image")
                        .file(image("query.gif", "image/gif", new byte[]{1})))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false));

        verify(visualSearchClient, never()).search(any(), anyInt());
    }

    @Test
    void imageOverFiveMegabytesReturns422BeforeCallingInternalService() throws Exception {
        byte[] content = new byte[(int) VisualSearchService.MAX_IMAGE_BYTES + 1];

        mockMvc.perform(multipart("/api/v1/products/search-by-image")
                        .file(image("large.jpg", "image/jpeg", content)))
                .andExpect(status().isUnprocessableEntity());

        verify(visualSearchClient, never()).search(any(), anyInt());
    }

    @Test
    void rateLimitAndUnavailableStatusesPassThroughPublicEndpoint() throws Exception {
        MockMultipartFile image = image("query.webp", "image/webp", new byte[]{1});
        when(visualSearchClient.search(any(), eq(VisualSearchService.CANDIDATE_LIMIT)))
                .thenThrow(new BusinessRuleException(HttpStatus.TOO_MANY_REQUESTS, "limited"));
        mockMvc.perform(multipart("/api/v1/products/search-by-image").file(image))
                .andExpect(status().isTooManyRequests());

        when(visualSearchClient.search(any(), eq(VisualSearchService.CANDIDATE_LIMIT)))
                .thenThrow(new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable"));
        mockMvc.perform(multipart("/api/v1/products/search-by-image").file(image))
                .andExpect(status().isServiceUnavailable());
    }

    private static MockMultipartFile image(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("image", filename, contentType, content);
    }
}
