package com.nodemetry.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mqtt.enabled=false",
        "app.http-api.write-token=test-token"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiAllowsGetRequestsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/runs"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void hiddenWriteEndpointsStayOutOfOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/runs'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runs'].post").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/runs/{runId}/end']").doesNotExist());

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void apiAllowsWritesWithToken() throws Exception {
        mockMvc.perform(post("/api/v1/runs")
                        .header("X-API-Key", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "runId": "run-001",
                                  "label": "CLI run",
                                  "qos": 1,
                                  "nodeCount": 1,
                                  "intervalSec": 1,
                                  "duplicateRate": 0
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void apiRejectsMissingOrInvalidWriteToken() throws Exception {
        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/runs")
                        .header("X-API-Key", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
