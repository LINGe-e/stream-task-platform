package com.a22.platform.flink;

import com.a22.platform.datasource.JdbcConnectionSettings;
import com.a22.platform.datasource.JdbcDialect;
import com.a22.platform.datasource.OutputDataSourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 将前端画布的 graph JSON 翻译为 Flink 动态管道配置。
 * 管道配置格式由 DynamicPipelineJob 定义。
 */
@Service
public class PipelineConfigGenerator {
    private final ObjectMapper mapper;
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final String kafkaServers;
    private final OutputDataSourceService outputDataSourceService;

    public PipelineConfigGenerator(
            ObjectMapper mapper,
            @Value("${app.flink.mysql-url:${spring.datasource.url:}}") String jdbcUrl,
            @Value("${app.flink.mysql-username:${spring.datasource.username:root}}") String jdbcUser,
            @Value("${app.flink.mysql-password:${spring.datasource.password:}}") String jdbcPassword,
            @Value("${app.kafka.bootstrap-servers:localhost:9092}") String kafkaServers,
            OutputDataSourceService outputDataSourceService) {
        this.mapper = mapper;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        this.kafkaServers = kafkaServers;
        this.outputDataSourceService = outputDataSourceService;
    }

    /**
     * 从 graph JSON 生成管道配置。
     *
     * @param graphJson     画布 JSON（nodes + edges）
     * @param sourceTable   可选：覆盖源表名（用于文件导入场景）
     * @param sinkTableName 可选：覆盖输出表名
     * @return 管道配置 JSON 字符串
     */
    public String generate(String graphJson, String sourceTable, String sinkTableName) throws Exception {
        JsonNode graph = mapper.readTree(graphJson);
        List<JsonNode> nodes = topologicalSort(graph);

        // 分离 source / transform / sink 节点
        JsonNode sourceNode = null;
        JsonNode sinkNode = null;
        List<JsonNode> transformNodes = new ArrayList<>();

        for (JsonNode node : nodes) {
            String nodeType = node.path("data").path("nodeType").asText();
            switch (nodeType) {
                case "source" -> sourceNode = node;
                case "transform" -> transformNodes.add(node);
                case "sink" -> sinkNode = node;
                // start / end 忽略
            }
        }

        ObjectNode config = mapper.createObjectNode();
        config.put("jobName", "a22-dynamic-" + System.currentTimeMillis());

        // 生成数据源配置
        if (sourceNode != null) {
            config.set("source", buildSourceConfig(sourceNode, sourceTable));
        }

        // 生成变换链
        ArrayNode transforms = mapper.createArrayNode();
        for (JsonNode tn : transformNodes) {
            ObjectNode tc = buildTransformConfig(tn);
            if (tc != null) transforms.add(tc);
        }
        config.set("transforms", transforms);

        // 生成输出配置
        if (sinkNode != null) {
            config.set("sink", buildSinkConfig(sinkNode, sinkTableName));
        }

        return mapper.writeValueAsString(config);
    }

    // ==================== 数据源配置 ====================

    private ObjectNode buildSourceConfig(JsonNode node, String overrideTable) {
        String kind = node.path("data").path("kind").asText();
        String nodeConfig = node.path("data").path("config").asText("");
        ObjectNode source = mapper.createObjectNode();

        return switch (kind) {
            case "mysql" -> {
                if (!extractValue(nodeConfig, "connectionId", "").isBlank()) {
                    yield buildJdbcSource(nodeConfig, overrideTable);
                }
                source.put("type", "mysql");
                source.put("url", extractJdbcUrl(nodeConfig));
                source.put("username", extractJdbcUser(nodeConfig));
                source.put("password", extractJdbcPassword(nodeConfig));
                source.put("table", overrideTable != null ? overrideTable : extractValue(nodeConfig, "table"));
                yield source;
            }
            case "jdbc", "oceanbase", "postgresql", "oracle" -> buildJdbcSource(nodeConfig, overrideTable);
            case "kafka" -> {
                source.put("type", "kafka");
                source.put("bootstrapServers", extractValue(nodeConfig, "bootstrapServers", kafkaServers));
                source.put("topic", extractValue(nodeConfig, "topic", "a22-transactions"));
                source.put("groupId", extractValue(nodeConfig, "groupId", "a22-flink"));
                yield source;
            }
            // 文件类源（csv/json/excel）→ 走 MySQL 表（文件已导入）
            case "csv", "json-file", "excel", "xml" -> {
                source.put("type", "mysql");
                source.put("url", jdbcUrl);
                source.put("username", jdbcUser);
                source.put("password", jdbcPassword);
                source.put("table", overrideTable != null ? overrideTable : extractValue(nodeConfig, "table"));
                yield source;
            }
            default -> {
                source.put("type", "mysql");
                source.put("url", jdbcUrl);
                source.put("username", jdbcUser);
                source.put("password", jdbcPassword);
                source.put("table", overrideTable != null ? overrideTable : "unknown_source");
                yield source;
            }
        };
    }

    private ObjectNode buildJdbcSource(String nodeConfig, String overrideTable) {
        ObjectNode source = mapper.createObjectNode();
        String connectionId = extractValue(nodeConfig, "connectionId", "");
        String url;
        String username;
        String password;
        String driverClass;
        String dialect;
        String schemaName;
        String dbType;
        if (!connectionId.isBlank()) {
            long id;
            try {
                id = Long.parseLong(connectionId);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("数据库数据源 connectionId 格式错误", exception);
            }
            JdbcConnectionSettings settings = outputDataSourceService.connectionForExecution(id);
            url = settings.url();
            username = settings.username();
            password = settings.password();
            driverClass = settings.driverClass();
            dialect = settings.dialect();
            schemaName = settings.schemaName();
            dbType = settings.dbType();
        } else {
            url = extractValue(nodeConfig, "url", "");
            username = extractValue(nodeConfig, "username", "");
            password = extractValue(nodeConfig, "password", "");
            driverClass = extractValue(nodeConfig, "driverClass", extractValue(nodeConfig, "driver", ""));
            dialect = extractValue(nodeConfig, "dialect", "");
            schemaName = extractValue(nodeConfig, "schemaName", "");
            dbType = extractValue(nodeConfig, "dbType", "");
            if (url.isBlank()) throw new IllegalArgumentException("JDBC 输入缺少 connectionId 或 url");
        }
        String table = overrideTable != null
                ? overrideTable
                : extractValue(nodeConfig, "table", extractValue(nodeConfig, "sourceTable", ""));
        if (table.isBlank()) throw new IllegalArgumentException("JDBC 输入需要配置 table");
        int limit;
        try {
            limit = Integer.parseInt(extractValue(nodeConfig, "limit", "10000"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("JDBC 输入 limit 必须是数字", exception);
        }
        source.put("type", "jdbc");
        source.put("url", url);
        source.put("username", username);
        source.put("password", password);
        source.put("driver", driverClass);
        source.put("dialect", JdbcDialect.resolve(dbType, url, dialect).key());
        source.put("schemaName", schemaName);
        source.put("table", table);
        source.put("limit", Math.max(1, Math.min(limit, 100_000)));
        source.put("preserveAllColumns", true);
        return source;
    }

    // ==================== 变换配置 ====================

    private ObjectNode buildTransformConfig(JsonNode node) {
        String kind = node.path("data").path("kind").asText();
        String nodeConfig = node.path("data").path("config").asText("");
        ObjectNode tc = mapper.createObjectNode();

        return switch (kind) {
            case "concat" -> {
                tc.put("type", "concat");
                JsonNode parsed = safeParse(nodeConfig);
                if (parsed != null && parsed.has("fields")) {
                    tc.set("fields", parsed.get("fields"));
                    tc.put("target", parsed.path("target").asText("concat_result"));
                    tc.put("separator", parsed.path("separator").asText(""));
                } else {
                    // 默认：拼接前两个字段
                    ArrayNode fields = mapper.createArrayNode();
                    fields.add("A");
                    fields.add("B");
                    tc.set("fields", fields);
                    tc.put("target", "C");
                    tc.put("separator", "");
                }
                yield tc;
            }
            case "filter" -> {
                tc.put("type", "filter");
                JsonNode parsed = safeParse(nodeConfig);
                if (parsed != null) {
                    tc.put("field", parsed.path("field").asText("status"));
                    tc.put("op", parsed.path("op").asText("="));
                    tc.put("value", parsed.path("value").asText(""));
                } else {
                    // 解析 "key=value" 格式
                    String[] kv = nodeConfig.split("=", 2);
                    tc.put("field", kv.length > 0 ? kv[0].trim() : "status");
                    tc.put("op", "=");
                    tc.put("value", kv.length > 1 ? kv[1].trim() : "");
                }
                yield tc;
            }
            case "field-map" -> {
                tc.put("type", "fieldMap");
                JsonNode parsed = safeParse(nodeConfig);
                if (parsed != null && parsed.has("mappings")) {
                    tc.set("mappings", parsed.get("mappings"));
                } else {
                    // 解析 "old1:new1,old2:new2" 格式
                    ObjectNode mappings = mapper.createObjectNode();
                    for (String part : nodeConfig.split("[,;]")) {
                        String[] kv = part.trim().split("[:=]", 2);
                        if (kv.length == 2) mappings.put(kv[0].trim(), kv[1].trim());
                    }
                    tc.set("mappings", mappings);
                }
                yield tc;
            }
            case "dedup" -> {
                tc.put("type", "dedup");
                JsonNode parsed = safeParse(nodeConfig);
                tc.put("field", parsed != null ? parsed.path("field").asText("id") : "id");
                yield tc;
            }
            case "type-convert" -> {
                tc.put("type", "typeConvert");
                JsonNode parsed = safeParse(nodeConfig);
                if (parsed != null) {
                    tc.put("field", parsed.path("field").asText(""));
                    tc.put("target", parsed.path("target").asText(parsed.path("field").asText("")));
                    tc.put("targetType", parsed.path("targetType").asText("STRING"));
                }
                yield tc;
            }
            case "xml-json" -> {
                // XML/JSON 互转暂不支持在 Flink 管道中直接处理
                // 跳过此变换，后续可通过 UDF 扩展
                yield null;
            }
            default -> null;
        };
    }

    // ==================== 输出配置 ====================

    private ObjectNode buildSinkConfig(JsonNode node, String overrideTable) {
        String kind = node.path("data").path("kind").asText();
        String nodeConfig = node.path("data").path("config").asText("");
        ObjectNode sink = mapper.createObjectNode();

        return switch (kind) {
            case "mysql" -> {
                if (extractValue(nodeConfig, "connectionId", "").isBlank()) {
                    sink.put("type", "mysql");
                    sink.put("url", extractJdbcUrl(nodeConfig));
                    sink.put("username", extractJdbcUser(nodeConfig));
                    sink.put("password", extractJdbcPassword(nodeConfig));
                    sink.put("driver", "com.mysql.cj.jdbc.Driver");
                    sink.put("dialect", "mysql");
                    sink.put("table", overrideTable != null ? overrideTable
                            : extractValue(nodeConfig, "targetTable", extractValue(nodeConfig, "table", "task_output")));
                    applyWriteOptions(sink, nodeConfig);
                    yield sink;
                }
                yield buildJdbcSink(nodeConfig, overrideTable);
            }
            case "jdbc", "oceanbase", "postgresql", "oracle" -> buildJdbcSink(nodeConfig, overrideTable);
            case "kafka" -> {
                sink.put("type", "kafka");
                sink.put("bootstrapServers", extractValue(nodeConfig, "bootstrapServers", kafkaServers));
                sink.put("topic", extractValue(nodeConfig, "topic", "a22-output"));
                yield sink;
            }
            // 文件类输出 → 走 MySQL 表（后续可导出为文件）
            case "csv", "excel" -> {
                sink.put("type", "mysql");
                sink.put("url", jdbcUrl);
                sink.put("username", jdbcUser);
                sink.put("password", jdbcPassword);
                sink.put("table", overrideTable != null ? overrideTable
                        : extractValue(nodeConfig, "targetTable", extractValue(nodeConfig, "table", "task_output")));
                yield sink;
            }
            case "redis" -> {
                // Redis 输出暂不支持 Flink 管道，回退到 MySQL
                sink.put("type", "mysql");
                sink.put("url", jdbcUrl);
                sink.put("username", jdbcUser);
                sink.put("password", jdbcPassword);
                sink.put("table", overrideTable != null ? overrideTable : "task_output");
                yield sink;
            }
            default -> {
                sink.put("type", "mysql");
                sink.put("url", jdbcUrl);
                sink.put("username", jdbcUser);
                sink.put("password", jdbcPassword);
                sink.put("table", overrideTable != null ? overrideTable : "task_output");
                yield sink;
            }
        };
    }

    private ObjectNode buildJdbcSink(String nodeConfig, String overrideTable) {
        ObjectNode sink = mapper.createObjectNode();
        String connectionId = extractValue(nodeConfig, "connectionId", "");
        String url;
        String username;
        String password;
        String driverClass;
        String dialect;
        String schemaName;
        String dbType;
        if (!connectionId.isBlank()) {
            long id;
            try {
                id = Long.parseLong(connectionId);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("数据库数据源 connectionId 格式错误", exception);
            }
            JdbcConnectionSettings settings = outputDataSourceService.connectionForExecution(id);
            url = settings.url();
            username = settings.username();
            password = settings.password();
            driverClass = settings.driverClass();
            dialect = settings.dialect();
            schemaName = settings.schemaName();
            dbType = settings.dbType();
        } else {
            url = extractValue(nodeConfig, "url", "");
            username = extractValue(nodeConfig, "username", "");
            password = extractValue(nodeConfig, "password", "");
            driverClass = extractValue(nodeConfig, "driverClass", "");
            dialect = extractValue(nodeConfig, "dialect", "");
            schemaName = extractValue(nodeConfig, "schemaName", "");
            dbType = extractValue(nodeConfig, "dbType", "");
            if (url.isBlank()) throw new IllegalArgumentException("JDBC 输出缺少 connectionId 或 url");
        }
        sink.put("type", "jdbc");
        sink.put("url", url);
        sink.put("username", username);
        sink.put("password", password);
        sink.put("driver", driverClass);
        sink.put("dialect", JdbcDialect.resolve(dbType, url, dialect).key());
        sink.put("schemaName", schemaName);
        sink.put("table", overrideTable != null ? overrideTable
                : extractValue(nodeConfig, "targetTable", extractValue(nodeConfig, "table", "task_output")));
        applyWriteOptions(sink, nodeConfig);
        return sink;
    }

    private void applyWriteOptions(ObjectNode sink, String nodeConfig) {
        sink.put("writeMode", extractValue(nodeConfig, "writeMode", "append"));
        sink.put("keyFields", extractValue(nodeConfig, "keyFields", ""));
        sink.put("fieldMapping", extractValue(nodeConfig, "fieldMapping", ""));
        sink.put("autoCreate", extractValue(nodeConfig, "autoCreate", "false"));
        sink.put("batchSize", extractValue(nodeConfig, "batchSize", "500"));
    }

    // ==================== 拓扑排序 ====================

    private List<JsonNode> topologicalSort(JsonNode graph) {
        JsonNode nodes = graph.get("nodes");
        JsonNode edges = graph.get("edges");

        // 构建 id → node 映射
        Map<String, JsonNode> nodeMap = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            nodeMap.put(node.get("id").asText(), node);
        }

        // 构建邻接表和入度
        Map<String, List<String>> adj = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String id : nodeMap.keySet()) {
            adj.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }
        if (edges != null) {
            for (JsonNode edge : edges) {
                String src = edge.get("source").asText();
                String tgt = edge.get("target").asText();
                adj.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
                inDegree.merge(tgt, 1, Integer::sum);
            }
        }

        // Kahn 算法
        Queue<String> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<JsonNode> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            JsonNode node = nodeMap.get(id);
            if (node != null) sorted.add(node);
            for (String next : adj.getOrDefault(id, List.of())) {
                int deg = inDegree.merge(next, -1, Integer::sum);
                if (deg == 0) queue.add(next);
            }
        }

        return sorted;
    }

    // ==================== 工具方法 ====================

    private String extractJdbcUrl(String config) {
        return extractValue(config, "url", jdbcUrl);
    }

    private String extractJdbcUser(String config) {
        return extractValue(config, "username", jdbcUser);
    }

    private String extractJdbcPassword(String config) {
        return extractValue(config, "password", jdbcPassword);
    }

    private String extractValue(String config, String key) {
        return extractValue(config, key, "");
    }

    private String extractValue(String config, String key, String defaultValue) {
        if (config == null || config.isBlank()) return defaultValue;
        JsonNode parsed = safeParse(config);
        if (parsed != null && parsed.has(key)) return parsed.get(key).asText(defaultValue);
        // 回退：尝试 "key=value" 格式
        for (String part : config.split("[,;]")) {
            String[] kv = part.trim().split("[:=]", 2);
            if (kv.length == 2 && kv[0].trim().equals(key)) return kv[1].trim();
        }
        return defaultValue;
    }

    private JsonNode safeParse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = mapper.readTree(json);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}

