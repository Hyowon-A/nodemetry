package com.nodemetry.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class FrontendProperties {

    private final List<String> allowedOrigins;

    public FrontendProperties(@Value("${app.frontend.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();

        if (this.allowedOrigins.isEmpty()) {
            throw new IllegalStateException("app.frontend.allowed-origins must include at least one origin");
        }
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }
}
