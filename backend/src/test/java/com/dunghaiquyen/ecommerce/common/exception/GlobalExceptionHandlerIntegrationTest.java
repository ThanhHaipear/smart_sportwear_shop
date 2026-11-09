package com.dunghaiquyen.ecommerce.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import com.dunghaiquyen.ecommerce.common.observability.CorrelationIdFilter;
import org.springframework.test.web.servlet.MvcResult;

class GlobalExceptionHandlerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void missingApiResourceReturns404Envelope() throws Exception {
        TokenPair user = registerUser(uniqueEmail("missing-resource"));

        MvcResult result = mockMvc.perform(get("/api/v1/does-not-exist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andReturn();

        var body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/success").asBoolean()).isFalse();
        assertThat(body.at("/message").asText()).isEqualTo("Resource not found");
        String traceId = result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(traceId).isNotBlank();
        assertThat(body.at("/traceId").asText()).isEqualTo(traceId);
    }

    @Test
    void suppliedCorrelationIdFlowsThroughHeaderAndErrorEnvelope() throws Exception {
        String supplied = "phase1-contract-test";

        MvcResult result = mockMvc.perform(get("/api/v1/does-not-exist")
                        .header(CorrelationIdFilter.HEADER_NAME, supplied))
                .andExpect(status().isUnauthorized())
                .andReturn();

        var body = json(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(supplied);
        assertThat(body.at("/traceId").asText()).isEqualTo(supplied);
    }
}
