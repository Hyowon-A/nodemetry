# Nodemetry Backend

Spring Boot backend for Nodemetry. It subscribes to MQTT telemetry and status
topics, validates sensor messages, batches PostgreSQL writes, maintains node
health and run metrics, exposes REST read APIs, and streams live updates over
WebSocket/STOMP.

## Requirements

- Java 21
- PostgreSQL
- Maven wrapper included as `./mvnw`
- MQTT broker when `MQTT_ENABLED=true`

## Environment Variables

Required for database access:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

MQTT ingest:

```text
MQTT_ENABLED
MQTT_HOST
MQTT_PORT
MQTT_USERNAME
MQTT_PASSWORD
MQTT_CLIENT_ID
```

Frontend and production behavior:

```text
FRONTEND_ALLOWED_ORIGINS
HTTP_API_READ_ONLY
```

Optional ingest tuning:

```text
TELEMETRY_INGEST_QUEUE_CAPACITY
TELEMETRY_INGEST_BATCH_SIZE
TELEMETRY_INGEST_FLUSH_INTERVAL_MS
```

Optional run-metric tuning:

```text
RUN_METRICS_VIRTUAL_NODE_PREFIXES
RUN_METRICS_PHYSICAL_IDLE_EVICT_MS
RUN_METRICS_END_GRACE_MS
```

Use `.env.example` as the template for `backend/.env`. `run-local.sh` sources
`backend/.env`, so local secrets can live there. Do not commit `.env` files.

## Run Locally

```bash
./run-local.sh
```

This runs:

```bash
./mvnw spring-boot:run
```

The app serves HTTP on port `8080` by default. MQTT ingest is disabled unless
`MQTT_ENABLED=true`.

## Test and Package

```bash
./mvnw test
./mvnw -DskipTests package
```

## REST Endpoints

```text
GET   /api/v1/nodes
GET   /api/v1/nodes/{nodeId}/latest
GET   /api/v1/nodes/{nodeId}/readings
GET   /api/v1/nodes/{nodeId}/runs
GET   /api/v1/nodes/{nodeId}/runs/{runId}/readings
GET   /api/v1/runs
POST  /api/v1/runs
PATCH /api/v1/runs/{runId}/end
GET   /api/v1/metrics/ingestion
```

In production read-only mode, `/api/**` permits only `GET`, `HEAD`, and
`OPTIONS`; HTTP write methods are rejected.

## API Documentation

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Export the OpenAPI document for Postman or other API tooling:

```bash
curl http://localhost:8080/v3/api-docs \
  -o ../docs/nodemetry-openapi.json
```

## WebSocket/STOMP

Endpoint:

```text
/ws
```

Broker topics:

```text
/topic/readings
/topic/nodes/status
/topic/nodes/{nodeId}/latest
```

The backend uses Spring's simple broker with `/topic` destinations.

## Package Structure

```text
com.nodemetry.backend
├── config      CORS, security, frontend origins, WebSocket
├── mqtt        MQTT properties, subscriber, message handler
├── node        Node entity, repository, health/status service
├── run         Virtual run registry and physical node run metrics
└── telemetry   Reading entity, repositories, ingest services, controllers
```

## Database Setup and Migrations

The backend uses PostgreSQL in normal operation and H2 for tests. Current
configuration uses:

```yaml
spring.jpa.hibernate.ddl-auto: update
```

That is convenient for development, but production should move to managed
migrations and set Hibernate to `validate`.

Main tables:

- `nodes`
- `sensor_readings`
- `virtual_node_runs`
- `physical_node_runs`

## Ingestion Flow

1. `MqttSubscriber` subscribes to `nodemetry/+/telemetry` and
   `nodemetry/+/status`.
2. `MqttMessageHandler` parses JSON and ignores retained telemetry and status
   messages.
3. Telemetry is enqueued into `TelemetryBatchIngestService`.
4. Batches are drained on the configured interval.
5. Invalid messages are counted and skipped before database work.
6. Existing `messageId` values are counted as duplicates.
7. New readings are inserted, node health is upserted, and STOMP updates are
   published.
8. Virtual and physical run counters are updated from saved and duplicate
   counts.

## Idempotency Design

`sensor_readings.messageId` is unique. MQTT QoS 1 can redeliver messages, so the
backend treats repeated `messageId` values as duplicates instead of new sensor
events. This keeps persistence idempotent even when the broker or publisher
retries delivery.

## Batching and Persistence Behavior

The ingest service uses an in-memory queue and drains up to
`TELEMETRY_INGEST_BATCH_SIZE` messages per flush. It deduplicates within the
batch, checks the database for existing message IDs, then writes new rows in a
transaction. Counters distinguish saved rows from duplicate events.

## Node Health Handling

Telemetry updates a node's battery, RSSI, firmware version, status, and
`last_seen_at`. Status messages can mark nodes online or offline. A scheduled
status task marks stale nodes offline and broadcasts status changes.

## Production Notes

- The Docker image activates `SPRING_PROFILES_ACTIVE=prod`.
- In `prod` or `production`, `HTTP_API_READ_ONLY` defaults to `true`.
- MQTT ingestion is unaffected by HTTP read-only mode.
- REST reads and WebSocket access remain unauthenticated.
- Configure `FRONTEND_ALLOWED_ORIGINS` for browser CORS and WebSocket origin
  checks.
- Use a secret store for database and MQTT credentials.
- Replace Hibernate `ddl-auto=update` with migrations before long-term
  production use.
