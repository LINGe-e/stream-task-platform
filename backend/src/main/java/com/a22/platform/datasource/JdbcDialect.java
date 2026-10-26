package com.a22.platform.datasource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 通用 JDBC 输出方言，负责标识符引用、查询分页和原生 UPSERT SQL。
 */
public enum JdbcDialect {
    MYSQL,
    POSTGRESQL,
    ORACLE,
    SQLSERVER,
    GENERIC;

    public static JdbcDialect resolve(String dbType, String jdbcUrl, String explicitDialect) {
        String normalizedDialect = normalize(explicitDialect);
        if (!normalizedDialect.isBlank()) {
            return switch (normalizedDialect) {
                case "mysql", "mariadb", "oceanbase", "tidb" -> MYSQL;
                case "postgresql", "postgres" -> POSTGRESQL;
                case "oracle" -> ORACLE;
                case "sqlserver", "mssql" -> SQLSERVER;
                default -> GENERIC;
            };
        }
        String normalizedType = normalize(dbType);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return switch (normalizedType) {
            case "mysql", "mariadb", "oceanbase", "tidb" -> MYSQL;
            case "postgresql", "postgres" -> POSTGRESQL;
            case "oracle" -> ORACLE;
            case "sqlserver", "mssql" -> SQLSERVER;
            default -> {
                if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) yield MYSQL;
                if (url.startsWith("jdbc:postgresql:")) yield POSTGRESQL;
                if (url.startsWith("jdbc:oracle:")) yield ORACLE;
                if (url.startsWith("jdbc:sqlserver:")) yield SQLSERVER;
                yield GENERIC;
            }
        };
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String quote(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("数据库标识符不能为空");
        }
        String[] parts = identifier.split("\\.", -1);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < parts.length; index += 1) {
            if (index > 0) result.append('.');
            result.append(quotePart(parts[index]));
        }
        return result.toString();
    }

    public String insertSql(String table, List<String> columns) {
        requireColumns(columns);
        return "INSERT INTO " + quote(table)
                + " (" + quoteColumns(columns) + ")"
                + " VALUES (" + placeholders(columns.size()) + ")";
    }

    public Optional<String> nativeUpsertSql(String table, List<String> columns, List<String> keyColumns) {
        requireColumns(columns);
        requireColumns(keyColumns);
        if (!columns.containsAll(keyColumns)) {
            throw new IllegalArgumentException("UPSERT 键必须存在于输出字段中");
        }
        return switch (this) {
            case MYSQL -> Optional.of(insertSql(table, columns)
                    + " ON DUPLICATE KEY UPDATE "
                    + columns.stream()
                    .filter(column -> !keyColumns.contains(column))
                    .map(column -> quote(column) + "=VALUES(" + quote(column) + ")")
                    .collect(java.util.stream.Collectors.joining(",")));
            case POSTGRESQL -> Optional.of(insertSql(table, columns)
                    + " ON CONFLICT (" + quoteColumns(keyColumns) + ") DO UPDATE SET "
                    + columns.stream()
                    .filter(column -> !keyColumns.contains(column))
                    .map(column -> quote(column) + "=EXCLUDED." + quote(column))
                    .collect(java.util.stream.Collectors.joining(",")));
            default -> Optional.empty();
        };
    }

    public String updateSql(String table, List<String> columns, List<String> keyColumns) {
        requireColumns(columns);
        requireColumns(keyColumns);
        List<String> updatable = columns.stream().filter(column -> !keyColumns.contains(column)).toList();
        if (updatable.isEmpty()) {
            throw new IllegalArgumentException("UPSERT 至少需要一个非键字段");
        }
        return "UPDATE " + quote(table)
                + " SET " + updatable.stream().map(column -> quote(column) + "=?").collect(java.util.stream.Collectors.joining(","))
                + " WHERE " + keyColumns.stream().map(column -> quote(column) + "=?").collect(java.util.stream.Collectors.joining(" AND "));
    }

    public String selectSql(String table, int limit) {
        String source = "FROM " + quote(table);
        return switch (this) {
            case ORACLE -> "SELECT * " + source + " FETCH FIRST " + limit + " ROWS ONLY";
            case SQLSERVER -> "SELECT TOP " + limit + " * " + source;
            default -> "SELECT * " + source + " LIMIT " + limit;
        };
    }

    public boolean supportsCreateTableIfNotExists() {
        return this != ORACLE;
    }

    private String quotePart(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据库标识符不能为空");
        }
        return switch (this) {
            case MYSQL -> "`" + value.replace("`", "``") + "`";
            case SQLSERVER -> "[" + value.replace("]", "]]") + "]";
            default -> "\"" + value.replace("\"", "\"\"") + "\"";
        };
    }

    private String quoteColumns(List<String> columns) {
        return columns.stream().map(this::quote).collect(java.util.stream.Collectors.joining(","));
    }

    private String placeholders(int count) {
        return java.util.Collections.nCopies(count, "?").stream().collect(java.util.stream.Collectors.joining(","));
    }

    private void requireColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("输出字段不能为空");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
