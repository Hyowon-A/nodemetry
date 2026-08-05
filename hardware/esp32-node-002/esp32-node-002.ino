#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_SHT4x.h>
#include <BH1750.h>
#include <time.h>

// Wifi details

const char* ssid = "your-wifi-name";
const char* wifi_password = "your-wifi-password";

// MQTT details
const char* mqtt_server = "your-broker-host";
const int mqtt_port = 8883;
const char* mqtt_username = "your-MQTT-username";
const char* mqtt_password = "your-MQTT-password";

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

// Moving-average filter settings
const int FILTER_SIZE = 5;

float temperatureReadings[FILTER_SIZE] = {0};
float humidityReadings[FILTER_SIZE] = {0};

int filterIndex = 0;
int validReadingCount = 0;

//Outlier

float previousTemperature = 0.0;
float previousHumidity = 0.0;

bool hasPreviousReading = false;

const float TEMPERATURE_OUTLIER_THRESHOLD = 3.0;
const float HUMIDITY_OUTLIER_THRESHOLD = 10.0;


// Create a run ID using the current UTC time
String createRunId() {
  configTime(0, 0, "pool.ntp.org", "time.google.com");

  struct tm timeinfo;

  while (!getLocalTime(&timeinfo)) {
    Serial.println("Waiting for NTP time...");
    delay(1000);
  }

  char buffer[32];

  strftime(
    buffer,
    sizeof(buffer),
    "%Y%m%dT%H%M%SZ",
    &timeinfo
  );

  return String(buffer);
}

// Create a unique message ID
String createMessageId() {
  sequenceNumber++;

  char seqBuffer[16];

  sprintf(
    seqBuffer,
    "%06lu",
    sequenceNumber
  );

  return nodeId
         + "-"
         + runId
         + "-"
         + String(seqBuffer);
}

// Connect the ESP32 to Wi-Fi
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
}

// Connect the ESP32 to the MQTT broker
void connectMQTT() {
  while (!client.connected()) {
    Serial.println("Connecting to MQTT...");

    String clientId = "esp32-node-002-";
    clientId += String(random(0xffff), HEX);

    bool connected = client.connect(
      clientId.c_str(),
      mqtt_username,
      mqtt_password
    );

    if (connected) {
      Serial.println("MQTT connected!");
    } else {
      Serial.print("MQTT failed, rc=");
      Serial.print(client.state());
      Serial.println(" trying again in 5 seconds");

      delay(5000);
    }
  }
}

// Start the SHT40 and BH1750 sensors
void setupSensors() {
  Wire.begin(21, 22);

  delay(1000);

  Serial.println("Starting sensors...");

  if (!sht4.begin(&Wire)) {
    Serial.println("Could not find SHT40. Check wiring.");

    while (true) {
      delay(10);
    }
  }

  Serial.println("SHT40 found.");

  sht4.setPrecision(SHT4X_HIGH_PRECISION);
  sht4.setHeater(SHT4X_NO_HEATER);

  if (!lightMeter.begin(
        BH1750::CONTINUOUS_HIGH_RES_MODE,
        0x23,
        &Wire
      )) {
    Serial.println("Could not find BH1750. Check wiring.");

    while (true) {
      delay(10);
    }
  }

  Serial.println("BH1750 found.");
  Serial.println("Sensors ready.");
}

// Store the newest temperature and humidity readings
void updateMovingAverage(
  float temperature,
  float humidity
) {
  temperatureReadings[filterIndex] = temperature;
  humidityReadings[filterIndex] = humidity;

  filterIndex++;

  if (filterIndex >= FILTER_SIZE) {
    filterIndex = 0;
  }

  if (validReadingCount < FILTER_SIZE) {
    validReadingCount++;
  }
}

// Calculate the average of the stored readings
float calculateAverage(float readings[]) {
  float total = 0.0;

  for (int i = 0; i < validReadingCount; i++) {
    total += readings[i];
  }

  return total / validReadingCount;
}

void rejectOutliers(
  float temperatureRaw,
  float humidityRaw,
  float &temperatureAccepted,
  float &humidityAccepted
) {
  if (!hasPreviousReading) {
    temperatureAccepted = temperatureRaw;
    humidityAccepted = humidityRaw;

    previousTemperature = temperatureRaw;
    previousHumidity = humidityRaw;

    hasPreviousReading = true;
    return;
  }

  float temperatureDifference =
    abs(temperatureRaw - previousTemperature);

  float humidityDifference =
    abs(humidityRaw - previousHumidity);

  if (temperatureDifference > TEMPERATURE_OUTLIER_THRESHOLD) {
    temperatureAccepted = previousTemperature;

    Serial.println("Temperature outlier rejected.");
  } else {
    temperatureAccepted = temperatureRaw;
    previousTemperature = temperatureRaw;
  }

  if (humidityDifference > HUMIDITY_OUTLIER_THRESHOLD) {
    humidityAccepted = previousHumidity;

    Serial.println("Humidity outlier rejected.");
  } else {
    humidityAccepted = humidityRaw;
    previousHumidity = humidityRaw;
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  setupSensors();
  connectWiFi();

  runId = createRunId();

  Serial.print("Run ID: ");
  Serial.println(runId);

  espClient.setInsecure();

  client.setServer(mqtt_server, mqtt_port);

  client.setBufferSize(512);
  client.setKeepAlive(30);

  connectMQTT();
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    connectWiFi();
  }

  if (!client.connected()) {
    connectMQTT();
  }

  client.loop();

  sensors_event_t humidityEvent;
  sensors_event_t temperatureEvent;

  sht4.getEvent(
    &humidityEvent,
    &temperatureEvent
  );

  float temperatureRaw =
    temperatureEvent.temperature;

  float humidityRaw =
    humidityEvent.relative_humidity;

  float light =
    lightMeter.readLightLevel();

  int rssi =
    WiFi.RSSI();

  float temperatureAccepted;
  float humidityAccepted;

  rejectOutliers(
    temperatureRaw,
    humidityRaw,
    temperatureAccepted,
    humidityAccepted
  );

  // Temporary fake battery value
  float battery = 87.0;

  updateMovingAverage(
    temperatureAccepted,
    humidityAccepted
  );

  float temperatureFiltered =
    calculateAverage(temperatureReadings);

  float humidityFiltered =
    calculateAverage(humidityReadings);

  Serial.println();
  Serial.println("Sensor readings");

  Serial.print("Temperature raw: ");
  Serial.print(temperatureRaw, 2);
  Serial.print(" C | filtered: ");
  Serial.print(temperatureFiltered, 2);
  Serial.println(" C");

  Serial.print("Humidity raw: ");
  Serial.print(humidityRaw, 2);
  Serial.print(" % | filtered: ");
  Serial.print(humidityFiltered, 2);
  Serial.println(" %");

  Serial.print("Light: ");
  Serial.print(light, 1);
  Serial.println(" lux");

  Serial.print("RSSI: ");
  Serial.print(rssi);
  Serial.println(" dBm");

  String messageId = createMessageId();

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

  // Separate raw and filtered fields
  payload += "\"temperatureRaw\":";
  payload += String(temperatureRaw, 2);
  payload += ",";

  payload += "\"temperatureFiltered\":";
  payload += String(temperatureFiltered, 2);
  payload += ",";

  payload += "\"humidityRaw\":";
  payload += String(humidityRaw, 2);
  payload += ",";

  payload += "\"humidityFiltered\":";
  payload += String(humidityFiltered, 2);
  payload += ",";

  // Light remains unfiltered
  payload += "\"light\":";
  payload += String(light, 1);
  payload += ",";

  payload += "\"battery\":";
  payload += String(battery, 1);
  payload += ",";

  payload += "\"rssi\":";
  payload += String(rssi);
  payload += ",";

  payload += "\"firmwareVersion\":\"";
  payload += String(firmwareVersion);
  payload += "\"";

  payload += "}";

  Serial.print("Publishing sensor data: ");
  Serial.println(payload);

  bool publishSuccessful =
    client.publish(topic, payload.c_str());

  if (publishSuccessful) {
    Serial.println("MQTT publish successful.");
  } else {
    Serial.println("MQTT publish failed.");
  }

  delay(10000);
}