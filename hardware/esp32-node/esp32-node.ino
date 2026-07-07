#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_SHT4x.h>
#include <BH1750.h>
#include <time.h>

// Wi-Fi details
// Better: move these to a secrets/env file before committing
const char* ssid = "WIFI_ADDRESS";
const char* wifi_password = "WIFI_PASSWORD";

// MQTT details
// Better: move these to a secrets/env file before committing
const char* mqtt_server = "MQTT_SERVER";
const int mqtt_port = 8883;
const char* mqtt_username = "MQTT_USERNAME";
const char* mqtt_password = "MQTT_PASSWORD";

// Node details
String nodeId = "node-002";
String runId;
unsigned long sequenceNumber = 0;

const char* firmwareVersion = "firmware-1.0.0";
const char* topic = "nodemetry/node-002/telemetry";

// MQTT client
WiFiClientSecure espClient;
PubSubClient client(espClient);

// Sensors
Adafruit_SHT4x sht4 = Adafruit_SHT4x();
BH1750 lightMeter;

String createRunId() {
  configTime(0, 0, "pool.ntp.org", "time.google.com");

  struct tm timeinfo;

  while (!getLocalTime(&timeinfo)) {
    Serial.println("Waiting for NTP time...");
    delay(1000);
  }

  char buffer[32];
  strftime(buffer, sizeof(buffer), "%Y%m%dT%H%M%SZ", &timeinfo);

  return String(buffer);
}

String createMessageId() {
  sequenceNumber++;

  char seqBuffer[16];
  sprintf(seqBuffer, "%06lu", sequenceNumber);

  return nodeId + "-" + runId + "-" + String(seqBuffer);
}

void connectWiFi() {
  Serial.println("Connecting to Wi-Fi...");
  WiFi.begin(ssid, wifi_password);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.println("Wi-Fi connected!");
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());
  Serial.print("RSSI: ");
  Serial.println(WiFi.RSSI());

  runId = createRunId();

  Serial.print("Run ID: ");
  Serial.println(runId);
}

void connectMQTT() {
  while (!client.connected()) {
    Serial.println("Connecting to MQTT...");

    String clientId = "esp32-node-002-";
    clientId += String(random(0xffff), HEX);

    if (client.connect(clientId.c_str(), mqtt_username, mqtt_password)) {
      Serial.println("MQTT connected!");
    } else {
      Serial.print("MQTT failed, rc=");
      Serial.print(client.state());
      Serial.println(" trying again in 5 seconds");
      delay(5000);
    }
  }
}

void setupSensors() {
  Wire.begin(21, 22); // SDA = GPIO21, SCL = GPIO22
  delay(1000);

  Serial.println("Starting sensors...");

  if (!sht4.begin(&Wire)) {
    Serial.println("Could not find SHT40. Check wiring.");
    while (1) delay(10);
  }

  Serial.println("SHT40 found.");

  sht4.setPrecision(SHT4X_HIGH_PRECISION);
  sht4.setHeater(SHT4X_NO_HEATER);

  if (!lightMeter.begin(BH1750::CONTINUOUS_HIGH_RES_MODE, 0x23, &Wire)) {
    Serial.println("Could not find BH1750. Check wiring.");
    while (1) delay(10);
  }

  Serial.println("BH1750 found.");
  Serial.println("Sensors ready.");
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  setupSensors();
  connectWiFi();

  // Simple TLS mode for testing
  espClient.setInsecure();

  client.setServer(mqtt_server, mqtt_port);
  connectMQTT();
}

void loop() {
  if (!client.connected()) {
    connectMQTT();
  }

  client.loop();

  sensors_event_t humidity, temp;
  sht4.getEvent(&humidity, &temp);

  float temperature = temp.temperature;
  float humidityValue = humidity.relative_humidity;
  float light = lightMeter.readLightLevel();
  float rssi = WiFi.RSSI();

  // Temporary fake battery percentage until you add battery measurement hardware
  float battery = 87.0;

  String messageId = createMessageId();

  String payload = "{";
  payload += "\"messageId\":\"" + messageId + "\",";
  payload += "\"nodeId\":\"" + nodeId + "\",";
  payload += "\"runId\":\"" + runId + "\",";
  payload += "\"temperature\":" + String(temperature, 2) + ",";
  payload += "\"humidity\":" + String(humidityValue, 2) + ",";
  payload += "\"battery\":" + String(battery, 1) + ",";
  payload += "\"light\":" + String(light, 1) + ",";
  payload += "\"rssi\":" + String(rssi, 0) + ",";
  payload += "\"firmwareVersion\":\"" + String(firmwareVersion) + "\"";
  payload += "}";

  Serial.print("Publishing real sensor data: ");
  Serial.println(payload);

  client.publish(topic, payload.c_str());

  delay(10000);
}