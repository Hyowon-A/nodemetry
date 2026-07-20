#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_SHT31.h>
#include <BH1750.h>
#include <time.h>
#include <math.h>

// ============================================================
// I2C and sensors
// ============================================================

constexpr int SDA_PIN = 21;
constexpr int SCL_PIN = 22;

Adafruit_SHT31 sht3x;
BH1750 lightMeter;

bool sht3xReady = false;
bool bh1750Ready = false;

// ============================================================
// Week 3 filtering
// ============================================================

constexpr int FILTER_WINDOW = 5;

constexpr float TEMPERATURE_OFFSET = 0.0f;
constexpr float HUMIDITY_OFFSET = 0.0f;

constexpr float TEMPERATURE_OUTLIER_THRESHOLD = 3.0f;
constexpr float HUMIDITY_OUTLIER_THRESHOLD = 15.0f;

struct MovingAverageFilter {
  float values[FILTER_WINDOW] = {};
  int index = 0;
  int count = 0;
  float sum = 0.0f;

  void add(float value) {
    if (count < FILTER_WINDOW) {
      values[index] = value;
      sum += value;
      count++;
    } else {
      sum -= values[index];
      values[index] = value;
      sum += value;
    }

    index = (index + 1) % FILTER_WINDOW;
  }

  float average() const {
    if (count == 0) {
      return NAN;
    }

    return sum / count;
  }
};

MovingAverageFilter temperatureFilter;
MovingAverageFilter humidityFilter;

// ============================================================
// Wi-Fi
// ============================================================

const char* WIFI_SSID = "VM0900341";
const char* WIFI_PASSWORD = "qn3vgKzqwpkx";

// ============================================================
// MQTT
// ============================================================

const char* MQTT_HOST =
    "9741496ab663426dba9cea75f1df7986.s1.eu.hivemq.cloud";

constexpr int MQTT_PORT = 8883;

const char* MQTT_USERNAME = "nodemetryAdmin";
const char* MQTT_PASSWORD = "Admin2026";

// ============================================================
// Node configuration
// ============================================================

// This fixes the:
// 'nodeId' was not declared in this scope
// compilation error.
String nodeId = "node-001";

const char* MQTT_TELEMETRY_TOPIC =
    "nodemetry/node-001/telemetry";

const char* MQTT_STATUS_TOPIC =
    "nodemetry/node-001/status";

const char* ONLINE_STATUS =
    "{\"status\":\"online\"}";

const char* OFFLINE_STATUS =
    "{\"status\":\"offline\"}";

String runId;

unsigned long sequenceNumber = 0;
unsigned long lastPublishTime = 0;

constexpr unsigned long PUBLISH_INTERVAL_MS = 10000;

// ============================================================
// MQTT client
// ============================================================

WiFiClientSecure espClient;
PubSubClient client(espClient);

// ============================================================
// Wi-Fi connection
// ============================================================

void connectToWiFi() {
  Serial.print("Connecting to WiFi");

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.println("WiFi connected");

  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());

  Serial.print("RSSI: ");
  Serial.print(WiFi.RSSI());
  Serial.println(" dBm");
}

// ============================================================
// Initialise sensors
// ============================================================

void initialiseSensors() {
  Serial.println("Starting sensors...");

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(100000);

  sht3xReady = sht3x.begin(0x44);

  if (sht3xReady) {
    Serial.println("SHT3X detected");
  } else {
    Serial.println("ERROR: SHT3X not detected");
  }

  bh1750Ready =
      lightMeter.begin(BH1750::CONTINUOUS_HIGH_RES_MODE);

  if (bh1750Ready) {
    Serial.println("BH1750 detected");
  } else {
    Serial.println("ERROR: BH1750 not detected");
  }
}

// ============================================================
// Create run ID using UTC time
// ============================================================

String createRunId() {
  configTime(
      0,
      0,
      "pool.ntp.org",
      "time.google.com"
  );

  struct tm timeInfo;

  while (!getLocalTime(&timeInfo)) {
    Serial.println("Waiting for NTP time...");
    delay(1000);
  }

  char buffer[32];

  strftime(
      buffer,
      sizeof(buffer),
      "%Y%m%dT%H%M%SZ",
      &timeInfo
  );

  return String(buffer);
}

// ============================================================
// Create unique message ID
// ============================================================

String createMessageId() {
  sequenceNumber++;

  char sequenceBuffer[16];

  snprintf(
      sequenceBuffer,
      sizeof(sequenceBuffer),
      "%06lu",
      sequenceNumber
  );

  return nodeId + "-" + runId + "-" +
         String(sequenceBuffer);
}

// ============================================================
// MQTT connection with Last Will and Testament
// ============================================================

void connectToMQTT() {
  while (!client.connected()) {
    Serial.print("Connecting to MQTT... ");

    String clientId = "esp32-" + nodeId + "-";
    clientId += String(random(0xffff), HEX);

    /*
      Last Will configuration:

      If the ESP32 unexpectedly loses power, Wi-Fi or crashes,
      HiveMQ publishes OFFLINE_STATUS automatically to:

      nodemetry/node-001/status
    */

    bool connected = client.connect(
        clientId.c_str(),
        MQTT_USERNAME,
        MQTT_PASSWORD,
        MQTT_STATUS_TOPIC,
        1,
        true,
        OFFLINE_STATUS
    );

    if (connected) {
      Serial.println("connected");

      /*
        Publish online immediately after connecting.

        Retain is true, so MQTT Explorer always shows
        the latest node status.
      */

      bool statusPublished = client.publish(
          MQTT_STATUS_TOPIC,
          ONLINE_STATUS,
          true
      );

      if (statusPublished) {
        Serial.println("Online status published");
      } else {
        Serial.println("ERROR: Online status publish failed");
      }

    } else {
      Serial.print("failed, MQTT state=");
      Serial.print(client.state());
      Serial.println(" - retrying in 5 seconds");

      delay(5000);
    }
  }
}

// ============================================================
// Outlier detection
// ============================================================

bool isOutlier(
    float newValue,
    const MovingAverageFilter& filter,
    float threshold
) {
  if (filter.count == 0) {
    return false;
  }

  float difference =
      fabsf(newValue - filter.average());

  return difference > threshold;
}

// ============================================================
// Read sensors and publish telemetry
// ============================================================

void publishTelemetry() {
  if (!sht3xReady || !bh1750Ready) {
    Serial.println(
        "Publish cancelled: sensors unavailable"
    );
    return;
  }

  // Raw sensor readings
  float rawTemperature =
      sht3x.readTemperature();

  float rawHumidity =
      sht3x.readHumidity();

  float lightLux =
      lightMeter.readLightLevel();

  if (isnan(rawTemperature) ||
      isnan(rawHumidity)) {
    Serial.println("ERROR: SHT3X reading failed");
    return;
  }

  if (lightLux < 0) {
    Serial.println("ERROR: BH1750 reading failed");
    return;
  }

  // Calibration offsets currently remain zero
  float calibratedTemperature =
      rawTemperature + TEMPERATURE_OFFSET;

  float calibratedHumidity =
      rawHumidity + HUMIDITY_OFFSET;

  // Check for outliers
  bool temperatureOutlier = isOutlier(
      calibratedTemperature,
      temperatureFilter,
      TEMPERATURE_OUTLIER_THRESHOLD
  );

  bool humidityOutlier = isOutlier(
      calibratedHumidity,
      humidityFilter,
      HUMIDITY_OUTLIER_THRESHOLD
  );

  // Only accepted readings enter the moving average
  if (!temperatureOutlier) {
    temperatureFilter.add(calibratedTemperature);
  } else {
    Serial.print("Temperature outlier rejected: ");
    Serial.print(rawTemperature, 2);
    Serial.println(" C");
  }

  if (!humidityOutlier) {
    humidityFilter.add(calibratedHumidity);
  } else {
    Serial.print("Humidity outlier rejected: ");
    Serial.print(rawHumidity, 2);
    Serial.println(" %RH");
  }

  float filteredTemperature =
      temperatureFilter.average();

  float filteredHumidity =
      humidityFilter.average();

  String messageId = createMessageId();

  // ==========================================================
  // Create JSON payload
  // ==========================================================

  String payload = "{";

  payload += "\"messageId\":\"";
  payload += messageId;
  payload += "\",";

  payload += "\"nodeId\":\"";
  payload += nodeId;
  payload += "\",";

  payload += "\"runId\":\"";
  payload += runId;
  payload += "\",";

  // Dashboard compatibility fields
  payload += "\"temperature\":";
  payload += String(filteredTemperature, 2);
  payload += ",";

  payload += "\"humidity\":";
  payload += String(filteredHumidity, 2);
  payload += ",";

  // Raw and filtered Week 3 values
  payload += "\"temperatureRaw\":";
  payload += String(rawTemperature, 2);
  payload += ",";

  payload += "\"temperatureFiltered\":";
  payload += String(filteredTemperature, 2);
  payload += ",";

  payload += "\"humidityRaw\":";
  payload += String(rawHumidity, 2);
  payload += ",";

  payload += "\"humidityFiltered\":";
  payload += String(filteredHumidity, 2);
  payload += ",";

  payload += "\"battery\":null,";

  payload += "\"light\":";
  payload += String(lightLux, 2);
  payload += ",";

  payload += "\"rssi\":";
  payload += String(WiFi.RSSI());
  payload += ",";

  payload += "\"firmwareVersion\":\"firmware-4.0.0\"";

  payload += "}";

  // ==========================================================
  // Serial output
  // ==========================================================

  Serial.println();

  Serial.print("Temperature raw: ");
  Serial.print(rawTemperature, 2);
  Serial.print(" C | filtered: ");
  Serial.print(filteredTemperature, 2);
  Serial.println(" C");

  Serial.print("Humidity raw: ");
  Serial.print(rawHumidity, 2);
  Serial.print(" %RH | filtered: ");
  Serial.print(filteredHumidity, 2);
  Serial.println(" %RH");

  Serial.println("Publishing telemetry:");
  Serial.println(payload);

  // ==========================================================
  // MQTT publish
  // ==========================================================

  bool success = client.publish(
      MQTT_TELEMETRY_TOPIC,
      payload.c_str()
  );

  if (success) {
    Serial.println("Publish successful");
  } else {
    Serial.println("Publish failed");
  }
}

// ============================================================
// Setup
// ============================================================

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println();
  Serial.println("Starting Nodemetry ESP32 node");

  initialiseSensors();

  connectToWiFi();

  runId = createRunId();

  Serial.print("Run ID: ");
  Serial.println(runId);

  // Development-only TLS configuration
  espClient.setInsecure();

  client.setServer(
      MQTT_HOST,
      MQTT_PORT
  );

  // Required for the larger raw/filtered JSON payload
  client.setBufferSize(512);

  connectToMQTT();

  Serial.println();
  Serial.println("System ready");
}

// ============================================================
// Main loop
// ============================================================

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("WiFi disconnected");

    connectToWiFi();
  }

  if (!client.connected()) {
    connectToMQTT();
  }

  client.loop();

  unsigned long currentTime = millis();

  if (
      currentTime - lastPublishTime >=
      PUBLISH_INTERVAL_MS
  ) {
    lastPublishTime = currentTime;

    publishTelemetry();
  }
}