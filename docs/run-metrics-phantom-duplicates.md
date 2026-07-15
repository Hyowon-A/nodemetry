# Note: run history phantom duplicates (fixed 2026-07-15)

## Symptom

Run history showed `duplicates_skipped > 0` and `total_saved < total_received` even
though every received message had a row in `sensor_readings`, with simulator
`duplicateRate=0`.

Example (run `20260715T052239Z-b3218232`): received=236, DB rows=236, saved=141,
dupes=95. Every affected run satisfied `saved + dupes == received`.

## Root cause

Local dev and the production deployment (`nodemetry.onrender.com`) share the same
HiveMQ Cloud broker **and** the same Supabase Postgres (`backend/.env`). Both
backends subscribe to `nodemetry/+/telemetry`, so the broker delivers **every
message to both instances**, and both write the same DB.

Per message, whichever instance's batch flush ran first inserted the row; the other
found the `message_id` already present and counted a "duplicate". The run's
counters live only in the in-memory `RunRegistry` of the instance where the run was
registered (local, via `localhost:8080`), so:

- rows the local instance won → `recordSaved` → saved = 141
- rows production won → local counted a phantom dupe → dupes = 95
- production's own saved-events hit its registry, which had no counters for the
  run → silently dropped
- every row was inserted exactly once → DB rows = 236 = received

This was **not** a duplicate-detection bug in `TelemetryBatchIngestService`, and
not run-id collision — runs with unique random-suffix run ids showed it too.

## Fix

Run totals are now **reconciled against the database** instead of trusting
single-instance in-memory event counts:

- `RunRegistry.flushCounters()` / `endRun()` set
  `total_saved = count(sensor_readings where run_id = ?)` (DB truth) and
  `duplicates_skipped = max(0, locallyProcessedEvents − dbCount)`.
  A message another instance saved first cancels out (one local dupe event, one DB
  row); a *real* duplicate send still counts (event without a row).
- The hard "freeze counters at end" became a grace window
  (`run.metrics.end-grace-ms`, default 15s): totals keep settling from the DB
  after a run ends (late queue drains, the other instance's in-flight batches),
  then the run is evicted from the registry.
- `SensorReadingRepository.countByRunId` added (cheap via the existing
  `(run_id, received_at)` index); `TestRunRepository.updateCounters` no longer
  skips ended runs so grace-window updates can land.

## Verification (live, prod ingesting in parallel)

- `duplicateRate=0`, 98 messages: received=98, saved=98, dupes=0, DB rows=98.
- `duplicateRate=0.3`, stopped mid-flight, 156 sent incl. 50 re-sends:
  received=156, saved=106 (= DB rows), dupes=50.

## Caveat

Any environment pointing a backend at the shared broker + DB ingests everything
twice; the metrics are now immune to that, but it still doubles write load. If
that ever matters, isolate dev with its own topic prefix or database.
