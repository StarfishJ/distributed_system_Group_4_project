#!/usr/bin/env python3
"""
CS6650 Assignment 2 - System Monitor
Collects metrics from RabbitMQ Management API and application health endpoints.
Outputs CSV for reporting and live console display.

Usage:
    python3 monitor.py --rabbitmq-host <IP> [--server-host <IP>] [--interval 5]

Requirements:
    pip3 install requests (or use curl fallback)
"""

import argparse
import csv
import json
import sys
import time
import urllib.request
import base64
from datetime import datetime

# ============================================================
# RabbitMQ Management API (port 15672)
# ============================================================

def rabbitmq_overview(host, user="guest", password="guest"):
    """Fetch RabbitMQ overview: message rates, connections, channels."""
    url = f"http://{host}:15672/api/overview"
    return _api_get(url, user, password)


def rabbitmq_queues(host, user="guest", password="guest"):
    """Fetch all queue details: depth, rates, consumers."""
    url = f"http://{host}:15672/api/queues"
    return _api_get(url, user, password)


def _api_get(url, user, password):
    """HTTP GET with basic auth (no external dependencies)."""
    credentials = base64.b64encode(f"{user}:{password}".encode()).decode()
    req = urllib.request.Request(url, headers={
        "Authorization": f"Basic {credentials}",
        "Accept": "application/json"
    })
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        print(f"  [WARN] Cannot reach {url}: {e}", file=sys.stderr)
        return None


# ============================================================
# Application Health Endpoints
# ============================================================

def app_health(host, port):
    """Fetch /health from server-v2 or consumer."""
    url = f"http://{host}:{port}/health"
    try:
        with urllib.request.urlopen(url, timeout=3) as resp:
            return json.loads(resp.read().decode())
    except Exception:
        return None


# ============================================================
# Display & CSV Export
# ============================================================

def print_header():
    print("=" * 80)
    print(f"{'Time':<12} {'Queue':<25} {'Depth':>8} {'Pub/s':>8} "
          f"{'Con/s':>8} {'Consumers':>10}")
    print("=" * 80)


def print_queue_row(ts, name, depth, pub_rate, con_rate, consumers):
    print(f"{ts:<12} {name:<25} {depth:>8} {pub_rate:>8.1f} "
          f"{con_rate:>8.1f} {consumers:>10}")


def collect_and_display(args, csv_writer):
    """Single collection cycle."""
    ts = datetime.now().strftime("%H:%M:%S")

    # --- RabbitMQ Queues ---
    queues = rabbitmq_queues(args.rabbitmq_host, args.rabbitmq_user, args.rabbitmq_password)
    if queues:
        total_depth = 0
        total_pub = 0.0
        total_con = 0.0
        for q in queues:
            name = q.get("name", "?")
            depth = q.get("messages", 0)
            pub_rate = q.get("message_stats", {}).get("publish_details", {}).get("rate", 0.0)
            con_rate = q.get("message_stats", {}).get("deliver_get_details", {}).get("rate", 0.0)
            consumers = q.get("consumers", 0)

            total_depth += depth
            total_pub += pub_rate
            total_con += con_rate

            # Only show non-empty or active queues
            if depth > 0 or pub_rate > 0 or con_rate > 0:
                print_queue_row(ts, name, depth, pub_rate, con_rate, consumers)

            # Write every queue to CSV
            if csv_writer:
                csv_writer.writerow([
                    datetime.now().isoformat(), name, depth,
                    f"{pub_rate:.1f}", f"{con_rate:.1f}", consumers
                ])

        print(f"{ts:<12} {'--- TOTAL ---':<25} {total_depth:>8} "
              f"{total_pub:>8.1f} {total_con:>8.1f}")
    else:
        print(f"{ts:<12} [RabbitMQ unreachable]")

    # --- Application Health ---
    if args.server_host:
        health = app_health(args.server_host, args.server_port)
        if health:
            print(f"{ts:<12} Server health: {json.dumps(health)}")

    if args.consumer_host:
        health = app_health(args.consumer_host, args.consumer_port)
        if health:
            print(f"{ts:<12} Consumer health: {json.dumps(health)}")

    print("-" * 80)


# ============================================================
# Main
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="CS6650 System Monitor")
    parser.add_argument("--rabbitmq-host", required=True, help="RabbitMQ host IP")
    parser.add_argument("--rabbitmq-user", default="guest")
    parser.add_argument("--rabbitmq-password", default="guest")
    parser.add_argument("--server-host", default=None, help="Server-v2 host IP")
    parser.add_argument("--server-port", type=int, default=8080)
    parser.add_argument("--consumer-host", default=None, help="Consumer host IP")
    parser.add_argument("--consumer-port", type=int, default=8081)
    parser.add_argument("--interval", type=int, default=5, help="Poll interval in seconds")
    parser.add_argument("--csv", default="metrics.csv", help="CSV output file")
    args = parser.parse_args()

    # Open CSV
    csv_file = open(args.csv, "w", newline="")
    csv_writer = csv.writer(csv_file)
    csv_writer.writerow(["timestamp", "queue_name", "depth", "publish_rate",
                         "consume_rate", "consumers"])

    print(f"Monitoring RabbitMQ at {args.rabbitmq_host} every {args.interval}s")
    print(f"CSV output: {args.csv}")
    print_header()

    try:
        while True:
            collect_and_display(args, csv_writer)
            csv_file.flush()
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nMonitor stopped.")
    finally:
        csv_file.close()
        print(f"Metrics saved to {args.csv}")


if __name__ == "__main__":
    main()
