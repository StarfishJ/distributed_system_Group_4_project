#!/bin/bash
# ============================================================
# Assignment 2 - Threading Optimization Test Runner
# Usage: ./run_tests.sh <SERVER_URL> <TOTAL_MSGS>
# Example: ./run_tests.sh http://3.236.240.43:8080 500000
# ============================================================

SERVER_URL=${1:?Usage: ./run_tests.sh <SERVER_URL> <TOTAL_MSGS>}
TOTAL_MSGS=${2:-500000}
THREADS_LIST="64 128 256 512"

echo "=== Starting Assignment 2 Optimization Tests ==="
echo "Server: $SERVER_URL"
echo "Messages: $TOTAL_MSGS"
echo ""

for THREADS in $THREADS_LIST; do
    echo "------------------------------------------------------------"
    echo "Running Test with $THREADS Threads..."
    
    # Update client.properties or pass via command line if Main supports it
    # Here we assume we can modify client.properties and re-run mvn exec
    sed -i "s/^main.threads=.*/main.threads=$THREADS/" src/main/resources/client.properties
    
    mvn exec:java "-Dexec.mainClass=client_part2.ChatClientMain" \
        "-Dexec.args=$SERVER_URL $TOTAL_MSGS -n"
    
    echo "Completed $THREADS threads test."
    echo "Waiting 10s for system to cool down..."
    sleep 10
done

echo ""
echo "=== All Tests Completed ==="
