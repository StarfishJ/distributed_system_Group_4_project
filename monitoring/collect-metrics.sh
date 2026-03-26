#!/usr/bin/env bash
# CS6650 Assignment 3 — snapshot server metrics (Unix/macOS)
# Usage: ./monitoring/collect-metrics.sh [BASE_URL] [--refresh]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
BASE="${BASE%/}"
OUT="$(dirname "$0")/results"
mkdir -p "$OUT"
TS="$(date +%Y%m%d-%H%M%S)"

fetch() {
  local path="$1"
  local suffix="$2"
  local url="${BASE}${path}"
  if curl -sfS "$url" -o "${OUT}/${TS}-${suffix}.json"; then
    echo "OK $url -> ${OUT}/${TS}-${suffix}.json"
  else
    echo "FAIL $url" >&2
  fi
}

fetch "/health" "health"
if [[ "${2:-}" == "--refresh" ]]; then
  fetch "/metrics?refreshMaterializedViews=true" "metrics"
else
  fetch "/metrics" "metrics"
fi
echo "Done. Files under: $OUT"
