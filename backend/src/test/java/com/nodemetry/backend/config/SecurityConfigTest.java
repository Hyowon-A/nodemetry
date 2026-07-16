package com.nodemetry.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mqtt.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void readOnlyApiAllowsGetRequests() throws Exception {
        mockMvc.perform(get("/api/v1/runs"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void readOnlyApiRejectsUnsafeRunRequests() throws Exception {
        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/runs/run-001/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
