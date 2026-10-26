# Flink 作业模块

本模块通过 `DynamicPipelineJob` 执行后端生成的动态管道配置，当前实现包括：

- Source：Kafka，以及 MySQL、OceanBase、PostgreSQL、Oracle 等 JDBC 数据源；文件节点复用平台已导入的数据表。
- Transform：字段映射、条件过滤、类型转换、字段拼接和去重。
- Sink：Kafka 和外部 JDBC 数据库；CSV、Excel、Redis 节点在 Flink 管道中当前回退到 MySQL 配置。

后端通过 Flink REST API 提交 JAR、查询作业状态和发送取消请求；当前仅在内存中保存本次会话的作业摘要。Checkpoint、吞吐量和详细异常信息以 Flink Dashboard 展示为准，不由后端单独持久化。
