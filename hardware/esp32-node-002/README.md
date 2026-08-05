# ESP32 Sensor Node-002

## Overview

This folder contains the firmware and hardware documentation for
Nodemetry sensor node `node-002`.

The node measures:

- Temperature using the SHT40
- Relative humidity using the SHT40
- Light level using the BH1750
- Wi-Fi signal strength using ESP32 RSSI

The ESP32 publishes the readings to the Nodemetry MQTT broker.

## Hardware

| Component | Purpose | Interface |
|---|---|---|
| ESP32 development board with USB-C| Microcontroller and Wi-Fi connection | — |
| SHT40 | Temperature and humidity measurement | I2C |
| BH1750 | Light measurement in lux | I2C |
| Breadboard and jumper wires | Prototype connections | — |
| Data cable USB-C to USB-C | ESP32 power during development | USB |

## Wiring

| Sensor pin | ESP32 connection |
|---|---|
| SHT40 VCC | 3.3V |
| SHT40 GND | GND |
| SHT40 SDA | GPIO 21 |
| SHT40 SCL | GPIO 22 |
| BH1750 VCC | 3.3V |
| BH1750 GND | GND |
| BH1750 SDA | GPIO 21 |
| BH1750 SCL | GPIO 22 |

Both sensors share the I2C SDA and SCL lines.

## Firmware Behaviour

During each operating cycle, node-002:
1. Initialises the SHT40 and BH1750 sensors.
2. Connect the ESP32 to Wi-Fi.
3. Synchronise the device clock.
4. Generates a unique run ID
5. Connect securely to the MQTT broker.
6. Read raw temperature, humidity and light values.
7. Checks and rejects temperature and humidity outliers.
8. Applies moving-average filtering to accepted temperature and humidity readings
9. Keeps the BH1750 light measurements unfiltered
8. Read Wi-Fi RSSI.
9. Generates a unique message ID
10. Publish telemetry message to MQTT.

Light is kept unfiltered because lighting changes can happen
quickly when lights are switched on or off.

## MQTT Communication
Node-002 publishes telemtry to:
nodometry/node-002/telemtry

Telemetry currently contains:
- Node ID
- Run ID
- Message ID
- Raw temperature
- Filtered temperature
- Raw humidity
- Filtered humidity
- Light level
- Wi-Fi RSSI
- Firmware version

## Running the Firmware

1. Open the hardware/esp32-node-002 folder.
2. Open esp32-node-002.ino in the Arduino IDE.
3. Enter the required Wi-Fi and MQTT configuration
4. Connect node-002 to the computer using USB-C data cable
5. Select the correct ESP32 board.
6. Select the correct serial port.
7. Compile and upload the firmware.
8. Open Serial Monitor at 115200 baud.
9. Confirm that the sensors initialise and MQTT messages are published.

## Security
Credentials must never be committed.