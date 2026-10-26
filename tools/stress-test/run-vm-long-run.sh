#!/usr/bin/env bash
set -u

exec > /tmp/vm-long-run.log 2>&1

export A22_LONG_CASE_ID=vm-long-run-20261008-p4-r6000
python3 /home/l1ngee/stress-data/tools/stress-test/long-run-kafka-flink.py
code=$?
echo "EXIT=$code"
exit $code
