package com.nodemetry.backend.mqtt;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.stereotype.Component;

@Component
public class MqttProperties {

    private final Dotenv dotenv = Dotenv.configure()
            .directory(".")
            .ignoreIfMissing()
            .load();

    public String getHost() {
        return get("MQTT_HOST");
    }

    public int getPort() {
        return Integer.parseInt(getOrDefault("MQTT_PORT", "8883"));
    }

    public String getUsername() {
        return get("MQTT_USERNAME");
    }

    public String getPassword() {
        return get("MQTT_PASSWORD");
    }

    public String getClientId() {
        return getOrDefault("MQTT_CLIENT_ID", "nodemetry-backend-local");
    }

    public String getTelemetryTopic() {
        return "nodemetry/+/telemetry";
    }

    public String getStatusTopic() {
        return "nodemetry/+/status";
    }

    public String getBrokerUri() {
        return "ssl://" + getHost() + ":" + getPort();
    }

    private String get(String key) {
        String value = dotenv.get(key);

        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }

        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }

        return value;
    }

    private String getOrDefault(String key, String defaultValue) {
        String value = dotenv.get(key);

        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }

        return value == null || value.isBlank() ? defaultValue : value;
    }
}