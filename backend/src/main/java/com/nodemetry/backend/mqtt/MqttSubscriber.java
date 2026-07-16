package com.nodemetry.backend.mqtt;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true")
public class MqttSubscriber {

    private static final int LOG_VALUE_LIMIT = 160;

    private final MqttProperties properties;
    private final MqttMessageHandler messageHandler;
    private MqttClient client;

    public MqttSubscriber(MqttProperties properties, MqttMessageHandler messageHandler) {
        this.properties = properties;
        this.messageHandler = messageHandler;
    }

    @PostConstruct
    public void connectAndSubscribe() {
        try {
            client = new MqttClient(properties.getBrokerUri(), properties.getClientId());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(properties.getUsername());
            options.setPassword(properties.getPassword().toCharArray());
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);

            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectionLost(Throwable cause) {
                    System.err.println("MQTT connection lost: " + cause.getMessage());
                }

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    // Paho's automatic reconnect restores the TCP connection but does not
                    // resubscribe on its own — without this, a reconnect leaves the client
                    // connected yet silently deaf to every topic.
                    if (reconnect) {
                        try {
                            client.subscribe(properties.getTelemetryTopic(), 1);
                            client.subscribe(properties.getStatusTopic(), 1);
                            System.out.println("Resubscribed after MQTT reconnect: " + serverURI);
                        } catch (MqttException e) {
                            System.err.println("Failed to resubscribe after reconnect: " + e.getMessage());
                        }
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());

                    if (topic.endsWith("/telemetry")) {
                        messageHandler.handleTelemetry(topic, payload, message.isRetained());
                    } else if (topic.endsWith("/status")) {
                        messageHandler.handleStatus(topic, payload, message.isRetained());
                    } else {
                        System.out.println("Unknown topic: " + safeLogValue(topic));
                        System.out.println("Payload chars: " + payload.length());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // Not used by subscriber
                }
            });

            client.connect(options);

            client.subscribe(properties.getTelemetryTopic(), 1);
            client.subscribe(properties.getStatusTopic(), 1);

            System.out.println("Connected to MQTT broker: " + properties.getBrokerUri());
            System.out.println("Subscribed to: " + properties.getTelemetryTopic());
            System.out.println("Subscribed to: " + properties.getStatusTopic());

        } catch (MqttException e) {
            throw new RuntimeException("Failed to connect to MQTT broker", e);
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
                client.close();
                System.out.println("Disconnected from MQTT broker");
            }
        } catch (MqttException e) {
            System.err.println("Failed to disconnect MQTT client: " + e.getMessage());
        }
    }

    private String safeLogValue(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", "?");
        return sanitized.length() <= LOG_VALUE_LIMIT
                ? sanitized
                : sanitized.substring(0, LOG_VALUE_LIMIT) + "...";
    }
}
