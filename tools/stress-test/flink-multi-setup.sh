#!/usr/bin/env bash
set -u

exec > /tmp/flink-multi-setup.txt 2>&1

conf=/home/l1ngee/stress-data/flink/conf/config.yaml
workers=/home/l1ngee/stress-data/flink/conf/workers

echo "=== 备份单 TaskManager 配置 ==="
if [ ! -f "${conf}.single-20261008" ]; then
    cp "${conf}" "${conf}.single-20261008"
fi
if [ ! -f "${workers}.single-20261008" ]; then
    cp "${workers}" "${workers}.single-20261008"
fi

echo "=== 停止现有集群 ==="
/home/l1ngee/stress-data/flink/bin/stop-cluster.sh

echo "=== 修改多 TaskManager 配置 ==="
sed -i '/^taskmanager.resource-id:/d' "${conf}"
sed -i 's/^taskmanager.rpc.port:.*/taskmanager.rpc.port: 0/' "${conf}"
sed -i 's/^taskmanager.data.port:.*/taskmanager.data.port: 0/' "${conf}"
printf 'localhost\nlocalhost\nlocalhost\nlocalhost\n' > "${workers}"
cat "${workers}"
grep -n 'taskmanager.rpc.port' "${conf}"
grep -n 'taskmanager.data.port' "${conf}"
grep -n 'taskmanager.numberOfTaskSlots' "${conf}"

echo "=== 启动 4 TaskManager 集群 ==="
/home/l1ngee/stress-data/flink/bin/start-cluster.sh
sleep 8

echo "=== 集群概览 ==="
curl -fsS http://127.0.0.1:8081/overview
echo
echo "=== TaskManager 列表 ==="
curl -fsS http://127.0.0.1:8081/taskmanagers
echo
echo "=== 端口 ==="
ss -ltnp
exit 0
