# Flink/Kafka 流处理链路部署说明

## 架构

`
Kafka Topic (a22-transactions)
    ↓ JSON
KafkaSourceService (后端消费)
    ↓ 数据列表
TaskExecutionEngine (字段拼接等)
    ↓
DynamicTableService (写 MySQL)
    ↓
MySQL 表 (kafka_stream_output)
`

## 环境要求

- JDK 17+, Maven 3.9+, MySQL 8.4, Node.js 24.15.0, npm 11.12.1
- Kafka 3.8+ (KRaft 模式，不需要 Zookeeper)
- Flink 1.20+ (可选，Windows 需 Docker/WSL)

## 启动步骤

### 1. 启动 Kafka

下载: https://mirrors.huaweicloud.com/apache/kafka/3.8.1/kafka_2.13-3.8.1.tgz

`ash
tar -xzf kafka_2.13-3.8.1.tgz && cd kafka_2.13-3.8.1
bin/kafka-storage.sh format -t  -c config/kraft/server.properties
bin/kafka-server-start.sh config/kraft/server.properties
`

### 2. 创建 Topic + 测试数据

`ash
bin/kafka-topics.sh --create --topic a22-transactions --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1

echo '{"transaction_id":"TXN001","customer_id":"C1001","customer_name":"张三","amount":1500.50,"city":"上海","timestamp":"2026-07-27 10:00:00"}' | bin/kafka-console-producer.sh --topic a22-transactions --bootstrap-server localhost:9092
`

### 3. 配置后端

application.yml 中设置:
`yaml
app:
  kafka:
    enabled: true
spring:
  kafka:
    bootstrap-servers: localhost:9092
`

### 4. 启动后端 + 前端

`ash
cd backend && mvn spring-boot:run
cd frontend && npm ci && npm run dev
`

### 5. 操作

- 浏览器打开 http://localhost:5173
- 登录: admin / Admin@123456
- 创建任务 → 编排画布(添加 Kafka 输入 + 字段拼接 + MySQL 输出) → 执行

### 6. 验证

`sql
SELECT * FROM a22.kafka_stream_output LIMIT 10;
`

## Flink Job (可选)

`ash
cd flink-jobs && mvn clean package
bin/flink run -c com.a22.flink.A22KafkaToMysqlJob target/a22-flink-jobs-0.1.0.jar --bootstrapServers localhost:9092 --sourceTopic a22-transactions --targetTable flink_output
`

## 验收清单

- [x] Kafka 能输入 (Topic 创建 + 数据写入)
- [x] 后端能消费 (KafkaSourceService)
- [x] 数据能处理 (字段拼接、过滤、类型转换等)
- [x] MySQL 能看到结果 (kafka_stream_output 表)
- [x] 前端能看到状态 (执行结果展示)
- [x] 有失败错误说明 (异常信息返回)
- [x] 部署步骤可复现 (本文档)
- [x] Flink Job JAR 已打包
- [x] 测试数据已准备
