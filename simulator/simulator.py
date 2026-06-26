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
import json
import os
import random
import signal
import ssl
import sys
import threading
import time
from dataclasses import dataclass, field
from dotenv import load_dotenv

import paho.mqtt.client as mqtt

load_dotenv()

# ----------------------------------------------------------------------------
# Shared, thread-safe counters so we can print real throughput at the end.
# ----------------------------------------------------------------------------
class Stats:
    def __init__(self):
        self._lock = threading.Lock()
        self.published = 0
        self.duplicates = 0
        self.publish_failures = 0

    def inc(self, field_name, n=1):
        with self._lock:
            setattr(self, field_name, getattr(self, field_name) + n)

    def snapshot(self):
        with self._lock:
            return self.published, self.duplicates, self.publish_failures


STATS = Stats()
STOP = threading.Event()


# ----------------------------------------------------------------------------
# A single virtual node. Holds its own random-walk sensor state and sequence
# counter so messageIds are stable and incrementing, like a real device.
# ----------------------------------------------------------------------------
@dataclass
class VirtualNode:
    node_id: str
    firmware: str = "1.0.0-sim"
    has_co2: bool = False
    has_light: bool = False
    seq: int = 0
    temperature: float = field(default_factory=lambda: random.uniform(19, 26))
    humidity: float = field(default_factory=lambda: random.uniform(40, 60))
    co2: float = field(default_factory=lambda: random.uniform(450, 700))
    battery: float = field(default_factory=lambda: random.uniform(60, 100))
    rssi: int = field(default_factory=lambda: random.randint(-80, -50))
    light: float = field(default_factory=lambda: random.uniform(100, 10000))

    def _walk(self, value, step, lo, hi):
        value += random.uniform(-step, step)
        return max(lo, min(hi, value))

    def next_payload(self, reuse_last_id=False):
        """Build the next telemetry payload, advancing the random walk."""
        if not reuse_last_id:
            self.seq += 1
        self.temperature = self._walk(self.temperature, 0.3, -10, 50)
        self.humidity = self._walk(self.humidity, 0.8, 0, 100)
        self.co2 = self._walk(self.co2, 15, 400, 2000)
        self.battery = max(0.0, self.battery - random.uniform(0, 0.02))
        self.rssi = int(self._walk(self.rssi, 2, -95, -40))
        self.light = self._walk(self.light, 50, 0, 100000)

        return {
            "messageId": f"{self.node_id}-{self.seq:06d}",
            "nodeId": self.node_id,
            "temperature": round(self.temperature, 2),
            "humidity": round(self.humidity, 2),
            "co2": round(self.co2, 1) if self.has_co2 else None,
            "battery": round(self.battery, 1),
            "rssi": self.rssi,
            "firmwareVersion": self.firmware,
            "light": round(self.light, 1) if self.has_light else None,
        }


def topic(prefix, node_id, suffix):
    return f"{prefix}/{node_id}/{suffix}"


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
def run_node_dedicated(node, args):
    client = make_client(f"sim-{node.node_id}", args)
    status_topic = topic(args.prefix, node.node_id, "status")
    # Last Will: broker publishes this if the connection drops uncleanly.
    client.will_set(status_topic, payload="offline", qos=1, retain=True)

    try:
        client.connect(args.broker, args.port, keepalive=max(30, args.interval * 2))
    except Exception as exc:  # noqa: BLE001
        print(f"[{node.node_id}] connect failed: {exc}", file=sys.stderr)
        return
    client.loop_start()
    client.publish(status_topic, "online", qos=1, retain=True)

    tele_topic = topic(args.prefix, node.node_id, "telemetry")
    # Spread first publishes across the interval so they don't all fire at once.
    time.sleep(random.uniform(0, args.interval))

    while not STOP.is_set():
        _publish_once(client, node, tele_topic, args)
        # interval with light jitter, but wake promptly on shutdown
        STOP.wait(args.interval + random.uniform(-0.5, 0.5))

    # Clean shutdown: explicit offline (so it isn't only the Last Will path).
    client.publish(status_topic, "offline", qos=1, retain=True)
    time.sleep(0.2)
    client.loop_stop()
    client.disconnect()


# ----------------------------------------------------------------------------
# Shared-connection mode: a few connections each drive many virtual nodes.
# Use this to reach high message rates on connection-capped free brokers.
# ----------------------------------------------------------------------------
def run_shared_connection(conn_index, nodes, args):
    client = make_client(f"sim-shared-{conn_index}", args)
    try:
        client.connect(args.broker, args.port, keepalive=60)
    except Exception as exc:  # noqa: BLE001
        print(f"[conn-{conn_index}] connect failed: {exc}", file=sys.stderr)
        return
    client.loop_start()

    for n in nodes:
        client.publish(topic(args.prefix, n.node_id, "status"), "online", qos=1, retain=True)

    offsets = {n.node_id: random.uniform(0, args.interval) for n in nodes}
    time.sleep(min(offsets.values()) if offsets else 0)

    while not STOP.is_set():
        cycle_start = time.time()
        for n in nodes:
            if STOP.is_set():
                break
            _publish_once(client, n, topic(args.prefix, n.node_id, "telemetry"), args)
        elapsed = time.time() - cycle_start
        STOP.wait(max(0.0, args.interval - elapsed))

    for n in nodes:
        client.publish(topic(args.prefix, n.node_id, "status"), "offline", qos=1, retain=True)
    time.sleep(0.2)
    client.loop_stop()
    client.disconnect()


def _publish_once(client, node, tele_topic, args):
    # Occasionally re-send the previous messageId to exercise dedup logic.
    reuse = args.duplicate_rate > 0 and random.random() < args.duplicate_rate and node.seq > 0
    payload = node.next_payload(reuse_last_id=reuse)
    info = client.publish(tele_topic, json.dumps(payload), qos=args.qos)
    if info.rc == mqtt.MQTT_ERR_SUCCESS:
        STATS.inc("published")
        if reuse:
            STATS.inc("duplicates")
    else:
        STATS.inc("publish_failures")


def build_nodes(args):
    nodes = []
    for i in range(1, args.nodes + 1):
        nodes.append(
            VirtualNode(
                node_id=f"{args.node_prefix}-{i:04d}",
                has_co2=(i % 3 == 0),     # ~1/3 of nodes report CO2
                has_light=(i % 5 == 0),  # ~1/5 of nodes report light
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
    p.add_argument("--node-prefix", default="vnode", help="virtual node id prefix")
    p.add_argument("--duration", type=float, default=0, help="stop after N seconds (0 = run until Ctrl+C)")
    p.add_argument("--duplicate-rate", type=float, default=0.0, help="fraction (0-1) of messages re-sent with same messageId")
    p.add_argument("--shared", action="store_true", help="multiplex nodes over a few connections (for capped brokers)")
    p.add_argument("--connections", type=int, default=5, help="connections to use in --shared mode")
    return p.parse_args()


def main():
    args = parse_args()
    nodes = build_nodes(args)

    expected_rate = args.nodes / args.interval if args.interval else 0
    mode = f"shared/{args.connections} conns" if args.shared else "one conn per node"
    print(
        f"Starting {args.nodes} virtual nodes ({mode}), QoS {args.qos}, "
        f"interval {args.interval}s -> ~{expected_rate:.1f} msg/s expected",
        flush=True,
    )

    def handle_sigint(_sig, _frame):
        print("\nStopping (publishing offline status)...", flush=True)
        STOP.set()

    signal.signal(signal.SIGINT, handle_sigint)
    signal.signal(signal.SIGTERM, handle_sigint)

    threads = []
    if args.shared:
        for idx, group in enumerate(chunk(nodes, args.connections)):
            t = threading.Thread(target=run_shared_connection, args=(idx, group, args), daemon=True)
            t.start()
            threads.append(t)
    else:
        for node in nodes:
            t = threading.Thread(target=run_node_dedicated, args=(node, args), daemon=True)
            t.start()
            threads.append(t)
            time.sleep(0.005)  # gentle connection ramp to avoid a thundering herd

    start = time.time()
    try:
        while not STOP.is_set():
            time.sleep(1)
            if args.duration and (time.time() - start) >= args.duration:
                STOP.set()
            pub, dup, fail = STATS.snapshot()
            elapsed = max(1e-6, time.time() - start)
            print(
                f"\r  published={pub} duplicates={dup} failures={fail} "
                f"rate={pub / elapsed:.1f} msg/s   ",
                end="",
                flush=True,
            )
    finally:
        STOP.set()
        for t in threads:
            t.join(timeout=5)
        pub, dup, fail = STATS.snapshot()
        elapsed = max(1e-6, time.time() - start)
        print(
            f"\nDone. total_published={pub} duplicates_sent={dup} "
            f"failures={fail} avg_rate={pub / elapsed:.1f} msg/s over {elapsed:.0f}s"
        )


if __name__ == "__main__":
    main()
