package com.nodemetry.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nodemetryOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nodemetry API")
                        .version("1.0.0")
                        .description("REST API for physical sensor monitoring, telemetry history, node runs, and virtual load-test results.")
                        .contact(new Contact().name("Hyowon Ahn")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local development"),
                        new Server()
                                .url("https://TODO_DEPLOYED_BACKEND_URL")
                                .description("Deployed backend")
                ));
    }
}
