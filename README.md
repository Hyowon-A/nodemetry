# Nodemetry

Live environmental telemetry platform for low-power MQTT sensor nodes. Devices
(or the bundled simulator) publish JSON readings over MQTT; a Spring Boot backend
ingests them into PostgreSQL and re-streams them to a SvelteKit dashboard over a
WebSocket.

## Architecture

```
ESP32 / simulator ──MQTT (TLS)──▶ HiveMQ Cloud broker
                                        │  nodemetry/{id}/telemetry
                                        │  nodemetry/{id}/status
                                        ▼
                              Spring Boot backend ──▶ PostgreSQL
                              (batch ingest, dedup)
                                        │  STOMP  /topic/readings
                                        ▼  (WebSocket /ws)
                              SvelteKit dashboard (browser)
```

- Devices publish to `nodemetry/{nodeId}/telemetry` (readings) and
  `nodemetry/{nodeId}/status` (`online`/`offline`, retained + Last Will).
- The backend subscribes, validates and de-duplicates by `messageId`, batches
  inserts into Postgres, and pushes each stored reading to STOMP subscribers.
- The dashboard bootstraps the fleet + recent history over REST, then streams
  live readings over STOMP.

## Components

| Directory    | Stack                              | Responsibility                          |
|--------------|------------------------------------|-----------------------------------------|
| `backend/`   | Spring Boot 4.1, Java 21, Postgres | MQTT ingest + REST/WebSocket API         |
| `frontend/`  | SvelteKit 2, Svelte 5              | Live dashboard (+ dev-only load tester) |
| `simulator/` | Python 3, paho-mqtt 2.x           | Virtual sensor-node load generator       |

## Quick start

### Backend
Needs Java 21 and a reachable PostgreSQL. Fill in `backend/.env` (keys below), then:

```bash
cd backend
./run-local.sh          # sources .env, runs ./mvnw spring-boot:run
```

Serves on `:8080`. MQTT ingest stays off unless `MQTT_ENABLED=true` (useful for
running the API against an existing database without a broker).

### Frontend
Needs Node 20 (pinned in `frontend/.nvmrc`).

```bash
cd frontend
npm install
npm run dev             # http://localhost:5173
```

Connects to the live backend over REST + STOMP. Set `PUBLIC_API_BASE` and
`PUBLIC_WS_URL` in `frontend/.env`; use the simulator or real devices to publish
telemetry into the backend. See [`frontend/README.md`](frontend/README.md).

### Simulator
Needs Python 3 and `paho-mqtt>=2.0`.

```bash
cd simulator
python simulator.py --nodes 100 --interval 10 --qos 1 --tls \
    --broker YOUR.hivemq.cloud --port 8883 --username U --password P
```

Or set `simulator/.env` (`MQTT_BROKER`, `MQTT_PORT`, `MQTT_USERNAME`,
`MQTT_PASSWORD`, `MQTT_TLS`) and drop the connection flags.

## Configuration

Secrets live in per-component `.env` files. **These are git-ignored — never commit
them**, and inject them from your platform's secret store when deploying.

**`backend/.env`**
```
DB_URL, DB_USERNAME, DB_PASSWORD
MQTT_ENABLED, MQTT_HOST, MQTT_PORT, MQTT_USERNAME, MQTT_PASSWORD, MQTT_CLIENT_ID
FRONTEND_ALLOWED_ORIGINS      # CORS + WebSocket origin allowlist, comma-separated
# optional ingest tuning:
TELEMETRY_INGEST_QUEUE_CAPACITY, TELEMETRY_INGEST_BATCH_SIZE, TELEMETRY_INGEST_FLUSH_INTERVAL_MS
```

**`frontend/.env`**
```
PUBLIC_API_BASE      # e.g. http://localhost:8080
PUBLIC_WS_URL        # e.g. ws://localhost:8080/ws
```

**`simulator/.env`**
```
MQTT_BROKER, MQTT_PORT, MQTT_USERNAME, MQTT_PASSWORD, MQTT_TLS
```

## API (backend)

```
GET   /api/v1/nodes
GET   /api/v1/nodes/{nodeId}/latest
GET   /api/v1/nodes/{nodeId}/readings
GET   /api/v1/nodes/{nodeId}/runs
GET   /api/v1/nodes/{nodeId}/runs/{runId}/readings
GET   /api/v1/runs
POST  /api/v1/runs                 # start a load-test run
PATCH /api/v1/runs/{runId}/end     # end a run

WS    /ws   (STOMP)
      /topic/readings                live readings
      /topic/nodes/status            status changes
      /topic/nodes/{nodeId}/latest   per-node latest reading
```

Alert evaluation and ingestion metrics are derived **client-side** in the
dashboard from the reading stream; there is no server-side alerts/metrics endpoint.

## Deployment notes

- **The API and WebSocket are currently unauthenticated** — `SecurityConfig`
  permits every request. CORS limits browser origins but not direct
  (`curl`/script) requests. Before exposing this on the public internet, put it
  behind authentication, a private network, or a gateway/reverse-proxy password.
- **The load tester is hidden in production.** The `/load-tester` page and its
  `/api/simulator` control endpoint (which spawns the Python simulator) return
  404 in any built/deployed frontend; they exist only under `npm run dev`.
- The backend `Dockerfile` builds a runnable jar. It currently runs as root and
  Hibernate `ddl-auto` is `update` — prefer a non-root user and
  `validate` + managed migrations for production.
