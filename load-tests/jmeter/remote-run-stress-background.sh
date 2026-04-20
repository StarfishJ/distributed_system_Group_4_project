#!/bin/bash
# Run on JMeter EC2 after plans are in ~/jmeter-plan. Usage:
#   ALB_HOST=your-alb.us-east-1.elb.amazonaws.com ./remote-run-stress-background.sh
set -euo pipefail
ALB_HOST="${ALB_HOST:?set ALB_HOST to ALB DNS}"
source /etc/profile.d/jmeter.sh
cd ~/jmeter-plan
mkdir -p ~/jmeter-results
LOG=~/jmeter-results/stress-ec2-nohup.log
JTL=~/jmeter-results/stress-ec2-run.jtl
HTML=~/jmeter-results/stress-ec2-report
rm -f "$LOG"
nohup /opt/apache-jmeter/bin/jmeter -n -t assignment-stress-30min.jmx -q jmeter-run.properties \
  -Jhost="$ALB_HOST" -Jport=80 \
  -l "$JTL" -e -o "$HTML" >>"$LOG" 2>&1 &
echo $! > ~/jmeter-results/stress-ec2.pid
echo "STARTED_PID=$(cat ~/jmeter-results/stress-ec2.pid)"
echo "LOG=$LOG"
echo "JTL=$JTL"
echo "HTML=$HTML"
echo "tail: tail -f $LOG"
