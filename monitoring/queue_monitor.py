#!/usr/bin/env python3
"""
queue_monitor.py - poll the RabbitMQ management API and log SkiTrak queue metrics.

Each sample sums messages / messages_ready / messages_unacknowledged / publish rate /
ack rate over resort.1 .. resort.10, records the dead-letter queue depth, appends one
row to a CSV file and prints it.

Usage:
    export RABBIT_PASS=...
    python3 queue_monitor.py --host <RabbitMQ-public-IP> [--user skitrak] [--interval 1] [--out FILE]

Stop with Ctrl+C; a short summary is printed at the end.
Standard library only (Python 3.6+).
"""
import argparse
import base64
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

PORT = 15672
RESORT_QUEUES = ["resort.%d" % i for i in range(1, 11)]
DLQ_NAME = "skitrak.dlqueue"

# Ask the API for only the fields we need (smaller response, less load on the broker).
COLUMNS = ",".join([
    "name",
    "messages",
    "messages_ready",
    "messages_unacknowledged",
    "message_stats.publish_details.rate",
    "message_stats.ack_details.rate",
])

CSV_FIELDS = [
    "timestamp", "epoch_ms", "elapsed_s",
    "messages", "messages_ready", "messages_unacknowledged",
    "publish_rate", "ack_rate",
    "dlq_messages", "resort_queues_found",
]


def parse_args():
    p = argparse.ArgumentParser(description="Monitor SkiTrak RabbitMQ queues via the management API.")
    p.add_argument("--host", required=True, help="RabbitMQ host (public IP or DNS name)")
    p.add_argument("--user", default="skitrak", help="management API user (default: skitrak)")
    p.add_argument("--password", default=os.environ.get("RABBIT_PASS"),
                   help="management API password (default: $RABBIT_PASS)")
    p.add_argument("--interval", type=float, default=1.0, help="seconds between samples (default: 1)")
    p.add_argument("--out", help="CSV output path (default: results-v2/queue_<timestamp>.csv)")
    args = p.parse_args()
    if not args.password:
        p.error("no password: set RABBIT_PASS or pass --password")
    if args.interval <= 0:
        p.error("--interval must be > 0")
    return args


def num(value):
    """Return value if it is a number, else 0 (handles missing / null fields)."""
    return value if isinstance(value, (int, float)) else 0


def rate(queue, key):
    """message_stats.<key>.rate, or 0 when the queue is idle and the stats are absent."""
    stats = queue.get("message_stats") or {}
    details = stats.get(key) or {}
    return num(details.get("rate"))


def fetch_queues(url, auth_header, timeout):
    req = urllib.request.Request(url, headers={"Authorization": auth_header})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def aggregate(queues):
    by_name = {q.get("name"): q for q in queues}
    totals = {"messages": 0, "ready": 0, "unacked": 0, "publish": 0.0, "ack": 0.0}
    found = 0
    for name in RESORT_QUEUES:
        q = by_name.get(name)
        if q is None:
            continue
        found += 1
        totals["messages"] += num(q.get("messages"))
        totals["ready"] += num(q.get("messages_ready"))
        totals["unacked"] += num(q.get("messages_unacknowledged"))
        totals["publish"] += rate(q, "publish_details")
        totals["ack"] += rate(q, "ack_details")
    dlq = by_name.get(DLQ_NAME)
    dlq_messages = num(dlq.get("messages")) if dlq else 0
    return totals, found, dlq is not None, dlq_messages


def main():
    args = parse_args()

    url = "http://%s:%d/api/queues?columns=%s" % (args.host, PORT, COLUMNS)
    token = base64.b64encode(("%s:%s" % (args.user, args.password)).encode("utf-8")).decode("ascii")
    auth_header = "Basic " + token
    timeout = max(2.0, args.interval)

    out_path = args.out or os.path.join(
        "results-v2", "queue_%s.csv" % datetime.now().strftime("%Y%m%d_%H%M%S"))
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)

    samples = 0
    peak_depth = 0
    depth_sum_active = 0
    peak_pub = peak_ack = 0.0
    active = 0
    pub_sum_active = ack_sum_active = 0.0
    last_dlq = 0
    warned = set()

    print("Monitoring http://%s:%d  every %gs  ->  %s   (Ctrl+C to stop)"
          % (args.host, PORT, args.interval, out_path))
    print("%-23s | %8s %8s %8s | %9s %9s | %6s" %
          ("time", "depth", "ready", "unacked", "pub/s", "ack/s", "dlq"))

    with open(out_path, "w", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(CSV_FIELDS)
        f.flush()

        start = time.monotonic()
        next_tick = start
        try:
            while True:
                try:
                    queues = fetch_queues(url, auth_header, timeout)
                except urllib.error.HTTPError as e:
                    if e.code == 401:
                        sys.exit("401 Unauthorized: check --user / RABBIT_PASS")
                    print("[warn] HTTP %d from management API" % e.code, file=sys.stderr)
                except (urllib.error.URLError, OSError, ValueError) as e:
                    print("[warn] request failed: %s" % e, file=sys.stderr)
                else:
                    now = datetime.now()
                    elapsed = time.monotonic() - start
                    totals, found, has_dlq, dlq_messages = aggregate(queues)

                    if found < len(RESORT_QUEUES) and "resorts" not in warned:
                        print("[warn] only %d of %d resort queues found" % (found, len(RESORT_QUEUES)),
                              file=sys.stderr)
                        warned.add("resorts")
                    if not has_dlq and "dlq" not in warned:
                        print("[warn] dead-letter queue '%s' not found (recorded as 0)" % DLQ_NAME,
                              file=sys.stderr)
                        warned.add("dlq")

                    writer.writerow([
                        now.isoformat(timespec="milliseconds"),
                        int(time.time() * 1000),
                        "%.3f" % elapsed,
                        totals["messages"], totals["ready"], totals["unacked"],
                        "%.2f" % totals["publish"], "%.2f" % totals["ack"],
                        dlq_messages, found,
                    ])
                    f.flush()  # keep the file usable even if the script is killed

                    print("%-23s | %8d %8d %8d | %9.1f %9.1f | %6d" % (
                        now.isoformat(timespec="milliseconds"),
                        totals["messages"], totals["ready"], totals["unacked"],
                        totals["publish"], totals["ack"], dlq_messages))

                    samples += 1
                    peak_depth = max(peak_depth, totals["messages"])
                    peak_pub = max(peak_pub, totals["publish"])
                    peak_ack = max(peak_ack, totals["ack"])
                    if totals["messages"] > 0 or totals["publish"] > 0 or totals["ack"] > 0:
                        active += 1
                        depth_sum_active += totals["messages"]
                        pub_sum_active += totals["publish"]
                        ack_sum_active += totals["ack"]
                    last_dlq = dlq_messages

                # fixed-rate schedule; if we fall behind, resync instead of bursting
                next_tick += args.interval
                delay = next_tick - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                else:
                    next_tick = time.monotonic()
        except KeyboardInterrupt:
            print()

    print("\n===== Summary =====")
    print("samples            : %d  (written to %s)" % (samples, out_path))
    if samples:
        print("queue depth        : peak %d, avg %.1f over %d active samples"
              % (peak_depth, depth_sum_active / active if active else 0.0, active))
        print("publish rate       : peak %.1f/s, avg %.1f/s over %d active samples"
              % (peak_pub, pub_sum_active / active if active else 0.0, active))
        print("ack (consume) rate : peak %.1f/s, avg %.1f/s over %d active samples"
              % (peak_ack, ack_sum_active / active if active else 0.0, active))
        print("dead-letter queue  : %d messages at last sample" % last_dlq)


if __name__ == "__main__":
    main()