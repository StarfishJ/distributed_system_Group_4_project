#!/usr/bin/env bash
set -euo pipefail
cd /home/ec2-user/app
set -a
if [ -f consumer.env ]; then
  # shellcheck disable=SC1091
  . ./consumer.env
fi
set +a
pkill -f 'consumer-v3.jar' || true
sleep 2
JAVA="$(command -v java || true)"
if [ -z "${JAVA}" ] || [ ! -x "${JAVA}" ]; then
  JAVA=/usr/lib/jvm/java-17-amazon-corretto/bin/java
fi
nohup "${JAVA}" -Dconsumer.rooms='*' -jar consumer-v3.jar >> consumer.log 2>&1 &
sleep 12
curl -fsS --max-time 15 "http://127.0.0.1:8081/actuator/health"
echo
echo "CONSUMER_OK"
