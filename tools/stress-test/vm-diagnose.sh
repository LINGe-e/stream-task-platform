#!/usr/bin/env bash
set -u

exec > /tmp/a22-diagnose.txt 2>&1

echo "=== 时间 ==="
date
echo "=== 启动脚本输出 ==="
cat /tmp/a22-start-services.txt 2>/dev/null || true
echo "=== Flink 日志目录 ==="
ls -lt /home/l1ngee/stress-data/flink/log 2>/dev/null || true
echo "=== 最新 Flink 日志 ==="
latest_log=$(ls -t /home/l1ngee/stress-data/flink/log/*.log 2>/dev/null | head -1)
if [ -n "${latest_log:-}" ]; then
    tail -n 160 "$latest_log"
else
    echo "未找到 Flink 日志"
fi
echo "=== 端口 ==="
ss -ltnp 2>/dev/null | grep -E ':(9092|8081)\b' || true
echo "=== 进程 ==="
ps -ef | grep -E '[F]link|[K]afka' || true
echo "=== REST ==="
curl -fsS http://127.0.0.1:8081/overview || true
echo
exit 0
