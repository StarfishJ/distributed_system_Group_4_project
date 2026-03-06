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
import subprocess
from datetime import datetime

# ============================================================
# AWS Dynamic IP Discovery
# ============================================================

def get_aws_ip(instance_name):
    """Fetch public IP of an EC2 instance by its Name tag using AWS CLI."""
    cmd = [
        "aws", "ec2", "describe-instances",
        "--filters", f"Name=tag:Name,Values={instance_name}", "Name=instance-state-name,Values=running",
        "--query", "Reservations[*].Instances[*].PublicIpAddress",
        "--output", "text"
    ]
    try:
        result = subprocess.check_output(cmd, stderr=subprocess.STDOUT).decode().strip()
        if not result:
            print(f"  [WARN] No running EC2 instance found with Name tag: {instance_name}", file=sys.stderr)
            return None
        return result
    except Exception as e:
        print(f"  [WARN] Failed to run AWS CLI: {e}", file=sys.stderr)
        return None

# ============================================================
# RabbitMQ Management API (port 15672)
# ============================================================

def rabbitmq_overview(host, user="guest", password="guest"):
    """Fetch RabbitMQ overview: message rates, connections, channels."""
    url = f"http://{host}:15672/api/overview"
    return _api_get(url, user, password)


def rabbitmq_connections(host, user="guest", password="guest"):
    """Fetch total connection count."""
    url = f"http://{host}:15672/api/connections"
    conns = _api_get(url, user, password)
    return len(conns) if conns is not None else 0


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
    print("=" * 90)
    print(f"{'Time':<12} {'Queue':<20} {'Depth':>8} {'Lag':>8} {'Pub/s':>8} "
          f"{'Con/s':>8} {'Consumers':>10}")
    print("=" * 90)


def print_queue_row(ts, name, depth, lag, pub_rate, con_rate, consumers):
    print(f"{ts:<12} {name:<20} {depth:>8} {lag:>8} {pub_rate:>8.1f} "
          f"{con_rate:>8.1f} {consumers:>10}")


def collect_and_display(args, csv_writer):
    """Single collection cycle."""
    ts = datetime.now().strftime("%H:%M:%S")

    # --- RabbitMQ Queues ---
    queues = rabbitmq_queues(args.rabbitmq_host, args.rabbitmq_user, args.rabbitmq_password)
    if queues:
        total_depth = 0
        total_lag = 0
        total_pub = 0.0
        total_con = 0.0
        for q in queues:
            name = q.get("name", "?")
            depth = q.get("messages", 0)
            lag = q.get("messages_ready", 0)  # Ready messages = Consumer Lag
            pub_rate = q.get("message_stats", {}).get("publish_details", {}).get("rate", 0.0)
            con_rate = q.get("message_stats", {}).get("deliver_get_details", {}).get("rate", 0.0)
            consumers = q.get("consumers", 0)

            total_depth += depth
            total_lag += lag
            total_pub += pub_rate
            total_con += con_rate

            # Track history for statistics
            if name not in queue_history: queue_history[name] = []
            queue_history[name].append(depth)

            if depth > 0 or pub_rate > 0 or con_rate > 0:
                print_queue_row(ts, name, depth, lag, pub_rate, con_rate, consumers)

            if csv_writer:
                csv_writer.writerow([
                    datetime.now().isoformat(), name, depth, lag,
                    f"{pub_rate:.1f}", f"{con_rate:.1f}", consumers
                ])

        print(f"{ts:<12} {'--- TOTAL ---':<20} {total_depth:>8} {total_lag:>8} "
              f"{total_pub:>8.1f} {total_con:>8.1f}")
    else:
        print(f"{ts:<12} [RabbitMQ unreachable]")

    # --- Application Health ---
    if args.server_hosts:
        for host in args.server_hosts.split(","):
            host = host.strip()
            health = app_health(host, args.server_port)
            if health:
                cpu = health.get('cpuUsage', '?')
                mem = health.get('memoryUsedMb', '?')
                disk = f"{health.get('diskUsedGb', '?')}/{health.get('diskTotalGb', '?')}GB"
                print(f"{ts:<12} [Server {host}] CPU: {cpu}, Mem: {mem}MB, Disk: {disk}, Sessions: {health.get('activeSessions', 0)}")

    if args.consumer_hosts:
        for host in args.consumer_hosts.split(","):
            host = host.strip()
            health = app_health(host, args.consumer_port)
            if health:
                cpu = health.get('cpuUsage', '?')
                mem = health.get('memoryUsedMb', '?')
                disk = f"{health.get('diskUsedGb', '?')}/{health.get('diskTotalGb', '?')}GB"
                print(f"{ts:<12} [Consumer {host}] CPU: {cpu}, Mem: {mem}MB, Disk: {disk}, Processed: {health.get('messagesProcessed', 0)}")

    # --- Connections (RabbitMQ Global) ---
    conn_count = rabbitmq_connections(args.rabbitmq_host, args.rabbitmq_user, args.rabbitmq_password)
    print(f"{ts:<12} Total active RabbitMQ connections: {conn_count}")

    print("-" * 90)


# ============================================================
# Statistical Tracking
# ============================================================

queue_history = {} # name -> list of depths

def get_local_system_metrics():
    """Get CPU and Mem from /proc (Linux only) or fallback."""
    try:
        # CPU: /proc/loadavg
        with open("/proc/loadavg", "r") as f:
            load = f.read().split()[0]
        # Mem: /proc/meminfo
        with open("/proc/meminfo", "r") as f:
            lines = f.readlines()
            total = 0
            free = 0
            for line in lines:
                if line.startswith("MemTotal:"):
                    total = int(line.split()[1])
                if line.startswith("MemAvailable:"):
                    free = int(line.split()[1])
            used = (total - free) // 1024
        return f"Load: {load}, Mem: {used}MB"
    except:
        return "N/A"

def print_final_summary():
    print("\n" + "=" * 90)
    print("FINAL MONITORING SUMMARY")
    print("=" * 90)
    print(f"{'Queue Name':<20} | {'Peak Depth':>10} | {'Avg Depth':>10}")
    print("-" * 90)
    for name, history in queue_history.items():
        if not history: continue
        peak = max(history)
        avg = sum(history) / len(history)
        print(f"{name:<20} | {peak:>10} | {avg:>10.1f}")
    print("=" * 90)

# ============================================================
# Main
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="CS6650 System Monitor")
    parser.add_argument("--rabbitmq-host", default=None, help="RabbitMQ host IP (overridden by --rabbitmq-instance-name)")
    parser.add_argument("--rabbitmq-instance-name", default="RabbitMQ", help="EC2 Name tag to discover RabbitMQ IP")
    parser.add_argument("--rabbitmq-user", default="guest")
    parser.add_argument("--rabbitmq-password", default="guest")
    parser.add_argument("--server-hosts", default=None, help="Comma-separated Server-v2 host IPs")
    parser.add_argument("--server-port", type=int, default=8080)
    parser.add_argument("--consumer-hosts", default=None, help="Comma-separated Consumer host IPs")
    parser.add_argument("--consumer-port", type=int, default=8081)
    parser.add_argument("--interval", type=int, default=5, help="Poll interval in seconds")
    parser.add_argument("--csv", default="metrics.csv", help="CSV output file")
    args = parser.parse_args()

    # Open CSV
    csv_file = open(args.csv, "w", newline="")
    csv_writer = csv.writer(csv_file)
    csv_writer.writerow(["timestamp", "queue_name", "depth", "publish_rate",
                         "consume_rate", "consumers"])

    # Dynamic IP Discovery
    if args.rabbitmq_instance_name:
        discovered_ip = get_aws_ip(args.rabbitmq_instance_name)
        if discovered_ip:
            print(f"Discovered RabbitMQ IP: {discovered_ip}")
            args.rabbitmq_host = discovered_ip
        elif not args.rabbitmq_host:
            print("Error: Could not discover RabbitMQ IP and no --rabbitmq-host provided.")
            sys.exit(1)
    elif not args.rabbitmq_host:
        print("Error: Either --rabbitmq-host or --rabbitmq-instance-name must be provided.")
        sys.exit(1)

    print(f"Monitoring RabbitMQ at {args.rabbitmq_host} every {args.interval}s")
    print(f"CSV output: {args.csv}")
    print_header()

    try:
        while True:
            collect_and_display(args, csv_writer)
            
            # Record local stats for RabbitMQ instance
            local_stats = get_local_system_metrics()
            print(f"{datetime.now().strftime('%H:%M:%S'):<12} [RabbitMQ host] {local_stats}")
            
            csv_file.flush()
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nMonitor stopped.")
        print_final_summary()
    finally:
        csv_file.close()
        print(f"Metrics saved to {args.csv}")


if __name__ == "__main__":
    main()
