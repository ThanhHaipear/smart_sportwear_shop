package com.dunghaiquyen.ecommerce.visualsearch.outbox;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class IntegrationEventsEndpointIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/actuator/integrationevents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerRequestIsForbidden() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("event-ops-customer"));

        mockMvc.perform(get("/actuator/integrationevents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadOutboxSnapshot() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("event-ops-admin"));

        mockMvc.perform(get("/actuator/integrationevents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").isNumber())
                .andExpect(jsonPath("$.publishing").isNumber())
                .andExpect(jsonPath("$.retryable_failed").isNumber())
                .andExpect(jsonPath("$.exhausted").isNumber())
                .andExpect(jsonPath("$.published").isNumber());
    }
}
