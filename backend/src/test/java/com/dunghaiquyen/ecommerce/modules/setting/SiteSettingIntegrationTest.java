package com.dunghaiquyen.ecommerce.modules.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class SiteSettingIntegrationTest extends AbstractIntegrationTest {

    // ===== upsert: create then update by the same key =====

    @Test
    void admin_upsertCreatesThenUpdatesSameKey() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("setting-admin"));
        String key = "site.contact.email." + UUID.randomUUID();

        MvcResult created = mockMvc.perform(put("/api/v1/admin/settings/" + key)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingValue\":\"support@example.com\",\"valueType\":\"STRING\",\"isPublic\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(created.getResponse().getContentAsString());
        assertThat(body.at("/data/settingValue").asText()).isEqualTo("support@example.com");
        String id = body.at("/data/id").asText();

        MvcResult updated = mockMvc.perform(put("/api/v1/admin/settings/" + key)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingValue\":\"help@example.com\",\"valueType\":\"STRING\",\"isPublic\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode updatedBody = json(updated.getResponse().getContentAsString());
        assertThat(updatedBody.at("/data/settingValue").asText()).isEqualTo("help@example.com");
        // Same row updated, not a second one created.
        assertThat(updatedBody.at("/data/id").asText()).isEqualTo(id);
    }

    // ===== shape validation =====

    @Test
    void admin_numberType_invalidValue_returns422() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("setting-number-admin"));
        String key = "site.shipping.flatFee." + UUID.randomUUID();
        mockMvc.perform(put("/api/v1/admin/settings/" + key)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingValue\":\"not-a-number\",\"valueType\":\"NUMBER\",\"isPublic\":false}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void admin_booleanType_invalidValue_returns422() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("setting-bool-admin"));
        String key = "site.feature.flag." + UUID.randomUUID();
        mockMvc.perform(put("/api/v1/admin/settings/" + key)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingValue\":\"yes\",\"valueType\":\"BOOLEAN\",\"isPublic\":false}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void admin_jsonType_invalidValue_returns422() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("setting-json-admin"));
        String key = "site.social.links." + UUID.randomUUID();
        mockMvc.perform(put("/api/v1/admin/settings/" + key)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingValue\":\"{not valid json\",\"valueType\":\"JSON\",\"isPublic\":false}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void admin_jsonType_validValue_succeeds() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("setting-json-valid-admin"));
        String key = "site.social.links." + UUID.randomUUID();
        mockMvc.perform(put("/api/v1/admin/settings/" + key)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingValue\":\"{\\\"facebook\\\":\\\"https://fb.com/x\\\"}\",\"valueType\":\"JSON\",\"isPublic\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    void admin_nonAdmin_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("setting-notadmin"));
        mockMvc.perform(get("/api/v1/admin/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ===== public subset =====

    @Test
    void public_onlyReturnsPublicSettings_noAuthRequired() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("setting-public-admin"));
        String publicKey = "site.contact.hotline." + UUID.randomUUID();
        String privateKey = "site.internal.api-secret." + UUID.randomUUID();

        mockMvc.perform(put("/api/v1/admin/settings/" + publicKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingValue\":\"0900000000\",\"valueType\":\"STRING\",\"isPublic\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/admin/settings/" + privateKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingValue\":\"super-secret\",\"valueType\":\"STRING\",\"isPublic\":false}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/v1/settings/public"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");

        boolean foundPublic = false;
        for (JsonNode item : data) {
            assertThat(item.at("/settingKey").asText()).isNotEqualTo(privateKey);
            if (item.at("/settingKey").asText().equals(publicKey)) {
                foundPublic = true;
                assertThat(item.at("/settingValue").asText()).isEqualTo("0900000000");
            }
        }
        assertThat(foundPublic).isTrue();
    }
}
