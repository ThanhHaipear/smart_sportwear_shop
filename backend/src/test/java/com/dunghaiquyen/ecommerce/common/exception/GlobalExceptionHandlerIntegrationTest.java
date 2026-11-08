package com.dunghaiquyen.ecommerce.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
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
    }
}
