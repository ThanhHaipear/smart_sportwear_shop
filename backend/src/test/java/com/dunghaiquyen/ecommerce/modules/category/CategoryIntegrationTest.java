package com.dunghaiquyen.ecommerce.modules.category;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class CategoryIntegrationTest extends AbstractIntegrationTest {

    @Test
    void create_asAdmin_succeeds() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cat-admin"));
        String slug = "cat-" + java.util.UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Shirts\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value(slug))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"slug\":\"x-cat\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_duplicateSlug_returns409() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cat-dup-admin"));
        String slug = "cat-dup-" + java.util.UUID.randomUUID();
        String body = "{\"name\":\"Shirts\",\"slug\":\"" + slug + "\"}";

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Slug already exists: " + slug));
    }

    @Test
    void create_invalidSlug_returns422() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cat-invalid-admin"));

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"slug\":\"Not A Slug!\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("slug"));
    }

    @Test
    void update_blankName_returns422_butNullNameLeavesItUnchanged() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cat-blank-admin"));
        String slug = "cat-blank-" + java.util.UUID.randomUUID();
        var created = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Shirts\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/admin/categories/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        // null/omitted name must still pass (PATCH semantics: leave unchanged).
        mockMvc.perform(patch("/api/v1/admin/categories/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Shirts"))
                .andExpect(jsonPath("$.data.description").value("Updated"));
    }

    @Test
    void publicList_onlyReturnsActiveCategories() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cat-list-admin"));
        String activeSlug = "cat-active-" + java.util.UUID.randomUUID();
        String inactiveSlug = "cat-inactive-" + java.util.UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Active Cat\",\"slug\":\"" + activeSlug + "\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Inactive Cat\",\"slug\":\"" + inactiveSlug + "\",\"status\":\"INACTIVE\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.slug=='" + activeSlug + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.slug=='" + inactiveSlug + "')]").doesNotExist());
    }

    @Test
    void publicDetail_bySlug_returnsActiveCategory() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cat-detail-admin"));
        String slug = "cat-detail-" + java.util.UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Detail Cat\",\"slug\":\"" + slug + "\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/categories/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value(slug))
                .andExpect(jsonPath("$.data.name").value("Detail Cat"));
    }

    @Test
    void publicDetail_inactiveCategory_returns404() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cat-detail-inactive-admin"));
        String slug = "cat-detail-inactive-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hidden Cat\",\"slug\":\"" + slug + "\",\"status\":\"INACTIVE\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/categories/" + slug))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicTree_returnsRootWithItsLeafChildren() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cat-tree-admin"));
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);

        var rootResult = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Running\",\"slug\":\"running-%s\","
                                        + "\"nodeType\":\"GROUP\",\"sortOrder\":10}")
                                .formatted(suffix)))
                .andExpect(status().isCreated())
                .andReturn();
        String rootId = json(rootResult.getResponse().getContentAsString()).at("/data/id").asText();
        String childSlug = "running-shirts-" + suffix;

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Running shirts\",\"slug\":\"%s\","
                                        + "\"nodeType\":\"LEAF\",\"parentId\":\"%s\",\"sortOrder\":1}")
                                .formatted(childSlug, rootId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/categories/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + rootId + "')].children[?(@.slug=='"
                                + childSlug + "')]")
                        .exists());
    }

    @Test
    void create_thirdLevel_returns409() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cat-depth-admin"));
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);

        var rootResult = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Root\",\"slug\":\"root-%s\",\"nodeType\":\"GROUP\"}")
                                .formatted(suffix)))
                .andExpect(status().isCreated())
                .andReturn();
        String rootId = json(rootResult.getResponse().getContentAsString()).at("/data/id").asText();

        var childResult = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Child\",\"slug\":\"child-%s\","
                                        + "\"parentId\":\"%s\"}")
                                .formatted(suffix, rootId)))
                .andExpect(status().isCreated())
                .andReturn();
        String childId = json(childResult.getResponse().getContentAsString()).at("/data/id").asText();

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Grandchild\",\"slug\":\"grandchild-%s\","
                                        + "\"parentId\":\"%s\"}")
                                .formatted(suffix, childId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Parent category must be GROUP."));
    }
}
