package com.a22.platform.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSetMetaData;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DynamicTableService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DynamicTableService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int writeData(String tableName, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return 0;
        List<Map<String, Object>> normalizedRecords = normalizeRecords(records);
        Map<String, String> columns = collectColumnTypes(normalizedRecords);
        ensureTableSchema(tableName, columns);
        return batchInsert(tableName, columns.keySet(), normalizedRecords);
    }

    public List<Map<String, Object>> readData(String tableName, int limit) {
        String safeTableName = requireIdentifier(tableName, "表名");
        if (!tableExists(safeTableName)) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 100_000));
        String sql = "SELECT * FROM `" + safeTableName + "` LIMIT " + safeLimit;
        return jdbc.query(sql, (resultSet, rowNumber) -> {
            ResultSetMetaData metadata = resultSet.getMetaData();
            Map<String, Object> record = new LinkedHashMap<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                record.put(metadata.getColumnLabel(column), resultSet.getObject(column));
            }
            return record;
        });
    }

    public List<Map<String, Object>> readDataByStatus(String tableName, String statusField, String unprocessedValue, int limit) {
        String safeTableName = requireIdentifier(tableName, "表名");
        String safeField = requireIdentifier(statusField, "状态字段");
        int safeLimit = Math.max(1, Math.min(limit, 100_000));
        String sql = "SELECT * FROM `" + safeTableName + "` WHERE `" + safeField + "` = ? ORDER BY id ASC LIMIT " + safeLimit;
        return jdbc.query(sql, (resultSet, rowNumber) -> mapRow(resultSet), unprocessedValue);
    }

    public List<Map<String, Object>> readDataByCursor(String tableName, String cursorField, String lastCursorValue, int limit) {
        String safeTableName = requireIdentifier(tableName, "表名");
        String safeField = requireIdentifier(cursorField, "游标字段");
        int safeLimit = Math.max(1, Math.min(limit, 100_000));
        if (lastCursorValue == null || lastCursorValue.isBlank()) {
            String sql = "SELECT * FROM `" + safeTableName + "` ORDER BY `" + safeField + "` ASC LIMIT " + safeLimit;
            return jdbc.query(sql, (resultSet, rowNumber) -> mapRow(resultSet));
        }
        String sql = "SELECT * FROM `" + safeTableName + "` ORDER BY `" + safeField + "` ASC LIMIT 100000";
        return jdbc.query(sql, (resultSet, rowNumber) -> mapRow(resultSet)).stream()
                .filter(row -> compareCursorValue(row.get(safeField), lastCursorValue) > 0)
                .sorted((left, right) -> compareCursorValue(left.get(safeField), right.get(safeField)))
                .limit(safeLimit)
                .toList();
    }

    public int markProcessedByIds(String tableName, List<Object> ids, String processedField, String processedValue) {
        if (ids == null || ids.isEmpty()) return 0;
        String safeTableName = requireIdentifier(tableName, "表名");
        String safeField = requireIdentifier(processedField, "状态字段");
        int total = 0;
        for (Object id : ids) {
            total += jdbc.update("UPDATE `" + safeTableName + "` SET `" + safeField + "` = ? WHERE id = ?", processedValue, id);
        }
        return total;
    }

    public DedupWriteResult writeDataDedup(String tableName, List<Map<String, Object>> records, List<String> dedupKeys) {
        if (records == null || records.isEmpty()) return new DedupWriteResult(0, 0);
        if (dedupKeys == null || dedupKeys.isEmpty()) {
            return new DedupWriteResult(writeData(tableName, records), 0);
        }
        List<Map<String, Object>> normalizedRecords = normalizeRecords(records);
        Map<String, String> columns = collectColumnTypes(normalizedRecords);
        ensureTableSchema(tableName, columns);
        List<Map<String, Object>> toWrite = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> row : normalizedRecords) {
            if (existsByDedupKeys(tableName, row, dedupKeys)) {
                skipped++;
            } else {
                toWrite.add(row);
            }
        }
        return new DedupWriteResult(batchInsert(tableName, columns.keySet(), toWrite), skipped);
    }

    private Map<String, Object> mapRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        Map<String, Object> record = new LinkedHashMap<>();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            record.put(metadata.getColumnLabel(column), resultSet.getObject(column));
        }
        return record;
    }

    private boolean existsByDedupKeys(String tableName, Map<String, Object> row, List<String> dedupKeys) {
        String safeTableName = requireIdentifier(tableName, "表名");
        List<String> keys = dedupKeys.stream().filter(key -> key != null && !key.isBlank()).toList();
        if (keys.isEmpty()) return false;
        String where = keys.stream()
                .map(key -> "`" + requireIdentifier(key, "去重字段") + "` = ?")
                .collect(Collectors.joining(" AND "));
        Object[] args = keys.stream().map(row::get).toArray();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + safeTableName + "` WHERE " + where, Integer.class, args);
        return count != null && count > 0;
    }

    private int compareCursorValue(Object left, Object right) {
        Comparable<?> leftValue = comparableCursor(left);
        Comparable<?> rightValue = comparableCursor(right);
        if (leftValue instanceof BigDecimal leftNumber && rightValue instanceof BigDecimal rightNumber) {
            return leftNumber.compareTo(rightNumber);
        }
        if (leftValue instanceof Instant leftInstant && rightValue instanceof Instant rightInstant) {
            return leftInstant.compareTo(rightInstant);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private Comparable<?> comparableCursor(Object value) {
        if (value == null) return "";
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        String text = String.valueOf(value).trim();
        if (text.isBlank()) return "";
        try {
            return new BigDecimal(text);
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(text.replace(" ", "T")).atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(text).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
        }
        return text;
    }

    private List<Map<String, Object>> normalizeRecords(List<Map<String, Object>> records) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            record.forEach((key, value) -> row.put(key, normalizeValue(value)));
            normalized.add(row);
        }
        return normalized;
    }

    private Object normalizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray()) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException exception) {
                return String.valueOf(value);
            }
        }
        if (value instanceof String text && text.isBlank()) {
            return null;
        }
        return value;
    }

    private Map<String, String> collectColumnTypes(List<Map<String, Object>> records) {
        Map<String, List<Object>> samples = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            record.forEach((key, value) -> {
                // 跳过 id 字段，避免与自增主键冲突
                if (!"id".equalsIgnoreCase(key)) {
                    samples.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
                }
            });
        }
        Map<String, String> columns = new LinkedHashMap<>();
        samples.forEach((key, values) -> columns.put(key, inferColumnType(values)));
        return columns;
    }

    private void ensureTableSchema(String tableName, Map<String, String> columns) {
        String safeTableName = requireIdentifier(tableName, "表名");
        if (!tableExists(safeTableName)) {
            String definitions = columns.entrySet().stream()
                    .map(entry -> "`" + sanitize(entry.getKey()) + "` " + entry.getValue())
                    .collect(Collectors.joining(", "));
            String sql = "CREATE TABLE IF NOT EXISTS `" + safeTableName + "` (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + definitions + ", `_created_at` DATETIME DEFAULT CURRENT_TIMESTAMP)";
            jdbc.execute(sql);
            return;
        }

        Map<String, String> existingTypes = loadExistingColumnTypes(safeTableName);
        Set<String> existing = new HashSet<>(existingTypes.keySet());
        for (Map.Entry<String, String> column : columns.entrySet()) {
            String safeColumn = sanitize(column.getKey());
            String desiredType = column.getValue();
            String existingColumn = existing.stream()
                    .filter(name -> name.equalsIgnoreCase(safeColumn))
                    .findFirst()
                    .orElse(null);
            if (existingColumn == null) {
                jdbc.execute("ALTER TABLE `" + safeTableName + "` ADD COLUMN `" + safeColumn + "` " + desiredType);
                existing.add(safeColumn);
                existingTypes.put(safeColumn, desiredType.toLowerCase());
            } else if (isTextType(desiredType) && isVarcharStorage(existingTypes.get(existingColumn))) {
                jdbc.execute("ALTER TABLE `" + safeTableName + "` MODIFY COLUMN `" + safeColumn + "` " + desiredType);
                existingTypes.put(existingColumn, desiredType.toLowerCase());
            } else if (isVarcharType(desiredType) && !isTextCompatible(existingTypes.get(existingColumn))) {
                jdbc.execute("ALTER TABLE `" + safeTableName + "` MODIFY COLUMN `" + safeColumn + "` " + desiredType);
                existingTypes.put(existingColumn, desiredType.toLowerCase());
            }
        }
    }

    private boolean tableExists(String tableName) {
        String existsSql = "SELECT COUNT(*) FROM information_schema.tables WHERE lower(table_name) = lower(?)";
        Integer count = jdbc.queryForObject(existsSql, Integer.class, tableName);
        return count != null && count > 0;
    }

    private Map<String, String> loadExistingColumnTypes(String tableName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns WHERE lower(table_name) = lower(?)",
                tableName);
        Map<String, String> types = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object columnName = row.get("column_name");
            Object dataType = row.get("data_type");
            if (columnName != null && dataType != null) {
                types.put(String.valueOf(columnName), String.valueOf(dataType).toLowerCase());
            }
        }
        return types;
    }

    private int batchInsert(String tableName, Set<String> cols, List<Map<String, Object>> records) {
        String colNames = cols.stream().map(column -> "`" + sanitize(column) + "`").collect(Collectors.joining(", "));
        String placeholders = cols.stream().map(column -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO `" + sanitize(tableName) + "` (" + colNames + ") VALUES (" + placeholders + ")";
        int total = 0;
        List<Object[]> batch = new ArrayList<>();
        for (Map<String, Object> row : records) {
            Object[] args = cols.stream()
                    .map(column -> normalizeValue(row.getOrDefault(column, null)))
                    .toArray();
            batch.add(args);
            if (batch.size() >= 500) {
                total += Arrays.stream(jdbc.batchUpdate(sql, batch)).sum();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) total += Arrays.stream(jdbc.batchUpdate(sql, batch)).sum();
        return total;
    }

    private String inferColumnType(List<Object> values) {
        List<Object> nonNullValues = values.stream().filter(value -> value != null && !String.valueOf(value).isBlank()).toList();
        if (nonNullValues.isEmpty()) return "TEXT";

        boolean hasString = nonNullValues.stream().anyMatch(value -> value instanceof CharSequence);
        boolean hasBoolean = nonNullValues.stream().anyMatch(value -> value instanceof Boolean);
        boolean allNumber = nonNullValues.stream().allMatch(value -> value instanceof Number);
        boolean allBoolean = nonNullValues.stream().allMatch(value -> value instanceof Boolean);

        // 落库阶段优先容错：只要出现字符串或混合类型，先按 VARCHAR 保存，后续交给校验/类型转换控件处理。
        if (hasString || (!allNumber && !allBoolean) || (hasBoolean && !allBoolean)) {
            return "TEXT";
        }
        if (allBoolean) return "BOOLEAN";
        boolean hasDecimal = nonNullValues.stream().anyMatch(value -> value instanceof Double || value instanceof Float || value instanceof BigDecimal);
        return hasDecimal ? "DOUBLE" : "BIGINT";
    }

    private boolean isVarcharType(String sqlType) {
        return sqlType != null && sqlType.toUpperCase().startsWith("VARCHAR");
    }

    private boolean isTextType(String sqlType) {
        return sqlType != null && sqlType.toUpperCase().contains("TEXT");
    }

    private boolean isVarcharStorage(String dataType) {
        return dataType != null && dataType.toLowerCase().contains("char");
    }

    private boolean isTextCompatible(String dataType) {
        if (dataType == null) return false;
        String lower = dataType.toLowerCase();
        return lower.contains("char") || lower.contains("text") || lower.contains("clob") || lower.contains("json");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_");
    }

    private String requireIdentifier(String name, String label) {
        if (name == null || !name.matches("[a-zA-Z0-9_\\u4e00-\\u9fa5]+")) {
            throw new IllegalArgumentException(label + "只能包含字母、数字、下划线或中文字符");
        }
        return name;
    }

    public record DedupWriteResult(int rowsWritten, int duplicateSkipped) {}
}


