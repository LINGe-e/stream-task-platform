#!/usr/bin/env bash
set -u

exec > /tmp/flink-restore-single.txt 2>&1

conf=/home/l1ngee/stress-data/flink/conf/config.yaml
workers=/home/l1ngee/stress-data/flink/conf/workers

echo "=== 停止多 TaskManager 集群 ==="
/home/l1ngee/stress-data/flink/bin/stop-cluster.sh

echo "=== 恢复单 TaskManager 配置 ==="
cp "${conf}.single-20261008" "${conf}"
cp "${workers}.single-20261008" "${workers}"
cat "${workers}"
grep -n 'taskmanager.rpc.port' "${conf}"
grep -n 'taskmanager.numberOfTaskSlots' "${conf}"

echo "=== 启动单 TaskManager 集群 ==="
/home/l1ngee/stress-data/flink/bin/start-cluster.sh
sleep 6
curl -fsS http://127.0.0.1:8081/overview
echo
exit 0
