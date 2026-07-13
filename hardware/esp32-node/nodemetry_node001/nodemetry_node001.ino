#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_SHT31.h>
#include <BH1750.h>
#include <time.h>
#include <math.h>

// ---------- I2C pins ----------
constexpr int SDA_PIN = 21;
constexpr int SCL_PIN = 22;

// ---------- Sensors ----------
Adafruit_SHT31 sht3x = Adafruit_SHT31();
BH1750 lightMeter;

bool sht3xReady = false;
bool bh1750Ready = false;

// ---------- Week 3 filtering ----------
constexpr int FILTER_WINDOW = 5;

// Calibration offsets remain zero for now
constexpr float TEMPERATURE_OFFSET = 0.0f;
constexpr float HUMIDITY_OFFSET = 0.0f;

// Maximum permitted difference from the current moving average
constexpr float TEMPERATURE_OUTLIER_THRESHOLD = 3.0f;
constexpr float HUMIDITY_OUTLIER_THRESHOLD = 15.0f;

// Stores and averages the latest five accepted readings
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
      // Remove the oldest value from the sum
      sum -= values[index];

      // Replace it with the new value
      values[index] = value;
      sum += value;
    }

    // Move to the next position in the circular buffer
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

// ---------- Wi-Fi ----------
const char* WIFI_SSID = "VM0900341";
const char* WIFI_PASSWORD = "qn3vgKzqwpkx";

// ---------- MQTT ----------
const char* MQTT_HOST =
    "9741496ab663426dba9cea75f1df7986.s1.eu.hivemq.cloud";

const int MQTT_PORT = 8883;

const char* MQTT_USERNAME = "nodemetryAdmin";
const char* MQTT_PASSWORD = "Admin0987";

// ---------- Node details ----------
String nodeId = "node-001";
String runId;

unsigned long sequenceNumber = 0;
unsigned long lastPublishTime = 0;

constexpr unsigned long PUBLISH_INTERVAL_MS = 10000;

// Topic must match nodeId
const char* MQTT_TOPIC =
    "nodemetry/node-001/telemetry";

// Secure Wi-Fi client for TLS MQTT
WiFiClientSecure espClient;
PubSubClient client(espClient);

// ---------- Connect to Wi-Fi ----------
void connectToWiFi() {
  Serial.print("Connecting to WiFi");

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.println("WiFi connected!");

  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());

  Serial.print("WiFi RSSI: ");
  Serial.print(WiFi.RSSI());
  Serial.println(" dBm");
}

// ---------- Initialise sensors ----------
void initialiseSensors() {
  Serial.println("Starting sensors...");

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(100000);

  // Most SHT3X boards use address 0x44
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

// ---------- Create runId using UTC time ----------
String createRunId() {
  configTime(
      0,
      0,
      "pool.ntp.org",
      "time.google.com"
  );

  struct tm timeinfo;

  while (!getLocalTime(&timeinfo)) {
    Serial.println("Waiting for NTP time...");
    delay(1000);
  }

  char buffer[32];

  // Example: 20260713T153000Z
  strftime(
      buffer,
      sizeof(buffer),
      "%Y%m%dT%H%M%SZ",
      &timeinfo
  );

  return String(buffer);
}

// ---------- Create unique messageId ----------
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

// ---------- Connect to MQTT ----------
void connectToMQTT() {
  while (!client.connected()) {
    Serial.print("Connecting to MQTT... ");

    String clientId = "esp32-" + nodeId + "-";
    clientId += String(random(0xffff), HEX);

    bool connected = client.connect(
        clientId.c_str(),
        MQTT_USERNAME,
        MQTT_PASSWORD
    );

    if (connected) {
      Serial.println("connected!");
    } else {
      Serial.print("failed, rc=");
      Serial.print(client.state());
      Serial.println(" - retrying in 5 seconds");

      delay(5000);
    }
  }
}

// ---------- Check whether a reading is an outlier ----------
bool isOutlier(
    float newValue,
    const MovingAverageFilter& filter,
    float threshold
) {
  // The first reading cannot be compared with an average
  if (filter.count == 0) {
    return false;
  }

  float difference =
      fabsf(newValue - filter.average());

  return difference > threshold;
}

// ---------- Read and publish telemetry ----------
void publishTelemetry() {
  if (!sht3xReady || !bh1750Ready) {
    Serial.println(
        "Publish cancelled: one or more sensors unavailable"
    );
    return;
  }

  // Read the raw sensor values
  float rawTemperature =
      sht3x.readTemperature();

  float rawHumidity =
      sht3x.readHumidity();

  float lightLux =
      lightMeter.readLightLevel();

  // Validate the SHT3X readings
  if (isnan(rawTemperature) ||
      isnan(rawHumidity)) {
    Serial.println("ERROR: SHT3X reading failed");
    return;
  }

  // BH1750 returns a negative value when reading fails
  if (lightLux < 0) {
    Serial.println("ERROR: BH1750 reading failed");
    return;
  }

  // Apply calibration offsets
  // Both offsets are currently zero
  float calibratedTemperature =
      rawTemperature + TEMPERATURE_OFFSET;

  float calibratedHumidity =
      rawHumidity + HUMIDITY_OFFSET;

  // Check each reading for an outlier
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

  // Only accepted values enter the moving-average filters
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

  // Calculate the filtered values
  float filteredTemperature =
      temperatureFilter.average();

  float filteredHumidity =
      humidityFilter.average();

  String messageId = createMessageId();

  // ---------- Build JSON telemetry ----------
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

  // Existing dashboard fields use filtered readings
  payload += "\"temperature\":";
  payload += String(filteredTemperature, 2);
  payload += ",";

  payload += "\"humidity\":";
  payload += String(filteredHumidity, 2);
  payload += ",";

  // Week 3 raw and filtered fields
  payload += "\"rawTemperature\":";
  payload += String(rawTemperature, 2);
  payload += ",";

  payload += "\"filteredTemperature\":";
  payload += String(filteredTemperature, 2);
  payload += ",";

  payload += "\"rawHumidity\":";
  payload += String(rawHumidity, 2);
  payload += ",";

  payload += "\"filteredHumidity\":";
  payload += String(filteredHumidity, 2);
  payload += ",";

  // Outlier flags
  payload += "\"temperatureOutlier\":";

  if (temperatureOutlier) {
    payload += "true,";
  } else {
    payload += "false,";
  }

  payload += "\"humidityOutlier\":";

  if (humidityOutlier) {
    payload += "true,";
  } else {
    payload += "false,";
  }

  // Calibration offsets
  payload += "\"temperatureOffset\":";
  payload += String(TEMPERATURE_OFFSET, 2);
  payload += ",";

  payload += "\"humidityOffset\":";
  payload += String(HUMIDITY_OFFSET, 2);
  payload += ",";

  // Other telemetry fields
  payload += "\"battery\":null,";

  payload += "\"light\":";
  payload += String(lightLux, 2);
  payload += ",";

  payload += "\"rssi\":";
  payload += String(WiFi.RSSI());
  payload += ",";

  payload += "\"firmwareVersion\":\"firmware-3.0.0\"";

  payload += "}";

  // ---------- Serial Monitor output ----------
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

  Serial.print("Filter samples: ");
  Serial.print(temperatureFilter.count);
  Serial.print("/");
  Serial.println(FILTER_WINDOW);

  Serial.print("Payload length: ");
  Serial.println(payload.length());

  Serial.println("Publishing telemetry:");
  Serial.println(payload);

  // ---------- Publish to MQTT ----------
  bool success =
      client.publish(
          MQTT_TOPIC,
          payload.c_str()
      );

  if (success) {
    Serial.println("Publish successful");
  } else {
    Serial.println("Publish failed");
  }
}

// ---------- Setup ----------
void setup() {
  Serial.begin(115200);
  delay(1000);

  initialiseSensors();

  connectToWiFi();

  runId = createRunId();

  Serial.print("Run ID: ");
  Serial.println(runId);

  // Skips TLS certificate validation.
  // Suitable for development, but not final production.
  espClient.setInsecure();

  client.setServer(MQTT_HOST, MQTT_PORT);

  // Week 3 JSON is larger than the Week 2 message
  client.setBufferSize(1024);

  connectToMQTT();

  Serial.println();
  Serial.println("Week 3 system ready");
}

// ---------- Main loop ----------
void loop() {
  // Reconnect Wi-Fi if necessary
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("WiFi disconnected");
    connectToWiFi();
  }

  // Reconnect MQTT if necessary
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