package com.nodemetry.backend.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mqtt")
public record MqttProperties(
        String host,
        int port,
        String username,
        String password,
        String clientId,
        String telemetryTopic,
        String statusTopic
) {

    public MqttProperties {
        host = required(host, "mqtt.host");
        username = required(username, "mqtt.username");
        password = required(password, "mqtt.password");
        clientId = defaultIfBlank(clientId, "nodemetry-backend-local");
        telemetryTopic = defaultIfBlank(telemetryTopic, "nodemetry/+/telemetry");
        statusTopic = defaultIfBlank(statusTopic, "nodemetry/+/status");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required property: " + name);
        return value;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
