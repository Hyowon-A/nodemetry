package com.nodemetry.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final FrontendProperties frontendProperties;
    private final HttpApiProperties httpApiProperties;

    public SecurityConfig(FrontendProperties frontendProperties, HttpApiProperties httpApiProperties) {
        this.frontendProperties = frontendProperties;
        this.httpApiProperties = httpApiProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs", "/v3/api-docs/**").permitAll();
                    if (httpApiProperties.isReadOnly()) {
                        auth.requestMatchers(HttpMethod.GET, "/api/**").permitAll();
                        auth.requestMatchers(HttpMethod.HEAD, "/api/**").permitAll();
                        auth.requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll();
                        auth.requestMatchers("/api/**").denyAll();
                    } else {
                        auth.requestMatchers("/api/**").permitAll();
                    }
                    auth.anyRequest().permitAll();
                })
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(frontendProperties.getAllowedOrigins());
        config.setAllowedMethods(httpApiProperties.isReadOnly()
                ? List.of("GET", "HEAD", "OPTIONS")
                : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        return source;
    }
}
