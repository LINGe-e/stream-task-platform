package com.a22.flink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.*;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.util.Collector;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 通用动态管道作业。
 * 通过 --config 参数传入 base64 编码的管道配置 JSON，动态构建 source → transform → sink 管道。
 *
 * 管道配置格式：
 * {
 *   "jobName": "a22-task-1",
 *   "source": {"type": "kafka"|"mysql"|"jdbc", ...},
 *   "transforms": [{"type": "concat"|"filter"|"fieldMap"|"typeConvert"|"dedup", ...}],
 *   "sink": {"type": "kafka"|"mysql", ...}
 * }
 *
 * 所有数据以 JSON 字符串流转，变换函数内部解析和序列化。
 */
public class DynamicPipelineJob {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        ParameterTool params = ParameterTool.fromArgs(args);
        String configBase64 = params.getRequired("config");
        String configJson = new String(Base64.getDecoder().decode(configBase64), StandardCharsets.UTF_8);
        JsonNode config = MAPPER.readTree(configJson);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(10000);

        // 1. 创建数据源
        DataStream<String> source = createSource(env, config.get("source"));

        // 2. 按顺序应用变换链
        DataStream<String> processed = source;
        JsonNode transforms = config.get("transforms");
        if (transforms != null && transforms.isArray()) {
            for (JsonNode transform : transforms) {
                processed = applyTransform(processed, transform);
            }
        }

        // 3. 创建输出
        createSink(processed, config.get("sink"));

        String jobName = config.path("jobName").asText("a22-dynamic-pipeline");
        env.execute(jobName);
    }

    // ==================== 数据源 ====================

    private static DataStream<String> createSource(StreamExecutionEnvironment env, JsonNode cfg) {
        String type = cfg.get("type").asText();
        return switch (type) {
            case "kafka" -> {
                KafkaSource<String> ks = KafkaSource.<String>builder()
                        .setBootstrapServers(cfg.get("bootstrapServers").asText())
                        .setTopics(cfg.get("topic").asText())
                        .setGroupId(cfg.path("groupId").asText("a22-flink"))
                        .setStartingOffsets(OffsetsInitializer.earliest())
                        .setValueOnlyDeserializer(new SimpleStringSchema())
                        .build();
                yield env.fromSource(ks, WatermarkStrategy.noWatermarks(), "Kafka Source");
            }
            case "mysql", "jdbc" -> env.addSource(new JdbcJsonSource(cfg))
                    .name("JDBC Source: " + cfg.path("table").asText());
            default -> throw new IllegalArgumentException("不支持的数据源类型: " + type);
        };
    }

    // ==================== 变换 ====================

    private static DataStream<String> applyTransform(DataStream<String> input, JsonNode cfg) {
        String kind = cfg.get("type").asText();
        String cfgStr = cfg.toString();
        return switch (kind) {
            case "concat" -> input.map(new ConcatMapper(cfgStr)).name("Concat");
            case "filter" -> input.filter(new TransformFilter(cfgStr)).name("Filter");
            case "fieldMap" -> input.map(new FieldMapMapper(cfgStr)).name("FieldMap");
            case "typeConvert" -> input.map(new TypeConvertMapper(cfgStr)).name("TypeConvert");
            case "dedup" -> {
                KeyedStream<String, String> keyed = input.keyBy(new DedupKeySelector(cfgStr));
                yield keyed.flatMap(new DedupFlatMap(cfgStr)).name("Dedup");
            }
            default -> throw new IllegalArgumentException("不支持的变换类型: " + kind);
        };
    }

    // ==================== 输出 ====================

    private static void createSink(DataStream<String> input, JsonNode cfg) {
        String type = cfg.get("type").asText();
        switch (type) {
            case "kafka" -> {
                KafkaSink<String> ks = KafkaSink.<String>builder()
                        .setBootstrapServers(cfg.get("bootstrapServers").asText())
                        .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                                .setTopic(cfg.get("topic").asText("a22-output"))
                                .setValueSerializationSchema(new SimpleStringSchema())
                                .build())
                        .build();
                input.sinkTo(ks).name("Kafka Sink");
            }
            case "mysql", "jdbc" -> input.addSink(new JdbcJsonSink(cfg))
                    .name("JDBC Sink: " + cfg.path("table").asText());
            default -> throw new IllegalArgumentException("不支持的输出类型: " + type);
        }
    }

    // ==================== JDBC 数据源 ====================

    static class JdbcJsonSource extends RichSourceFunction<String> {
        private final String url;
        private final String user;
        private final String password;
        private final String table;
        private final String schemaName;
        private final String driverClass;
        private final String dialect;
        private final int limit;
        private final boolean preserveAllColumns;
        private volatile boolean running = true;

        JdbcJsonSource(JsonNode configuration) {
            this.url = configuration.path("url").asText();
            this.user = configuration.path("username").asText("");
            this.password = configuration.path("password").asText("");
            this.table = configuration.path("table").asText();
            this.schemaName = configuration.path("schemaName").asText("");
            this.driverClass = configuration.path("driver").asText("");
            this.dialect = configuration.path("dialect").asText("mysql").toLowerCase(Locale.ROOT);
            this.limit = Math.max(1, Math.min(configuration.path("limit").asInt(10_000), 100_000));
            this.preserveAllColumns = configuration.path("preserveAllColumns").asBoolean(false);
        }

        @Override
        public void run(SourceContext<String> ctx) throws Exception {
            String resolvedDriver = resolveDriverClass();
            if (!resolvedDriver.isBlank()) Class.forName(resolvedDriver);
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 Statement stmt = conn.createStatement()) {
                stmt.setMaxRows(limit);
                try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + quote(qualifiedTable()))) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    while (running && rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            String label = meta.getColumnLabel(i);
                            // 平台内部 MySQL 表继续跳过系统字段，用户数据库保留全部业务字段
                            if (!preserveAllColumns && ("id".equals(label) || label.startsWith("_"))) continue;
                            row.put(label, rs.getObject(i));
                        }
                        ctx.collect(MAPPER.writeValueAsString(row));
                    }
                }
            }
        }

        private String resolveDriverClass() {
            if (!driverClass.isBlank()) return driverClass;
            if (url.startsWith("jdbc:postgresql:")) return "org.postgresql.Driver";
            if (url.startsWith("jdbc:oracle:")) return "oracle.jdbc.OracleDriver";
            if (url.startsWith("jdbc:sqlserver:")) return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            if (url.startsWith("jdbc:mariadb:")) return "org.mariadb.jdbc.Driver";
            return switch (dialect) {
                case "postgresql" -> "org.postgresql.Driver";
                case "oracle" -> "oracle.jdbc.OracleDriver";
                case "sqlserver" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
                default -> "com.mysql.cj.jdbc.Driver";
            };
        }

        private String quote(String identifier) {
            String[] parts = identifier.split("\\.", -1);
            return Arrays.stream(parts).map(part -> switch (dialect) {
                case "mysql" -> "`" + part.replace("`", "``") + "`";
                case "sqlserver" -> "[" + part.replace("]", "]]") + "]";
                default -> "\"" + part.replace("\"", "\"\"") + "\"";
            }).collect(Collectors.joining("."));
        }

        private String qualifiedTable() {
            if (schemaName.isBlank() || table.contains(".")) return table;
            return schemaName + "." + table;
        }

        @Override
        public void cancel() { running = false; }
    }

    // ==================== JDBC 输出 ====================

    static class JdbcJsonSink extends RichSinkFunction<String> {
        private final String url;
        private final String user;
        private final String password;
        private final String table;
        private final String driverClass;
        private final String dialect;
        private final String writeMode;
        private final String keyFieldsJson;
        private final String fieldMappingJson;
        private final boolean autoCreate;
        private final int batchSize;
        private transient Connection conn;
        private transient PreparedStatement insertStatement;
        private transient PreparedStatement upsertStatement;
        private transient PreparedStatement updateStatement;
        private transient List<String> columns;
        private transient List<String> keyFields;
        private transient int batchCount;

        JdbcJsonSink(JsonNode cfg) {
            this.url = cfg.path("url").asText();
            this.user = cfg.path("username").asText("");
            this.password = cfg.path("password").asText("");
            this.table = cfg.path("table").asText();
            this.driverClass = cfg.path("driver").asText("");
            this.dialect = cfg.path("dialect").asText("mysql").toLowerCase(Locale.ROOT);
            this.writeMode = cfg.path("writeMode").asText("append").toLowerCase(Locale.ROOT);
            this.keyFieldsJson = cfg.path("keyFields").asText("");
            this.fieldMappingJson = cfg.path("fieldMapping").asText("");
            this.autoCreate = cfg.path("autoCreate").asBoolean(false);
            this.batchSize = Math.max(1, cfg.path("batchSize").asInt(500));
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            if (!driverClass.isBlank()) Class.forName(driverClass);
            conn = DriverManager.getConnection(url, user, password);
            batchCount = 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void invoke(String value, Context context) throws Exception {
            Map<String, Object> row = MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
            row = mapFields(row);
            if (columns == null) {
                columns = new ArrayList<>(row.keySet());
                keyFields = parseKeyFields();
                if ("upsert".equals(writeMode) && keyFields.isEmpty()) {
                    throw new IllegalArgumentException("UPSERT 输出缺少 keyFields");
                }
                if (autoCreate) ensureTable(columns, row);
                insertStatement = conn.prepareStatement(insertSql(columns));
                Optional<String> nativeUpsert = nativeUpsertSql(columns, keyFields);
                if (nativeUpsert.isPresent()) {
                    upsertStatement = conn.prepareStatement(nativeUpsert.get());
                } else if ("upsert".equals(writeMode)) {
                    updateStatement = conn.prepareStatement(updateSql(columns, keyFields));
                }
            }
            if ("upsert".equals(writeMode) && upsertStatement == null) {
                writeUpdateThenInsert(row);
                return;
            }
            PreparedStatement statement = "upsert".equals(writeMode) ? upsertStatement : insertStatement;
            bind(statement, row);
            statement.addBatch();
            if (++batchCount >= batchSize) {
                statement.executeBatch();
                batchCount = 0;
            }
        }

        private Map<String, Object> mapFields(Map<String, Object> row) {
            if (fieldMappingJson == null || fieldMappingJson.isBlank()) return row;
            try {
                JsonNode mapping = MAPPER.readTree(fieldMappingJson);
                if (!mapping.isObject()) throw new IllegalArgumentException("fieldMapping 必须是对象");
                Map<String, Object> mapped = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = mapping.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (row.containsKey(field.getKey())) {
                        String target = field.getValue().asText("");
                        mapped.put(target.isBlank() ? field.getKey() : target, row.get(field.getKey()));
                    }
                }
                if (mapped.isEmpty()) throw new IllegalArgumentException("字段映射未匹配到输入字段");
                return mapped;
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalArgumentException("fieldMapping 不是合法 JSON", exception);
            }
        }

        private List<String> parseKeyFields() {
            if (keyFieldsJson == null || keyFieldsJson.isBlank()) return List.of();
            String text = keyFieldsJson.trim();
            if (text.startsWith("[")) {
                try {
                    JsonNode array = MAPPER.readTree(text);
                    List<String> values = new ArrayList<>();
                    array.forEach(item -> values.add(item.asText()));
                    return values;
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new IllegalArgumentException("keyFields 不是合法 JSON 数组", exception);
                }
            }
            return Arrays.stream(text.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
        }

        private void bind(PreparedStatement statement, Map<String, Object> row) throws SQLException {
            for (int index = 0; index < columns.size(); index += 1) {
                statement.setObject(index + 1, row.get(columns.get(index)));
            }
        }

        private void writeUpdateThenInsert(Map<String, Object> row) throws SQLException {
            List<String> updatable = columns.stream().filter(column -> !keyFields.contains(column)).toList();
            int index = 1;
            for (String column : updatable) updateStatement.setObject(index++, row.get(column));
            for (String key : keyFields) updateStatement.setObject(index++, row.get(key));
            if (updateStatement.executeUpdate() == 0) {
                bind(insertStatement, row);
                insertStatement.executeUpdate();
            }
        }

        private String insertSql(List<String> columns) {
            return "INSERT INTO " + quote(table)
                    + " (" + columns.stream().map(this::quote).collect(Collectors.joining(","))
                    + ") VALUES (" + columns.stream().map(column -> "?").collect(Collectors.joining(",")) + ")";
        }

        private Optional<String> nativeUpsertSql(List<String> columns, List<String> keys) {
            if (keys.isEmpty()) return Optional.empty();
            String nonKeys = columns.stream().filter(column -> !keys.contains(column)).collect(Collectors.joining(","));
            if (nonKeys.isEmpty()) return Optional.empty();
            return switch (dialect) {
                case "mysql" -> Optional.of(insertSql(columns)
                        + " ON DUPLICATE KEY UPDATE "
                        + columns.stream().filter(column -> !keys.contains(column))
                        .map(column -> quote(column) + "=VALUES(" + quote(column) + ")")
                        .collect(Collectors.joining(",")));
                case "postgresql" -> Optional.of(insertSql(columns)
                        + " ON CONFLICT (" + keys.stream().map(this::quote).collect(Collectors.joining(",")) + ") DO UPDATE SET "
                        + columns.stream().filter(column -> !keys.contains(column))
                        .map(column -> quote(column) + "=EXCLUDED." + quote(column))
                        .collect(Collectors.joining(",")));
                default -> Optional.empty();
            };
        }

        private String updateSql(List<String> columns, List<String> keys) {
            List<String> updatable = columns.stream().filter(column -> !keys.contains(column)).toList();
            if (updatable.isEmpty()) throw new IllegalArgumentException("UPSERT 至少需要一个非键字段");
            return "UPDATE " + quote(table)
                    + " SET " + updatable.stream().map(column -> quote(column) + "=?").collect(Collectors.joining(","))
                    + " WHERE " + keys.stream().map(column -> quote(column) + "=?").collect(Collectors.joining(" AND "));
        }

        private String quote(String identifier) {
            String[] parts = identifier.split("\\.", -1);
            return Arrays.stream(parts).map(part -> switch (dialect) {
                case "mysql" -> "`" + part.replace("`", "``") + "`";
                case "sqlserver" -> "[" + part.replace("]", "]]") + "]";
                default -> "\"" + part.replace("\"", "\"\"") + "\"";
            }).collect(Collectors.joining("."));
        }

        private void ensureTable(List<String> cols, Map<String, Object> sample) throws SQLException {
            if ("oracle".equals(dialect) || "generic".equals(dialect)) {
                throw new IllegalArgumentException("当前方言不支持自动创建表，请先手工创建目标表");
            }
            String definitions = cols.stream()
                    .map(column -> quote(column) + " " + inferType(sample.get(column)))
                    .collect(Collectors.joining(","));
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS " + quote(table) + " ("
                            + identityColumn() + definitions
                            + ", _created_at " + timestampColumn() + ")");
        }

        private String identityColumn() {
            return switch (dialect) {
                case "postgresql" -> "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,";
                case "sqlserver" -> "id BIGINT IDENTITY(1,1) PRIMARY KEY,";
                default -> "id BIGINT AUTO_INCREMENT PRIMARY KEY,";
            };
        }

        private String timestampColumn() {
            return "sqlserver".equals(dialect)
                    ? "DATETIME2 DEFAULT SYSUTCDATETIME()"
                    : "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
        }

        private String inferType(Object value) {
            if (value instanceof Number) {
                if (value instanceof Double || value instanceof Float) return "DOUBLE";
                return "BIGINT";
            }
            String text = String.valueOf(value);
            if (text.matches("-?\\d+")) return "BIGINT";
            if (text.matches("-?\\d+\\.\\d+")) return "DOUBLE";
            return "VARCHAR(500)";
        }

        @Override
        public void close() throws Exception {
            if (batchCount > 0) {
                PreparedStatement statement = "upsert".equals(writeMode) && upsertStatement != null
                        ? upsertStatement
                        : insertStatement;
                if (statement != null) statement.executeBatch();
                batchCount = 0;
            }
            if (insertStatement != null) insertStatement.close();
            if (upsertStatement != null) upsertStatement.close();
            if (updateStatement != null) updateStatement.close();
            if (conn != null) conn.close();
        }
    }

    // ==================== 变换函数 ====================

    /** 字段拼接：将多个字段值拼接为新字段 */
    static class ConcatMapper extends RichMapFunction<String, String> {
        private final String cfgJson;
        private transient List<String> fields;
        private transient String target;
        private transient String separator;

        ConcatMapper(String cfgJson) { this.cfgJson = cfgJson; }

        @Override
        public void open(Configuration parameters) throws Exception {
            JsonNode cfg = MAPPER.readTree(cfgJson);
            fields = new ArrayList<>();
            cfg.get("fields").forEach(f -> fields.add(f.asText()));
            target = cfg.get("target").asText("concat_result");
            separator = cfg.path("separator").asText("");
        }

        @Override
        @SuppressWarnings("unchecked")
        public String map(String value) throws Exception {
            Map<String, Object> row = MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
            String joined = fields.stream()
                    .map(f -> String.valueOf(row.getOrDefault(f, "")))
                    .collect(Collectors.joining(separator));
            row.put(target, joined);
            return MAPPER.writeValueAsString(row);
        }
    }

    /** 条件过滤 */
    static class TransformFilter extends RichFilterFunction<String> {
        private final String cfgJson;
        private transient String field, op, value;

        TransformFilter(String cfgJson) { this.cfgJson = cfgJson; }

        @Override
        public void open(Configuration parameters) throws Exception {
            JsonNode cfg = MAPPER.readTree(cfgJson);
            field = cfg.get("field").asText();
            op = cfg.path("op").asText("=");
            value = cfg.path("value").asText("");
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean filter(String v) throws Exception {
            Map<String, Object> row = MAPPER.readValue(v, new TypeReference<Map<String, Object>>() {});
            Object fieldVal = row.get(field);
            String str = fieldVal == null ? "" : String.valueOf(fieldVal);
            return switch (op) {
                case "=" -> str.equals(value);
                case "!=" -> !str.equals(value);
                case ">" -> str.compareTo(value) > 0;
                case "<" -> str.compareTo(value) < 0;
                case "contains" -> str.contains(value);
                default -> true;
            };
        }
    }

    /** 字段重命名 */
    static class FieldMapMapper extends RichMapFunction<String, String> {
        private final String cfgJson;
        private transient Map<String, String> mapping;

        FieldMapMapper(String cfgJson) { this.cfgJson = cfgJson; }

        @Override
        public void open(Configuration parameters) throws Exception {
            JsonNode cfg = MAPPER.readTree(cfgJson);
            mapping = new LinkedHashMap<>();
            // 支持两种格式：JSON对象 {"old":"new"} 或 逗号分隔 "old1:new1,old2:new2"
            if (cfg.isObject() && cfg.has("mappings")) {
                cfg.get("mappings").fields().forEachRemaining(e -> mapping.put(e.getKey(), e.getValue().asText()));
            } else {
                // 尝试解析为逗号分隔格式
                String raw = cfg.isTextual() ? cfg.asText() : cfgJson;
                for (String part : raw.split("[,;]")) {
                    String[] kv = part.trim().split("[:=]", 2);
                    if (kv.length == 2) mapping.put(kv[0].trim(), kv[1].trim());
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public String map(String value) throws Exception {
            Map<String, Object> row = MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> result = new LinkedHashMap<>();
            row.forEach((k, v) -> result.put(mapping.getOrDefault(k, k), v));
            return MAPPER.writeValueAsString(result);
        }
    }

    /** 类型转换 */
    static class TypeConvertMapper extends RichMapFunction<String, String> {
        private final String cfgJson;
        private transient String field, target, targetType;

        TypeConvertMapper(String cfgJson) { this.cfgJson = cfgJson; }

        @Override
        public void open(Configuration parameters) throws Exception {
            JsonNode cfg = MAPPER.readTree(cfgJson);
            field = cfg.get("field").asText();
            target = cfg.path("target").asText(field);
            targetType = cfg.get("targetType").asText("STRING");
        }

        @Override
        @SuppressWarnings("unchecked")
        public String map(String value) throws Exception {
            Map<String, Object> row = MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> result = new LinkedHashMap<>(row);
            Object val = row.get(field);
            if (val != null) {
                String text = String.valueOf(val).trim();
                result.put(target, switch (targetType.toUpperCase()) {
                    case "STRING" -> text;
                    case "INTEGER", "INT" -> Integer.valueOf(text);
                    case "LONG" -> Long.valueOf(text);
                    case "DOUBLE" -> Double.valueOf(text);
                    case "BOOLEAN" -> "true".equalsIgnoreCase(text) || "1".equals(text);
                    default -> text;
                });
            }
            return MAPPER.writeValueAsString(result);
        }
    }

    /** 去重键选择器 */
    static class DedupKeySelector implements KeySelector<String, String> {
        private final String cfgJson;
        private transient String field;

        DedupKeySelector(String cfgJson) { this.cfgJson = cfgJson; }

        @Override
        public String getKey(String value) throws Exception {
            if (field == null) {
                JsonNode cfg = MAPPER.readTree(cfgJson);
                field = cfg.get("field").asText();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
            Object val = row.get(field);
            return val == null ? "" : String.valueOf(val);
        }
    }

    /** 去重：保留每组第一条记录 */
    static class DedupFlatMap extends RichFlatMapFunction<String, String> {
        private final String cfgJson;
        private transient org.apache.flink.api.common.state.ValueState<Boolean> seen;

        DedupFlatMap(String cfgJson) { this.cfgJson = cfgJson; }

        @Override
        public void open(Configuration parameters) {
            seen = getRuntimeContext().getState(
                    new org.apache.flink.api.common.state.ValueStateDescriptor<>("seen", Boolean.class));
        }

        @Override
        public void flatMap(String value, Collector<String> out) throws Exception {
            if (seen.value() == null) {
                seen.update(true);
                out.collect(value);
            }
        }
    }
}
