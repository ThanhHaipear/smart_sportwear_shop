package com.dunghaiquyen.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.dunghaiquyen.identity.security.JwtService;
import com.dunghaiquyen.identity.domain.IdentityModels.Role;

@SpringBootTest
@AutoConfigureMockMvc
class IdentityApiIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired JwtService jwt;

    @Test void registerLoginRefreshRotationAndReuseDetection() throws Exception {
        String register=mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
          {"fullName":"Phase Seven","email":"phase7@example.com","password":"secret123","phone":"0901234567"}
          """)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.user.passwordHash").doesNotExist()).andReturn().getResponse().getContentAsString();
        JsonNode first=json.readTree(register);String access=first.at("/data/tokens/accessToken").asText(),refresh=first.at("/data/tokens/refreshToken").asText();
        assertThat(access).isNotBlank();
        String rotated=mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("refreshToken",refresh))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(rotated).at("/data/refreshToken").asText()).isNotEqualTo(refresh);
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("refreshToken",refresh))))
                .andExpect(status().isUnauthorized());
        Integer active=jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null",Integer.class);
        assertThat(active).isZero();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"phase7@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value("CUSTOMER"));
    }

    @Test void adminRoleCheckRejectsCustomerAndAllowsAdmin() throws Exception {
        java.util.UUID customer=java.util.UUID.randomUUID(),admin=java.util.UUID.randomUUID();
        jdbc.update("insert into users(id,email,full_name,role_code) values(?,?,?,'CUSTOMER')",customer,"customer-role@example.com","Customer");
        jdbc.update("insert into users(id,email,full_name,role_code) values(?,?,?,'ADMIN')",admin,"admin-role@example.com","Admin");
        String body="{\"role\":\"SALES_STAFF\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/users/"+customer+"/role").header("Authorization","Bearer "+jwt.access(customer,Role.CUSTOMER)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/users/"+customer+"/role").header("Authorization","Bearer "+jwt.access(admin,Role.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("SALES_STAFF"));
    }
}
