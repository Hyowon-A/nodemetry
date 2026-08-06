package com.nodemetry.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.frontend")
public record FrontendProperties(List<String> allowedOrigins) {

    public FrontendProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }

    public List<String> getAllowedOrigins() {
        if (allowedOrigins.isEmpty()) {
            throw new IllegalStateException("app.frontend.allowed-origins must include at least one origin");
        }
        return allowedOrigins;
    }
}
