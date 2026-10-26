#!/usr/bin/env bash
set -euo pipefail

cd /opt/a22/a22

if [ ! -f .env.cloud ]; then
  echo "Missing /opt/a22/a22/.env.cloud"
  echo "It must contain OCEANBASE_PASSWORD=..."
  exit 1
fi

cat > docker-compose.oceanbase-stable.yml <<'YAML'
name: a22-cloud

services:
  oceanbase:
    image: quay.io/oceanbase/oceanbase-ce:latest
    restart: unless-stopped
    ulimits:
      nofile:
        soft: 655350
        hard: 655350
      stack:
        soft: -1
        hard: -1
    environment:
      MODE: slim
      OB_TENANT_PASSWORD: ${OCEANBASE_PASSWORD:?Set OCEANBASE_PASSWORD in .env.cloud}
      OB_MEMORY_LIMIT: 4G
      OB_SYSTEM_MEMORY: 512M
      OB_DATAFILE_SIZE: 5G
      OB_LOG_DISK_SIZE: 5G
    volumes:
      - oceanbase-data:/root/ob
      - oceanbase-cluster:/root/.obd/cluster
    ports:
      - "127.0.0.1:2881:2881"
    expose:
      - "2886"
    networks: [a22]

networks:
  a22:
    driver: bridge

volumes:
  oceanbase-data:
  oceanbase-cluster:
YAML

# Local development uses local backend/frontend, so keep cloud app containers from
# competing with OceanBase memory. This does not remove data volumes.
docker update --restart=no \
  a22-cloud-backend-1 \
  a22-cloud-frontend-1 \
  a22-cloud-redis-1 \
  a22-flink-jobmanager-1 \
  a22-flink-taskmanager-1 \
  a22-cloud-kafka-1 >/dev/null 2>&1 || true

docker stop \
  a22-cloud-backend-1 \
  a22-cloud-frontend-1 \
  a22-cloud-redis-1 \
  a22-flink-jobmanager-1 \
  a22-flink-taskmanager-1 \
  a22-cloud-kafka-1 >/dev/null 2>&1 || true

sync || true
echo 3 > /proc/sys/vm/drop_caches || true

docker compose -f docker-compose.oceanbase-stable.yml --env-file .env.cloud up -d --force-recreate oceanbase

echo "Waiting for OceanBase SQL port..."
for i in $(seq 1 40); do
  if docker exec a22-cloud-oceanbase-1 bash -lc "ss -lntp | grep -q ':2881'"; then
    break
  fi
  sleep 5
done

echo "Waiting for OceanBase tenant..."
for i in $(seq 1 60); do
  if docker exec a22-cloud-oceanbase-1 obclient -h127.0.0.1 -P2881 -uroot@test -p"$(grep '^OCEANBASE_PASSWORD=' .env.cloud | cut -d= -f2-)" -e "CREATE DATABASE IF NOT EXISTS a22 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; SHOW DATABASES;"; then
    echo "OceanBase is ready."
    exit 0
  fi
  sleep 5
done

echo "OceanBase did not become ready. Recent logs:"
docker logs --tail 120 a22-cloud-oceanbase-1 | grep -Ei "error|warn|fail|memory|observer|init" || true
exit 1
