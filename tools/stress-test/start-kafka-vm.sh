#!/usr/bin/env bash
set -u

exec > /tmp/start-kafka-vm.txt 2>&1

if /usr/bin/ss -ltn | /usr/bin/grep -q ':9092'; then
    echo "Kafka 已在运行"
else
    echo "启动 Kafka"
    nohup /home/l1ngee/stress-data/kafka/bin/kafka-server-start.sh \
        /home/l1ngee/stress-data/kafka/config/kafka-vm.properties \
        > /tmp/kafka-server.out 2>&1 < /dev/null &
    disown
fi

for _ in $(seq 1 30); do
    if /usr/bin/ss -ltn | /usr/bin/grep -q ':9092'; then
        echo "Kafka 9092 已监听"
        exit 0
    fi
    sleep 1
done

echo "Kafka 启动超时"
tail -n 80 /tmp/kafka-server.out
exit 1
