package com.a22.platform.engine;

import com.a22.platform.datasource.JdbcConnectionSettings;
import com.a22.platform.datasource.JdbcDialect;
import com.a22.platform.datasource.OutputDataSourceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class JdbcDataSourceService {
    private static final int MAX_LIMIT = 100_000;
    private static final int DEFAULT_BATCH_SIZE = 500;

    private final OutputDataSourceService outputDataSourceService;
    private final ObjectMapper objectMapper;

    public JdbcDataSourceService(OutputDataSourceService outputDataSourceService, ObjectMapper objectMapper) {
        this.outputDataSourceService = outputDataSourceService;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> read(Map<String, ?> config) {
        ConnectionSettings settings = resolveSettings(config);
        String table = require(config, "table", "JDBC 输入需要配置 table");
        int limit = Math.min(parseInt(get(config, "limit", "10000"), 10000), MAX_LIMIT);
        String sql = settings.dialect().selectSql(table, limit);
        try (Connection connection = open(settings);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return readRows(resultSet);
        } catch (Exception exception) {
            throw new RuntimeException("JDBC 读取失败：" + exception.getMessage(), exception);
        }
    }

    public int write(Map<String, ?> config, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return 0;
        ConnectionSettings settings = resolveSettings(config);
        String table = require(config, "table", "JDBC 输出需要配置 table");
        Map<String, String> fieldMapping = parseFieldMapping(config.get("fieldMapping"));
        List<Map<String, Object>> projected = projectRecords(records, fieldMapping);
        List<String> columns = new ArrayList<>(projected.get(0).keySet());
        if (columns.isEmpty()) throw new IllegalArgumentException("JDBC 输出字段不能为空");

        String writeMode = get(config, "writeMode", "append").trim().toLowerCase(Locale.ROOT);
        List<String> keyFields = parseKeyFields(config.get("keyFields"));
        boolean autoCreate = Boolean.parseBoolean(get(config, "autoCreate", "false"));
        int batchSize = Math.max(1, Math.min(parseInt(get(config, "batchSize", String.valueOf(DEFAULT_BATCH_SIZE)), DEFAULT_BATCH_SIZE), 5000));

        try (Connection connection = open(settings)) {
            if (autoCreate) ensureTable(connection, settings, table, columns, projected.get(0));
            if ("upsert".equals(writeMode)) {
                if (keyFields.isEmpty()) throw new IllegalArgumentException("UPSERT 模式必须配置 keyFields");
                if (!columns.containsAll(keyFields)) throw new IllegalArgumentException("keyFields 必须存在于输出字段中");
                return writeUpsert(connection, settings, table, columns, keyFields, projected, batchSize);
            }
            return writeInsert(connection, settings, table, columns, projected, batchSize);
        } catch (Exception exception) {
            throw new RuntimeException("JDBC 写入失败：" + exception.getMessage(), exception);
        }
    }

    private int writeInsert(
            Connection connection,
            ConnectionSettings settings,
            String table,
            List<String> columns,
            List<Map<String, Object>> records,
            int batchSize) throws SQLException {
        String sql = settings.dialect().insertSql(table, columns);
        int total = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : records) {
                bindColumns(statement, columns, row, 1);
                statement.addBatch();
                if (++total % batchSize == 0) executeBatch(statement);
            }
            executeBatch(statement);
        }
        return total;
    }

    private int writeUpsert(
            Connection connection,
            ConnectionSettings settings,
            String table,
            List<String> columns,
            List<String> keyFields,
            List<Map<String, Object>> records,
            int batchSize) throws SQLException {
        Optional<String> nativeSql = settings.dialect().nativeUpsertSql(table, columns, keyFields);
        if (nativeSql.isPresent()) {
            int total = 0;
            try (PreparedStatement statement = connection.prepareStatement(nativeSql.get())) {
                for (Map<String, Object> row : records) {
                    bindColumns(statement, columns, row, 1);
                    statement.addBatch();
                    if (++total % batchSize == 0) executeBatch(statement);
                }
                executeBatch(statement);
            }
            return total;
        }
        return writeUpdateThenInsert(connection, settings, table, columns, keyFields, records);
    }

    private int writeUpdateThenInsert(
            Connection connection,
            ConnectionSettings settings,
            String table,
            List<String> columns,
            List<String> keyFields,
            List<Map<String, Object>> records) throws SQLException {
        String updateSql = settings.dialect().updateSql(table, columns, keyFields);
        String insertSql = settings.dialect().insertSql(table, columns);
        List<String> updatable = columns.stream().filter(column -> !keyFields.contains(column)).toList();
        int inserted = 0;
        int updated = 0;
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement update = connection.prepareStatement(updateSql);
                 PreparedStatement insert = connection.prepareStatement(insertSql)) {
                for (Map<String, Object> row : records) {
                    int index = 1;
                    for (String column : updatable) {
                        update.setObject(index++, row.get(column));
                    }
                    for (String key : keyFields) {
                        update.setObject(index++, row.get(key));
                    }
                    if (update.executeUpdate() > 0) {
                        updated += 1;
                    } else {
                        bindColumns(insert, columns, row, 1);
                        insert.addBatch();
                        inserted += 1;
                    }
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
        return updated + inserted;
    }

    private void executeBatch(PreparedStatement statement) throws SQLException {
        statement.executeBatch();
    }

    private void bindColumns(PreparedStatement statement, List<String> columns, Map<String, Object> row, int offset) throws SQLException {
        for (int index = 0; index < columns.size(); index += 1) {
            statement.setObject(offset + index, row.get(columns.get(index)));
        }
    }

    private void ensureTable(
            Connection connection,
            ConnectionSettings settings,
            String table,
            List<String> columns,
            Map<String, Object> sample) throws SQLException {
        if (tableExists(connection, settings, table)) return;
        if (!settings.dialect().supportsCreateTableIfNotExists()) {
            throw new IllegalArgumentException("Oracle 首次输出建议先创建目标表，或关闭“自动创建表”");
        }
        String definitions = columns.stream()
                .map(column -> settings.dialect().quote(column) + " " + inferSqlType(settings.dialect(), sample.get(column)))
                .collect(Collectors.joining(","));
        String identity = switch (settings.dialect()) {
            case POSTGRESQL -> "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,";
            case SQLSERVER -> "id BIGINT IDENTITY(1,1) PRIMARY KEY,";
            default -> "id BIGINT AUTO_INCREMENT PRIMARY KEY,";
        };
        String timestamp = switch (settings.dialect()) {
            case SQLSERVER -> ", _created_at DATETIME2 DEFAULT SYSUTCDATETIME()";
            default -> ", _created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
        };
        String sql = "CREATE TABLE IF NOT EXISTS " + settings.dialect().quote(table)
                + " (" + identity + definitions + timestamp + ")";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private boolean tableExists(Connection connection, ConnectionSettings settings, String table) throws SQLException {
        String schema = null;
        String tableName = table;
        int dot = table.lastIndexOf('.');
        if (dot >= 0) {
            schema = table.substring(0, dot);
            tableName = table.substring(dot + 1);
        }
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), schema, tableName, new String[]{"TABLE", "VIEW"})) {
            return tables.next();
        }
    }

    private String inferSqlType(JdbcDialect dialect, Object value) {
        if (value instanceof Boolean) return dialect == JdbcDialect.SQLSERVER ? "BIT" : "BOOLEAN";
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return dialect == JdbcDialect.ORACLE ? "NUMBER(19)" : "BIGINT";
        }
        if (value instanceof Float || value instanceof Double || value instanceof Number) {
            return dialect == JdbcDialect.ORACLE ? "NUMBER(38,10)" : "DECIMAL(38,10)";
        }
        if (value instanceof java.time.temporal.Temporal || value instanceof java.util.Date) {
            return dialect == JdbcDialect.SQLSERVER ? "DATETIME2" : "TIMESTAMP";
        }
        return dialect == JdbcDialect.ORACLE ? "VARCHAR2(4000)" : "VARCHAR(4000)";
    }

    private List<Map<String, Object>> projectRecords(List<Map<String, Object>> records, Map<String, String> fieldMapping) {
        if (fieldMapping.isEmpty()) return records;
        List<Map<String, Object>> projected = new ArrayList<>(records.size());
        for (Map<String, Object> row : records) {
            Map<String, Object> mapped = new LinkedHashMap<>(row);
            for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
                if (row.containsKey(entry.getKey())) {
                    String target = entry.getValue() == null || entry.getValue().isBlank()
                            ? entry.getKey()
                            : entry.getValue();
                    if (!target.equals(entry.getKey())) mapped.remove(entry.getKey());
                    mapped.put(target, row.get(entry.getKey()));
                }
            }
            if (row.keySet().stream().noneMatch(fieldMapping::containsKey)) {
                throw new IllegalArgumentException("字段映射未匹配到任何输入字段");
            }
            projected.add(mapped);
        }
        return projected;
    }

    private Map<String, String> parseFieldMapping(Object raw) {
        if (raw == null) return Map.of();
        try {
            Object value = raw;
            if (raw instanceof String text && !text.isBlank()) value = objectMapper.readValue(text, Map.class);
            if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("fieldMapping 必须是对象");
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((key, target) -> result.put(String.valueOf(key), target == null ? "" : String.valueOf(target)));
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("fieldMapping 不是合法 JSON", exception);
        }
    }

    private List<String> parseKeyFields(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).filter(value -> !value.isBlank()).map(String::trim).toList();
        }
        String text = String.valueOf(raw).trim();
        if (text.startsWith("[")) {
            try {
                return objectMapper.readValue(text, new TypeReference<List<String>>() { });
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalArgumentException("keyFields 不是合法 JSON 数组", exception);
            }
        }
        return Arrays.stream(text.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private ConnectionSettings resolveSettings(Map<String, ?> config) {
        String connectionId = get(config, "connectionId", "");
        JdbcConnectionSettings settings;
        if (!connectionId.isBlank()) {
            long id;
            try {
                id = Long.parseLong(connectionId);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("connectionId 格式错误", exception);
            }
            settings = outputDataSourceService.connectionForExecution(id);
        } else {
            String url = require(config, "url", "JDBC 连接需要配置 url 或 connectionId");
            settings = new JdbcConnectionSettings(
                    null,
                    get(config, "dbType", ""),
                    get(config, "driverClass", ""),
                    url,
                    get(config, "username", ""),
                    get(config, "password", ""),
                    get(config, "dialect", ""),
                    get(config, "schemaName", ""));
        }
        JdbcDialect dialect = JdbcDialect.resolve(settings.dbType(), settings.url(), settings.dialect());
        return new ConnectionSettings(settings, dialect);
    }

    private Connection open(ConnectionSettings settings) throws SQLException {
        DriverManager.setLoginTimeout(8);
        String driverClass = settings.settings().driverClass();
        if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException exception) {
                throw new SQLException("未找到 JDBC 驱动类：" + driverClass, exception);
            }
        }
        return DriverManager.getConnection(
                settings.settings().url(),
                settings.settings().username(),
                settings.settings().password());
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metadata = resultSet.getMetaData();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= metadata.getColumnCount(); column += 1) {
                row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
            }
            rows.add(row);
        }
        return rows;
    }

    private String require(Map<String, ?> config, String key, String message) {
        String value = get(config, key, "");
        if (value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private String get(Map<String, ?> config, String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record ConnectionSettings(JdbcConnectionSettings settings, JdbcDialect dialect) { }
}
