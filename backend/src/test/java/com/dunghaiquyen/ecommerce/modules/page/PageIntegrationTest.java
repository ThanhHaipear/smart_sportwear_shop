package com.dunghaiquyen.ecommerce.modules.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@Tag("phase0-excluded")
@Tag("dormant")
class PageIntegrationTest extends AbstractIntegrationTest {

    private String createPage(String adminToken, String status) throws Exception {
        String slug = "page-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/pages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"title\":\"About Us\",\"slug\":\"%s\",\"contentHtml\":\"<p>Hello</p>\",\"status\":\"%s\"}")
                                .formatted(slug, status)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String slugOf(String adminToken, String id) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/pages/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/slug").asText();
    }

    // ===== admin CRUD =====

    @Test
    void admin_createAndPublishPage_setsPublishedAtOnce() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("page-admin"));
        String id = createPage(adminToken, "DRAFT");

        mockMvc.perform(patch("/api/v1/admin/pages/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());
        // Re-fetch from DB (not the save()-result response) so both sides of the
        // comparison below go through the same timestamptz microsecond-precision
        // round-trip - comparing a fresh in-memory Instant against a re-fetched one
        // can otherwise differ in sub-microsecond digits despite being the same instant.
        MvcResult firstFetch = mockMvc.perform(get("/api/v1/admin/pages/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String publishedAt = json(firstFetch.getResponse().getContentAsString()).at("/data/publishedAt").asText();
        assertThat(publishedAt).isNotBlank();

        // Re-saving while already PUBLISHED must not change publishedAt.
        MvcResult second = mockMvc.perform(patch("/api/v1/admin/pages/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"updated summary\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(second.getResponse().getContentAsString()).at("/data/publishedAt").asText())
                .isEqualTo(publishedAt);
    }

    @Test
    void admin_duplicateSlug_returns409() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("page-dup-admin"));
        String slug = "page-dup-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/pages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"title\":\"A\",\"slug\":\"%s\",\"contentHtml\":\"<p>x</p>\"}").formatted(slug)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/v1/admin/pages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"title\":\"B\",\"slug\":\"%s\",\"contentHtml\":\"<p>y</p>\"}").formatted(slug)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void admin_nonAdmin_returns403() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("page-notadmin"));
        mockMvc.perform(get("/api/v1/admin/pages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ===== public by slug =====

    @Test
    void public_publishedPage_isVisibleBySlug_noAuthRequired() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("page-public-admin"));
        String id = createPage(adminToken, "PUBLISHED");
        String slug = slugOf(adminToken, id);

        mockMvc.perform(get("/api/v1/pages/" + slug))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.title").value("About Us"));
    }

    @Test
    void public_draftPage_returns404() throws Exception {
        String adminToken = registerAdminAndGetAccessToken(uniqueEmail("page-draft-admin"));
        String id = createPage(adminToken, "DRAFT");
        String slug = slugOf(adminToken, id);

        mockMvc.perform(get("/api/v1/pages/" + slug))
                .andExpect(status().isNotFound());
    }

    @Test
    void public_unknownSlug_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/pages/does-not-exist-" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
