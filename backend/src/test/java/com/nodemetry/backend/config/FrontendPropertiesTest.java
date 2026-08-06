package com.nodemetry.backend.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrontendPropertiesTest {

    @Test
    void parsesMultipleAllowedOrigins() {
        FrontendProperties properties = new FrontendProperties(
                List.of("http://localhost:5173", " http://127.0.0.1:5173 ")
        );

        assertThat(properties.getAllowedOrigins())
                .containsExactly("http://localhost:5173", "http://127.0.0.1:5173");
    }

    @Test
    void rejectsBlankAllowedOrigins() {
        FrontendProperties properties = new FrontendProperties(List.of(" ", "\t"));

        assertThatThrownBy(properties::getAllowedOrigins)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("app.frontend.allowed-origins must include at least one origin");
    }
}
