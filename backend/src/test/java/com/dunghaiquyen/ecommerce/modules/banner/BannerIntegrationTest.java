package com.dunghaiquyen.ecommerce.modules.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@Tag("phase0-excluded")
@Tag("dormant")
class BannerIntegrationTest extends AbstractIntegrationTest {

    private String createBanner(String adminToken, String status) throws Exception {
        String code = "ban-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/banners")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Banner\",\"code\":\"%s\",\"placement\":\"HOME_HERO\",\"status\":\"%s\"}")
                                .formatted(code, status)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    // ===== admin CRUD =====

    @Test
    void admin_createUpdateBanner_succeeds() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("banner-admin"));
        String id = createBanner(adminToken, "DRAFT");

        MvcResult update = mockMvc.perform(patch("/api/v1/admin/banners/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(update.getResponse().getContentAsString()).at("/data/status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void admin_duplicateCode_returns409() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("banner-dup-admin"));
        String code = "ban-dup-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/banners")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Banner\",\"code\":\"%s\",\"placement\":\"HOME_HERO\"}").formatted(code)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/v1/admin/banners")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Banner2\",\"code\":\"%s\",\"placement\":\"HOME_HERO\"}").formatted(code)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void admin_nonAdmin_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("banner-notadmin"));
        mockMvc.perform(get("/api/v1/admin/banners")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ===== banner items =====

    @Test
    void admin_addUpdateDeleteItem_succeeds() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("banner-item-admin"));
        String bannerId = createBanner(adminToken, "ACTIVE");

        MvcResult addResult = mockMvc.perform(post("/api/v1/admin/banners/" + bannerId + "/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Slide 1\",\"imageUrl\":\"https://example.com/a.jpg\",\"sortOrder\":1}"))
                .andExpect(status().isCreated())
                .andReturn();
        String itemId = json(addResult.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult updateResult = mockMvc.perform(patch("/api/v1/admin/banners/items/" + itemId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(updateResult.getResponse().getContentAsString()).at("/data/isActive").asBoolean()).isFalse();

        mockMvc.perform(delete("/api/v1/admin/banners/items/" + itemId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        MvcResult detail = mockMvc.perform(get("/api/v1/admin/banners/" + bannerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(detail.getResponse().getContentAsString()).at("/data/items")).isEmpty();
    }

    // ===== public active listing =====

    @Test
    void public_activeListing_onlyReturnsActiveWithinWindow_andOnlyActiveItems() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("banner-public-admin"));
        String activeBannerId = createBanner(adminToken, "ACTIVE");
        String draftBannerId = createBanner(adminToken, "DRAFT");

        mockMvc.perform(post("/api/v1/admin/banners/" + activeBannerId + "/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"https://example.com/active.jpg\",\"sortOrder\":1,\"isActive\":true}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/banners/" + activeBannerId + "/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"https://example.com/inactive.jpg\",\"sortOrder\":2,\"isActive\":false}"))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/v1/banners/active").param("placement", "HOME_HERO"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");

        boolean foundActive = false;
        boolean foundDraft = false;
        for (JsonNode item : data) {
            if (item.at("/id").asText().equals(activeBannerId)) {
                foundActive = true;
                assertThat(item.at("/items")).hasSize(1);
                assertThat(item.at("/items/0/imageUrl").asText()).isEqualTo("https://example.com/active.jpg");
            }
            if (item.at("/id").asText().equals(draftBannerId)) {
                foundDraft = true;
            }
        }
        assertThat(foundActive).isTrue();
        assertThat(foundDraft).as("DRAFT banners must not be public").isFalse();
    }

    @Test
    void public_activeListing_excludesExpiredTimeWindow() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("banner-expired-admin"));
        String code = "ban-expired-" + UUID.randomUUID();
        Instant past = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant pastEnd = Instant.now().minus(1, ChronoUnit.DAYS);
        MvcResult created = mockMvc.perform(post("/api/v1/admin/banners")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Expired\",\"code\":\"%s\",\"placement\":\"HOME_HERO\",\"status\":\"ACTIVE\","
                                        + "\"startsAt\":\"%s\",\"endsAt\":\"%s\"}")
                                .formatted(code, past, pastEnd)))
                .andExpect(status().isCreated())
                .andReturn();
        String expiredId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult result = mockMvc.perform(get("/api/v1/banners/active").param("placement", "HOME_HERO"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");
        for (JsonNode item : data) {
            assertThat(item.at("/id").asText()).isNotEqualTo(expiredId);
        }
    }

    @Test
    void public_activeListing_noAuthRequired() throws Exception {
        mockMvc.perform(get("/api/v1/banners/active").param("placement", "HOME_HERO"))
                .andExpect(status().isOk());
    }
}
