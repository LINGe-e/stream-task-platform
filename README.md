# A22 通用流处理任务管理平台

面向 A22 赛题的最小可演示工程。当前版本已实现“拖入文件 → 解析为 JSON → 写入关系型数据库 → 前端预览”的数据接入闭环；Flink 动态管道已支持 Kafka 与外部 JDBC 数据库链路，后续可继续扩展 CDC、Redis 等接入方式。

## 工程结构

- `frontend/`：Vue 3 + Vite 管理台，提供文件拖拽工作区、JSON 预览、任务管理与运行日志页面。
- `backend/`：Spring Boot API，文件内容经解析后以 MySQL JSON 字段保存。
- `flink-jobs/`：Flink 动态管道作业，支持 Kafka、JDBC Source/Sink 和通用转换算子。
- `docker-compose.yml`：默认启动 MySQL、Redis；Kafka、Flink 与 OceanBase 分别由独立 profile 控制。

## 快速开始

1. 启动统一数据库与缓存：`docker compose up -d mysql redis`。
2. 启动后端（需 JDK 17+ 与 Maven）：`cd backend && mvn spring-boot:run`。
3. 启动前端（Node.js 24.15.0、npm 11.12.1）：`cd frontend && npm.cmd ci && npm.cmd run dev`（PowerShell 若禁止执行 `npm.ps1`，请使用 `npm.cmd`）。
4. 浏览器打开 Vite 输出的地址（默认 `http://localhost:5173`），进入“数据工作区”后拖入 JSON、CSV、XLSX 或 XLS 文件。

前端开发服务器已将 `/api` 代理到 `http://localhost:8080`。数据库连接通过 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 或 `A22_DB_PASSWORD` 环境变量配置；请勿将真实密码写入项目文件或提交到仓库。

PowerShell 示例：

```powershell
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '请填写本机数据库密码'
mvn spring-boot:run
```

## 本机需要配置

- JDK 17（当前基线为 OpenJDK 17.0.19）；
- Maven 3.9+，并确保 `mvn -v` 可用；
- Node.js 24.15.0 与 npm 11.12.1，前端依赖必须通过 `npm ci` 按 lockfile 安装；
- 统一测试浏览器：Google Chrome 154.0.8037.98；
- Docker Desktop（用于启动 MySQL；如已有 MySQL，只需新建 `a22` 数据库并调整环境变量）。

完整的虚拟机、存储、操作系统与服务启动基线见 `docs/统一虚拟机与软件环境.md`。CSV 单机测试不得启动 Kafka、Flink 或 OceanBase。

## 当前数据库设计

后端首次连接时会自动创建 `uploaded_files` 表。每个上传文件保存以下元数据：原始文件名、类型、大小、解析记录数、上传时间和 `content_json`（MySQL `JSON` 列）。CSV/Excel 的首行会作为字段名；所有格式都会规范化为 JSON 对象数组。

## 下一步对接

1. 将当前内存任务仓库换为 MySQL 持久化实现，并让任务选择已导入文件作为数据源。
2. 根据业务需要补充 CDC 源、Redis Sink 和更完整的作业指标持久化。
3. 接入 Redis 保存实时指标，接入 WebSocket/SSE 推送运行状态。
4. 增加登录、权限、任务版本、文件字段映射与告警通知。
