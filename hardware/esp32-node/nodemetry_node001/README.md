# ESP32 Hardware Documentation

## 1. Final Hardware Setup

Describe the completed ESP32 sensor node and how the components are arranged.

![Final hardware setup](image.png)!

## 2. Hardware Components and Sensor Selection

| Component | Purpose | Reason for Selection |
| --- | --- | --- |
| ESP32 | Main controller and Wi-Fi connection | Built-in Wi-Fi and suitable for MQTT-based IoT projects |
| SHT31 | Temperature and humidity measurement | Provides digital I2C measurements with suitable accuracy |
| BH1750 | Ambient light measurement | Directly outputs light intensity in lux and supports I2C |

## 3. Sensor Wiring

Both sensors use I2C and share the same SDA and SCL lines.

| Sensor | Sensor Pin | ESP32 Pin |
| --- | --- | --- |
| SHT31 | VIN | 3.3 V |
| SHT31 | GND | GND |
| SHT31 | SDA | GPIO 21 |
| SHT31 | SCL | GPIO 22 |
| BH1750 | VCC | 3.3 V |
| BH1750 | GND | GND |
| BH1750 | SDA | GPIO 21 |
| BH1750 | SCL | GPIO 22 |

![ESP32 sensor wiring](images/esp32-wiring.jpg)

## 4. Raw Versus Filtered Sensor Results

The ESP32 node records both raw and filtered temperature and humidity readings.
The raw readings are the direct values measured by the sensors, while the
filtered readings are processed by the firmware to reduce short-term
fluctuations and produce a smoother trend.

The purpose of filtering is not to change the measured environmental conditions.
Instead, it reduces small variations that may be caused by sensor noise,
temporary air movement, or minor changes around the sensor. This makes the
dashboard graphs easier to read and helps users identify the overall trend.

### Temperature Results

![Raw and filtered temperature results](image-3.png)

During the test, the raw temperature remained approximately between 23.9 °C and
24.2 °C. The raw line contained several small and rapid changes between
successive readings.

The filtered temperature line was smoother and changed more gradually while
still following the same overall trend as the raw measurements. This shows that
the filtering process reduced minor fluctuations without significantly changing
the recorded temperature.

### Humidity Results

![Raw and filtered humidity results](image-1.png)

The raw humidity readings fluctuated more noticeably than the temperature
readings, with values approximately between 44% and 52%.

The filtered humidity values produced a smoother curve around the overall
humidity trend. Sudden increases and decreases in the raw data had less effect
on the filtered output, making the displayed humidity values more stable and
easier to interpret.

### Light Results

![Raw light sensor results](image-2.png)

Only raw light readings were displayed during this test. The measured light
level changed considerably because the BH1750 sensor responds directly to
changes in ambient light, shadows, sensor position, and nearby movement.

A filtered light value was not included in the current dashboard. Light
filtering could be added in future work if a smoother trend is required.
However, retaining raw light readings also allows the node to respond quickly
to sudden changes in lighting conditions.

### Results Summary

| Measurement | Raw Result | Filtered Result | Observation |
| --- | --- | --- | --- |
| Temperature | Approximately 23.9–24.2 °C with small short-term fluctuations | Smoother values following the same overall trend | Filtering reduced minor rapid changes |
| Humidity | Approximately 44–52% with noticeable short-term fluctuations | More stable and gradual curve | Filtering improved the readability of the humidity trend |
| Light | Large changes depending on ambient lighting conditions | Not implemented or displayed during this test | Raw readings responded quickly to changes in light |

### Filtering Trade-Off

The filtered values respond slightly more slowly than the raw values because the
filter considers previous measurements when calculating the next output. This
can introduce a small delay when the real temperature or humidity changes
suddenly.

For this project, the delay is acceptable because temperature and humidity
normally change gradually. A stable dashboard trend is more useful than
displaying every small sensor fluctuation.

Overall, the filtering process successfully reduced short-term variation in the
temperature and humidity readings while preserving the main environmental
trends. The results demonstrate improved stability and readability, but they do
not prove that the filtered measurements are more accurate than the raw
measurements because no calibrated reference instrument was used.

## 5. RSSI and QoS Reliability Testing

The reliability testing examined two different parts of the Nodemetry system:

1. the Wi-Fi signal strength and reconnection behaviour of the physical ESP32
   node; and
2. the delivery behaviour of MQTT QoS 0 and QoS 1 using virtual nodes.

These tests were conducted separately because RSSI applies to the physical
ESP32 Wi-Fi connection, while the QoS comparison was performed using the Python
MQTT simulator.

### Physical ESP32 RSSI Test

RSSI, or Received Signal Strength Indicator, represents the strength of the
ESP32 Wi-Fi connection. RSSI is measured in dBm. Values closer to `0 dBm`
indicate a stronger signal, while more negative values indicate a weaker
connection.

The ESP32 was tested at several locations with increasing distance and physical
obstruction from the Wi-Fi router.

| Location | RSSI (dBm) | Signal Quality |
| --- | ---: | --- |
| Beside router | -36 | Excellent |
| Same room | -50 | Good |
| Next room | -66 | Fair |
| Furthest room | -76 | Weak |

The results show that the Wi-Fi signal became weaker as the ESP32 was moved
farther away from the router. RSSI decreased from `-36 dBm` beside the router to
`-76 dBm` in the furthest tested room.

Under excellent, good, and fair signal conditions, the ESP32 continued reading
sensor values and publishing telemetry successfully. The Serial Monitor
repeatedly displayed `Publish successful`, showing that the MQTT connection
remained operational.

![ESP32 publishing under normal Wi-Fi conditions](image-4.png)

The physical node therefore maintained stable telemetry transmission at RSSI
values between approximately `-36 dBm` and `-66 dBm`. At `-76 dBm`, the signal
was weak and the connection was more vulnerable to interruption.

### Out-of-Range Disconnection and Automatic Reconnection

A further test was completed by moving the ESP32 much farther away from the
router until it left the usable Wi-Fi range.

When the ESP32 moved outside the Wi-Fi range, the Wi-Fi and MQTT connection was
lost, and telemetry publishing stopped temporarily. The firmware continued
attempting to reconnect automatically rather than requiring a manual reset.

The Serial Monitor recorded a failed MQTT connection attempt:

```text
Connecting to MQTT... failed, MQTT state=-2 - retrying in 5 seconds
```
![Wifi break](image-5.png)

### MQTT QoS 0 Versus QoS 1 Test

The MQTT simulator was tested using QoS 0 and QoS 1 under identical conditions:

- 10 virtual nodes;
- a 10-second publishing interval;
- a test duration of approximately 300 seconds;
- an expected average throughput of approximately 1.0 message per second.

QoS 0 provides at-most-once delivery. Messages are published without requiring
an acknowledgement from the broker. This reduces protocol overhead, but a
message may be lost if the connection fails before it reaches the broker.

QoS 1 provides at-least-once delivery. The broker acknowledges each message,
and an unacknowledged message may be retransmitted. This improves delivery
assurance, although it also means that the same message may be delivered more
than once.

### QoS Test Results

| MQTT QoS | Virtual Nodes | Publish Interval | Duration | Messages Queued | Failures | Intentional Duplicates | Average Throughput |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| QoS 0 | 10 | 10 s | Approximately 300 s | 299 | 0 | 0 | 1.0 msg/s |
| QoS 1 | 10 | 10 s | Approximately 300 s | 301 | 0 | 0 | 1.0 msg/s |

Both tests completed with zero publishing failures and the same average
throughput of approximately `1.0 msg/s`.

QoS 0 queued 299 messages, while QoS 1 queued 301 messages. The difference of
two messages is too small to indicate a meaningful performance difference and
was most likely caused by slight variation in the exact start and stop times of
the two tests.

At the tested load, QoS 1 did not cause a noticeable reduction in throughput.
It therefore provided acknowledgement-based delivery assurance without creating
a significant performance penalty.

### Duplicate Handling

QoS 1 does not guarantee exactly-once delivery. If an acknowledgement is delayed
or lost, the publisher or broker may retransmit the same message.

Nodemetry handles this by assigning a unique `messageId` to every telemetry
reading.

- The first occurrence of a unique `messageId` is stored.
- A repeated `messageId` is identified as a duplicate delivery.
- The repeated message is counted but is not stored as an additional sensor
  reading.

No intentionally queued duplicates were included in this QoS comparison.
Therefore, this test compared the normal performance of QoS 0 and QoS 1 but did
not directly test forced duplicate redelivery.

### QoS Results Summary

| Test | Main Result | Interpretation |
| --- | --- | --- |
| QoS 0 simulator test | 299 messages, 0 failures, 1.0 msg/s | Reliable operation was observed at the tested load |
| QoS 1 simulator test | 301 messages, 0 failures, 1.0 msg/s | Acknowledgement-based delivery caused no noticeable throughput reduction |
| Duplicate handling | No intentional duplicates in this comparison | Unique `messageId` values remain necessary because QoS 1 may redeliver messages |

### QoS Key Findings

The simulator results showed that QoS 0 and QoS 1 achieved effectively
equivalent throughput under a load of 10 virtual nodes publishing every
10 seconds.

Both tests recorded zero failures and an average throughput of approximately
`1.0 msg/s`. QoS 1 therefore added broker acknowledgement without causing a
noticeable reduction in performance at this scale.

QoS 1 is the more suitable default for Nodemetry telemetry where reliable
delivery is important. However, the backend must continue checking unique
`messageId` values because QoS 1 provides at-least-once delivery and may produce
duplicate messages.


## 6. Hardware Demonstration

A short video was recorded showing:

- the completed ESP32 node;
- live sensor readings in the Serial Monitor;
- MQTT connection;
- readings appearing on the dashboard;

[View the hardware demonstration](videos/hardware-demo.mp4)

## 7. Key Findings

The completed ESP32 node successfully measured temperature, humidity, and
ambient light and transmitted the sensor data to the Nodemetry dashboard through
Wi-Fi and MQTT.

The main findings were:

- The SHT31 and BH1750 sensors operated successfully on the same I2C bus using
  GPIO 21 for SDA and GPIO 22 for SCL.
- Filtering reduced short-term fluctuations in the temperature and humidity
  readings, producing smoother and easier-to-read dashboard trends.
- The filtered values followed the same overall trends as the raw values, but
  responded slightly more slowly to sudden changes.
- The test confirmed improved stability and readability, but not improved
  measurement accuracy because no calibrated reference instrument was used.
- The ESP32 maintained successful telemetry transmission under excellent, good,
  and fair Wi-Fi conditions, with RSSI values between approximately `-36 dBm`
  and `-66 dBm`.
- At approximately `-76 dBm`, the Wi-Fi signal was weak and the connection became
  more vulnerable to interruption.
- When the ESP32 moved outside the usable Wi-Fi range, publishing stopped
  temporarily, but the firmware continued attempting to reconnect automatically.
- QoS 0 and QoS 1 both achieved approximately `1.0 msg/s` with zero failures
  during the 10-node simulator tests.
- QoS 1 provided acknowledgement-based delivery without a noticeable throughput
  penalty at the tested scale.
- QoS 1 is the more suitable default for reliable telemetry, although duplicate
  checking using unique `messageId` values is still required.

Overall, the tests showed that the Nodemetry hardware node can collect, process,
and transmit environmental data reliably under normal operating conditions. The
system also demonstrated basic resilience through automatic reconnection and
backend duplicate handling.

## 8. Limitations and Future Improvements

- Calibration offsets were not implemented because a reliable reference
  instrument was not available. Future work could compare the SHT31 and BH1750
  readings against calibrated instruments and apply correction offsets where
  necessary.
- Filtering was implemented for temperature and humidity, but not for the light
  readings. As a result, the BH1750 values responded quickly to lighting changes
  but showed larger short-term fluctuations. A light-filtering method could be
  added if a smoother dashboard trend is required.
- Weak Wi-Fi testing was performed by changing the distance and obstruction
  between the ESP32 and router rather than using a controlled signal attenuator.
- The breadboard setup may be affected by loose connections or accidental
  movement of jumper wires.
- Longer-duration reliability testing could be completed to identify connection
  failures, data loss, or sensor drift over time.
- Power-consumption measurements could also be added to evaluate the suitability
  of the node for battery-powered operation.