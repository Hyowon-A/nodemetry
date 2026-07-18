# Nodemetry Simulator

Python MQTT load simulator for Nodemetry. It creates virtual sensor nodes that
publish the same telemetry schema as physical ESP32 devices, allowing backend
ingest, idempotency, and dashboard behavior to be tested without hardware.

## Requirements

- Python 3
- `paho-mqtt>=2.0`
- `python-dotenv`

If using the existing virtual environment:

```bash
source venv/bin/activate
```

Otherwise install dependencies in your preferred environment:

```bash
pip install "paho-mqtt>=2.0" python-dotenv
```

## Environment Variables

The simulator loads `.env` from the simulator directory; use `.env.example` as
the template.

```text
MQTT_BROKER
MQTT_HOST
MQTT_PORT
MQTT_TLS
MQTT_USERNAME
MQTT_PASSWORD
```

CLI flags override environment variables.

## Basic Command

```bash
python simulator.py --nodes 100 --interval 10 --qos 1 --tls \
  --broker YOUR.hivemq.cloud --port 8883 --username U --password P
```

## Major CLI Options

| Option | Purpose |
|---|---|
| `--nodes` | Number of virtual nodes to create |
| `--interval` | Seconds between publishes per node |
| `--qos` | MQTT QoS level: `0`, `1`, or `2` |
| `--broker` | MQTT broker host |
| `--port` | MQTT broker port |
| `--tls` | Enable TLS |
| `--username` | MQTT username |
| `--password` | MQTT password |
| `--prefix` | Topic root, default `nodemetry` |
| `--node-prefix` | Prefix for generated node IDs |
| `--test` | Use `test-node` prefix |
| `--run-id` | Explicit run ID |
| `--duration` | Stop after N seconds; `0` means until interrupted |
| `--duplicate-rate` | Fraction of publishes that reuse the previous `messageId` |
| `--shared` | Multiplex many virtual nodes over fewer MQTT connections |
| `--connections` | Number of shared MQTT connections |
| `--start-gate-stdin` | Warm up connections, print `READY`, then wait for `start` on stdin |

## Dedicated Mode

Dedicated mode is the default. Each virtual node gets its own MQTT connection:

```bash
python simulator.py --nodes 100 --interval 5 --qos 1 --duration 120
```

Use this when you want behavior closest to real devices, including per-node
connections and Last Will handling. It can hit broker connection limits at high
node counts.

## Shared Mode

Shared mode spreads many virtual nodes across a smaller number of MQTT
connections:

```bash
python simulator.py --nodes 250 --interval 5 --qos 1 --shared --connections 5 --duration 120
```

Use this for throughput testing on connection-capped brokers. Shared mode still
emits per-node telemetry and status messages, but it does not model one physical
MQTT connection per node.

## Duplicate-Rate Testing

`--duplicate-rate` intentionally republishes some readings with the previous
`messageId`. This tests backend idempotency:

```bash
python simulator.py --nodes 100 --interval 5 --qos 1 --duplicate-rate 0.2 --duration 120
```

The backend should count repeated `messageId` values as duplicates and persist
only unique readings.

## QoS Comparison Testing

Run the same scenario with different QoS levels:

```bash
python simulator.py --nodes 100 --interval 5 --qos 0 --duration 120
python simulator.py --nodes 100 --interval 5 --qos 1 --duration 120
python simulator.py --nodes 100 --interval 5 --qos 2 --duration 120
```

QoS 1 is the primary target for Nodemetry because it provides at-least-once
delivery while relying on `messageId` idempotency to avoid duplicate rows.

## Example Commands

Baseline:

```bash
python simulator.py --nodes 100 --interval 5 --qos 1 --duration 120
```

Benchmark:

```bash
python simulator.py --nodes 250 --interval 5 --qos 1 --shared --connections 5 --duration 180
```

Saturation:

```bash
python simulator.py --nodes 300 --interval 5 --qos 1 --shared --connections 5 --duration 180
```

Duplicate delivery:

```bash
python simulator.py --nodes 100 --interval 5 --qos 1 --duplicate-rate 0.2 --duration 120
```

Soak test:

```bash
python simulator.py --nodes 250 --interval 5 --qos 1 --shared --connections 5 --duration 1800
```

Frontend-controlled warmup mode:

```bash
python simulator.py --nodes 100 --interval 5 --qos 1 --start-gate-stdin
```

The process prints `READY warmup_connected=N/N`, then waits for `start` on
stdin.

## Run ID and Message ID Format

Default `runId` is an online UTC timestamp when available, falling back to local
UTC:

```text
YYYYMMDDTHHMMSSZ
```

Each virtual node emits sequential message IDs in this shape:

```text
{nodeId}-{runId}-{sequence}
```

Example:

```text
vnode-0001-20260706T132045Z-000001
```

## Simulator Metric Definitions

The simulator reports local publish-call counters:

- `queued`: publish calls accepted by the local MQTT client.
- `duplicate_queued`: queued publish calls that reused a previous `messageId`.
- `failures`: publish calls rejected by the local MQTT client.
- `queued_rate`: local queued publish calls per second.

These are simulator-side counters. They are not backend delivery or persistence
metrics. Backend delivery and persistence must be measured from backend run
metrics:

- Expected: configured number of expected messages.
- Received: messages accepted by the backend.
- Duplicates: repeated `messageId` values rejected.
- Unique received: `received - duplicates`.
- Saved: unique messages persisted successfully.
- Delivery percentage: `received / expected`.
- Persistence percentage: `saved / unique received`.

## Limitations of Shared Mode

- Fewer MQTT connections than virtual nodes, so it is not a physical connection
  model.
- Last Will behavior is connection-level, not one connection per virtual node.
- Shared connections are best for throughput and persistence testing, not
  device-offline realism.

## Interpreting Results

Compare simulator expected rate with backend metrics, not only simulator queued
rate. A successful run should show high backend receipt, low unexpected
duplicates, and high persistence percentage. If MQTT receipt remains high while
saved rows fall behind, the bottleneck is likely in database persistence,
transaction cost, indexing, or batch sizing rather than broker delivery.
