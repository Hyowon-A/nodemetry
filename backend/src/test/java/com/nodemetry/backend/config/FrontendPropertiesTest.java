package com.nodemetry.backend.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrontendPropertiesTest {

    @Test
    void parsesMultipleAllowedOrigins() {
        FrontendProperties properties = new FrontendProperties(
                "http://localhost:5173,http://127.0.0.1:5173"
        );

        assertThat(properties.getAllowedOrigins())
                .containsExactly("http://localhost:5173", "http://127.0.0.1:5173");
    }

    @Test
    void rejectsBlankAllowedOrigins() {
        assertThatThrownBy(() -> new FrontendProperties(" , "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("app.frontend.allowed-origins must include at least one origin");
    }
}
