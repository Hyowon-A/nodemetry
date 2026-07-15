#!/usr/bin/env python3
"""
Nodemetry virtual MQTT node load simulator.

Spawns N virtual sensor nodes that publish JSON telemetry to the same broker,
topics, and schema as a real ESP32 node, so you can load test the Spring Boot
ingestion backend without physical hardware.

Topics (match docs/mqtt-topics.md):
    nodemetry/{nodeId}/telemetry   <- readings
    nodemetry/{nodeId}/status      <- "online"/"offline" (retained), also Last Will

Examples
--------
# Medium scenario: 100 nodes, one message every 10s, QoS 1
python simulator.py --nodes 100 --interval 10 --qos 1 \
    --broker your-broker.hivemq.cloud --port 8883 --tls \
    --username USER --password PASS

# Stretch scenario over a free-tier broker (few connections, many nodes)
python simulator.py --nodes 1000 --interval 10 --qos 1 --shared --connections 5 ...

# Exercise duplicate handling: 20% of messages are re-sent with the same messageId
python simulator.py --nodes 100 --interval 10 --qos 1 --duplicate-rate 0.2 ...

Requires: pip install "paho-mqtt>=2.0"
"""

import argparse
import email.utils
import json
import os
import random
import secrets
import signal
import ssl
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import timezone
from urllib.request import Request, urlopen
from dotenv import load_dotenv

import paho.mqtt.client as mqtt

load_dotenv()

# ----------------------------------------------------------------------------
# Shared, thread-safe counters for publish calls accepted by the MQTT client.
# ----------------------------------------------------------------------------
class Stats:
    def __init__(self):
        self._lock = threading.Lock()
        self.queued = 0
        self.duplicate_queued = 0
        self.publish_failures = 0

    def inc(self, field_name, n=1):
        with self._lock:
            setattr(self, field_name, getattr(self, field_name) + n)

    def snapshot(self):
        with self._lock:
            return self.queued, self.duplicate_queued, self.publish_failures


STATS = Stats()
STOP = threading.Event()
START = threading.Event()


class WarmupGate:
    def __init__(self, expected):
        self.expected = expected
        self.ready = 0
        self.failed = 0
        self._condition = threading.Condition()

    def mark_ready(self):
        with self._condition:
            self.ready += 1
            self._condition.notify_all()

    def mark_failed(self):
        with self._condition:
            self.failed += 1
            self._condition.notify_all()

    def wait_until_settled(self):
        with self._condition:
            self._condition.wait_for(
                lambda: self.ready + self.failed >= self.expected or STOP.is_set()
            )

    def snapshot(self):
        with self._condition:
            return self.ready, self.failed, self.expected


# ----------------------------------------------------------------------------
# A single virtual node. Holds its own random-walk sensor state and sequence
# counter so messageIds are stable and incrementing, like a real device.
# ----------------------------------------------------------------------------
@dataclass
class VirtualNode:
    node_id: str
    run_id: str
    firmware: str = "firmware-1.0.0"
    seq: int = 0
    temperature_raw: float = field(default_factory=lambda: random.uniform(19, 26))
    temperature_filtered: float = 0.0
    humidity_raw: float = field(default_factory=lambda: random.uniform(40, 60))
    humidity_filtered: float = 0.0
    battery: float = field(default_factory=lambda: random.uniform(60, 100))
    rssi: float = field(default_factory=lambda: random.uniform(-80, -50))
    light: float = field(default_factory=lambda: random.uniform(100, 10000))

    def __post_init__(self):
        # Filtered readings start pinned to the raw value, then lag it via EMA.
        self.temperature_filtered = self.temperature_raw
        self.humidity_filtered = self.humidity_raw

    def _walk(self, value, step, lo, hi):
        value += random.uniform(-step, step)
        return max(lo, min(hi, value))

    def _filter(self, filtered, raw, alpha=0.3):
        # Exponential moving average: a smoothed reading that trails the raw one.
        return filtered + alpha * (raw - filtered)

    def next_payload(self, reuse_last_id=False):
        """Build the next telemetry payload, advancing the random walk."""
        if not reuse_last_id:
            self.seq += 1
        self.temperature_raw = self._walk(self.temperature_raw, 0.3, -10, 50)
        self.temperature_filtered = self._filter(self.temperature_filtered, self.temperature_raw)
        self.humidity_raw = self._walk(self.humidity_raw, 0.8, 0, 100)
        self.humidity_filtered = self._filter(self.humidity_filtered, self.humidity_raw)
        self.battery = max(0.0, self.battery - random.uniform(0, 0.02))
        self.rssi = self._walk(self.rssi, 2, -95, -40)
        self.light = self._walk(self.light, 500, 0, 100000)

        message_id = f"{self.seq:06d}"

        return {
            "messageId": f"{self.node_id}-{self.run_id}-{message_id}",
            "nodeId": self.node_id,
            "runId": self.run_id,
            "temperatureRaw": round(self.temperature_raw, 2),
            "temperatureFiltered": round(self.temperature_filtered, 2),
            "humidityRaw": round(self.humidity_raw, 2),
            "humidityFiltered": round(self.humidity_filtered, 2),
            "battery": round(self.battery, 1),
            "light": round(self.light, 1),
            "rssi": round(self.rssi, 1),
            "firmwareVersion": self.firmware,
        }


def topic(prefix, node_id, suffix):
    return f"{prefix}/{node_id}/{suffix}"


def status_payload(status):
    return json.dumps({"status": status})


INTERNET_TIME_URL = "https://www.google.com/generate_204"


def format_run_id_from_time_tuple(time_tuple):
    return time.strftime("%Y%m%dT%H%M%SZ", time_tuple)


def unique_run_id(timestamp):
    return f"{timestamp}-{secrets.token_hex(4)}"


def local_utc_run_id():
    return unique_run_id(format_run_id_from_time_tuple(time.gmtime()))


def internet_utc_run_id(timeout=3):
    request = Request(
        INTERNET_TIME_URL,
        headers={"User-Agent": "nodemetry-simulator/1.0"},
        method="HEAD",
    )
    with urlopen(request, timeout=timeout) as response:
        date_header = response.headers.get("Date")
    if not date_header:
        raise RuntimeError("missing Date header")
    internet_time = email.utils.parsedate_to_datetime(date_header)
    timestamp = internet_time.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return unique_run_id(timestamp)


def default_run_id():
    try:
        return internet_utc_run_id()
    except Exception as exc:  # noqa: BLE001
        print(
            f"warning: online UTC lookup failed ({exc}); using local UTC clock",
            file=sys.stderr,
        )
    return local_utc_run_id()


def make_client(client_id, args):
    """Create and connect a paho client (paho-mqtt 2.x API)."""
    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=client_id,
        clean_session=True,
    )
    if args.username:
        client.username_pw_set(args.username, args.password)
    if args.tls:
        client.tls_set(cert_reqs=ssl.CERT_REQUIRED, tls_version=ssl.PROTOCOL_TLS_CLIENT)
    return client


# ----------------------------------------------------------------------------
# Per-node-connection mode: one TCP connection per virtual node (most realistic,
# best for testing Last Will / offline detection; limited by broker conn cap).
# ----------------------------------------------------------------------------
def wait_for_measurement_start():
    while not STOP.is_set():
        if START.wait(timeout=0.1):
            return True
    return False


def run_node_dedicated(node, args, ready_gate):
    client = make_client(f"sim-{node.node_id}", args)
    status_topic = topic(args.prefix, node.node_id, "status")
    # Last Will: broker publishes this if the connection drops uncleanly.
    client.will_set(status_topic, payload=status_payload("offline"), qos=1, retain=True)

    try:
        client.connect(args.broker, args.port, keepalive=int(max(30, args.interval * 2)))
    except Exception as exc:  # noqa: BLE001
        print(f"[{node.node_id}] connect failed: {exc}", file=sys.stderr)
        ready_gate.mark_failed()
        return
    client.loop_start()
    ready_gate.mark_ready()

    if not wait_for_measurement_start():
        client.loop_stop()
        client.disconnect()
        return

    client.publish(status_topic, status_payload("online"), qos=1, retain=True)

    tele_topic = topic(args.prefix, node.node_id, "telemetry")
    # Spread first publishes across the interval so they don't all fire at once.
    STOP.wait(random.uniform(0, args.interval))

    while not STOP.is_set():
        _publish_once(client, node, tele_topic, args)
        # interval with light jitter, but wake promptly on shutdown
        STOP.wait(args.interval + random.uniform(-0.5, 0.5))

    # Clean shutdown: explicit offline (so it isn't only the Last Will path).
    client.publish(status_topic, status_payload("offline"), qos=1, retain=True)
    time.sleep(0.2)
    client.loop_stop()
    client.disconnect()


# ----------------------------------------------------------------------------
# Shared-connection mode: a few connections each drive many virtual nodes.
# Use this to reach high message rates on connection-capped free brokers.
# ----------------------------------------------------------------------------
def run_shared_connection(conn_index, nodes, args, ready_gate):
    client = make_client(f"sim-shared-{conn_index}", args)
    try:
        client.connect(args.broker, args.port, keepalive=60)
    except Exception as exc:  # noqa: BLE001
        print(f"[conn-{conn_index}] connect failed: {exc}", file=sys.stderr)
        ready_gate.mark_failed()
        return
    client.loop_start()
    ready_gate.mark_ready()

    if not wait_for_measurement_start():
        client.loop_stop()
        client.disconnect()
        return

    for n in nodes:
        client.publish(topic(args.prefix, n.node_id, "status"), status_payload("online"), qos=1, retain=True)

    # Give each node a stable phase offset evenly spread across the interval so
    # this shared connection paces its publishes across the window instead of
    # bursting them all at the top of every cycle (which spikes the broker and
    # backend and defeats the point of a steady load test).
    count = len(nodes)
    offsets = [i * args.interval / count for i in range(count)] if count else []

    # Keep manual CLI behavior unchanged. The API-gated path has already spent
    # this phase warming up, so don't add startup loss to the measured window.
    if not args.start_gate_stdin:
        STOP.wait(random.uniform(0, args.interval))

    while not STOP.is_set():
        cycle_start = time.time()
        for offset, n in zip(offsets, nodes):
            if STOP.is_set():
                break
            # Hold this node's publish until its slot within the current cycle.
            STOP.wait(max(0.0, (cycle_start + offset) - time.time()))
            if STOP.is_set():
                break
            _publish_once(client, n, topic(args.prefix, n.node_id, "telemetry"), args)
        elapsed = time.time() - cycle_start
        STOP.wait(max(0.0, args.interval - elapsed))

    for n in nodes:
        client.publish(topic(args.prefix, n.node_id, "status"), status_payload("offline"), qos=1, retain=True)
    time.sleep(0.2)
    client.loop_stop()
    client.disconnect()


def _publish_once(client, node, tele_topic, args):
    # Occasionally re-send the previous messageId to exercise dedup logic.
    reuse = args.duplicate_rate > 0 and random.random() < args.duplicate_rate and node.seq > 0
    payload = node.next_payload(reuse_last_id=reuse)
    info = client.publish(tele_topic, json.dumps(payload), qos=args.qos)
    if info.rc == mqtt.MQTT_ERR_SUCCESS:
        STATS.inc("queued")
        if reuse:
            STATS.inc("duplicate_queued")
    else:
        STATS.inc("publish_failures")


def build_nodes(args):
    nodes = []
    for i in range(1, args.nodes + 1):
        nodes.append(
            VirtualNode(
                node_id=f"{args.node_prefix}-{i:04d}",
                run_id=args.run_id,
            )
        )
    return nodes


def chunk(seq, parts):
    parts = max(1, parts)
    out = [[] for _ in range(parts)]
    for i, item in enumerate(seq):
        out[i % parts].append(item)
    return [c for c in out if c]


def parse_args():
    p = argparse.ArgumentParser(description="Nodemetry virtual MQTT load simulator")
    p.add_argument("--nodes", type=int, default=10, help="number of virtual nodes")
    p.add_argument("--interval", type=float, default=10, help="seconds between publishes per node")
    p.add_argument("--qos", type=int, default=1, choices=[0, 1, 2])
    # Connection settings default to environment variables so credentials
    # don't end up in shell history or committed files. A CLI flag, if given,
    # overrides the env var. Precedence: CLI flag > env var > built-in default.
    p.add_argument("--broker", default=os.environ.get("MQTT_BROKER") or os.environ.get("MQTT_HOST") or "localhost")
    p.add_argument("--port", type=int, default=int(os.environ.get("MQTT_PORT", "8883")))
    p.add_argument(
        "--tls",
        action="store_true",
        default=os.environ.get("MQTT_TLS", "").lower() in ("1", "true", "yes"),
        help="use TLS (typically with --port 8883). Also set via MQTT_TLS=true",
    )
    p.add_argument("--username", default=os.environ.get("MQTT_USERNAME"))
    p.add_argument("--password", default=os.environ.get("MQTT_PASSWORD"))
    p.add_argument("--prefix", default="nodemetry", help="topic root, e.g. nodemetry/{nodeId}/telemetry")
    p.add_argument(
        "--node-prefix",
        default=None,
        help="virtual node id prefix (default: vnode, or test-node with --test)",
    )
    p.add_argument(
        "--test",
        action="store_true",
        help="use the 'test-node' id prefix to keep test traffic separate from vnode load testing",
    )
    p.add_argument(
        "--run-id",
        help=(
            "run id sent as runId and included in messageId "
            "(default: online UTC timestamp plus random suffix, e.g. 20260706T132045Z-a1b2c3d4)"
        ),
    )
    p.add_argument("--duration", type=float, default=0, help="stop after N seconds (0 = run until Ctrl+C)")
    p.add_argument("--duplicate-rate", type=float, default=0.0, help="fraction (0-1) of messages re-sent with same messageId")
    p.add_argument("--shared", action="store_true", help="multiplex nodes over a few connections (for capped brokers)")
    p.add_argument("--connections", type=int, default=5, help="connections to use in --shared mode")
    p.add_argument(
        "--start-gate-stdin",
        action="store_true",
        help="connect clients, print READY, then wait for 'start' on stdin before publishing telemetry",
    )
    args = p.parse_args()
    if args.node_prefix is None:
        args.node_prefix = "test-node" if args.test else "vnode"
    if args.run_id is None:
        args.run_id = default_run_id()
    return args


def main():
    args = parse_args()
    nodes = build_nodes(args)
    groups = chunk(nodes, args.connections) if args.shared else []
    expected_connections = len(groups) if args.shared else len(nodes)
    ready_gate = WarmupGate(expected_connections)

    expected_rate = args.nodes / args.interval if args.interval else 0
    mode = f"shared/{args.connections} conns" if args.shared else "one conn per node"
    print(
        f"Starting {args.nodes} {args.node_prefix} nodes ({mode}), QoS {args.qos}, "
        f"interval {args.interval}s -> ~{expected_rate:.1f} msg/s expected, "
        f"run_id={args.run_id}",
        flush=True,
    )

    def handle_sigint(_sig, _frame):
        print("\nStopping (publishing offline status)...", flush=True)
        STOP.set()

    signal.signal(signal.SIGINT, handle_sigint)
    signal.signal(signal.SIGTERM, handle_sigint)

    if not args.start_gate_stdin:
        START.set()

    threads = []
    if args.shared:
        for idx, group in enumerate(groups):
            t = threading.Thread(target=run_shared_connection, args=(idx, group, args, ready_gate), daemon=True)
            t.start()
            threads.append(t)
    else:
        for node in nodes:
            t = threading.Thread(target=run_node_dedicated, args=(node, args, ready_gate), daemon=True)
            t.start()
            threads.append(t)
            time.sleep(0.005)  # gentle connection ramp to avoid a thundering herd

    if args.start_gate_stdin:
        ready_gate.wait_until_settled()
        ready, failed, expected = ready_gate.snapshot()
        if failed or ready < expected:
            print(f"WARMUP_FAILED warmup_connected={ready}/{expected} failures={failed}", flush=True)
            STOP.set()
        else:
            print(f"READY warmup_connected={ready}/{expected}", flush=True)
            command = sys.stdin.readline().strip().lower()
            if command == "start":
                START.set()
            else:
                print("Start gate closed before measurement began", file=sys.stderr, flush=True)
                STOP.set()

    start = time.time()
    try:
        while not STOP.is_set():
            time.sleep(1)
            if args.duration and (time.time() - start) >= args.duration:
                STOP.set()
            queued, dup, fail = STATS.snapshot()
            elapsed = max(1e-6, time.time() - start)
            print(
                f"\r  queued={queued} duplicate_queued={dup} failures={fail} "
                f"queued_rate={queued / elapsed:.1f} msg/s   ",
                end="",
                flush=True,
            )
    finally:
        STOP.set()
        for t in threads:
            t.join(timeout=5)
        queued, dup, fail = STATS.snapshot()
        elapsed = max(1e-6, time.time() - start)
        print(
            f"\nDone. total_queued={queued} duplicates_queued={dup} "
            f"failures={fail} avg_queued_rate={queued / elapsed:.1f} msg/s over {elapsed:.0f}s"
        )


if __name__ == "__main__":
    main()
