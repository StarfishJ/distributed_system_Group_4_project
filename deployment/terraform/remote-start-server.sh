#!/usr/bin/env bash
set -euo pipefail
cd /home/ec2-user/app
set -a
if [ -f server.env ]; then
  # shellcheck disable=SC1091
  . ./server.env
fi
set +a
pkill -f 'server-v2.jar' || true
sleep 2
JAVA="$(command -v java || true)"
if [ -z "${JAVA}" ] || [ ! -x "${JAVA}" ]; then
  JAVA=/usr/lib/jvm/java-17-amazon-corretto/bin/java
fi
nohup "${JAVA}" -jar server-v2.jar >> server.log 2>&1 &
sleep 12
curl -fsS --max-time 15 "http://127.0.0.1:8080/health"
echo
echo "SERVER_OK"
