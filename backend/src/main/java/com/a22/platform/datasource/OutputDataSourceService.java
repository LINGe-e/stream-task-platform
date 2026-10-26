package com.a22.platform.datasource;

import com.a22.platform.auth.AuthContext;
import com.a22.platform.auth.AuthService;
import com.a22.platform.auth.UserEntity;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Service
public class OutputDataSourceService {

    private static final int LOGIN_TIMEOUT_SECONDS = 8;
    private static final int MAX_TABLES = 500;

    private final OutputDataSourceRepository repository;
    private final SecretCipher secretCipher;
    private final JdbcDriverCatalog driverCatalog;

    public OutputDataSourceService(
            OutputDataSourceRepository repository,
            SecretCipher secretCipher,
            JdbcDriverCatalog driverCatalog) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.driverCatalog = driverCatalog;
    }

    public List<JdbcDriverInfo> drivers() {
        return driverCatalog.options();
    }

    public List<OutputDataSourceView> list() {
        Long ownerId = requireUserId();
        return repository.findByOwnerIdOrderByNameAsc(ownerId).stream().map(this::view).toList();
    }

    public OutputDataSourceView create(OutputDataSourceRequest request) {
        validateJdbcUrl(request.getUrl());
        Long ownerId = requireUserId();
        String driverClass = driverCatalog.resolve(request.getDbType(), request.getDriverClass());
        driverCatalog.load(driverClass);

        OutputDataSourceEntity entity = new OutputDataSourceEntity();
        entity.setOwnerId(ownerId);
        applyRequest(entity, request);
        entity.setDriverClass(driverClass);
        entity.setEncryptedPassword(secretCipher.encrypt(nullToEmpty(request.getPassword())));
        entity.setDialect(canonicalDialect(request.getDbType(), request.getDialect(), request.getUrl()));
        return view(repository.save(entity));
    }

    public OutputDataSourceView update(Long id, OutputDataSourceRequest request) {
        validateJdbcUrl(request.getUrl());
        OutputDataSourceEntity entity = findOwned(id);
        String driverClass = driverCatalog.resolve(request.getDbType(), request.getDriverClass());
        driverCatalog.load(driverClass);

        applyRequest(entity, request);
        entity.setDriverClass(driverClass);
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            entity.setEncryptedPassword(secretCipher.encrypt(request.getPassword()));
        }
        entity.setDialect(canonicalDialect(request.getDbType(), request.getDialect(), request.getUrl()));
        return view(repository.save(entity));
    }

    public void delete(Long id) {
        repository.delete(findOwned(id));
    }

    public ConnectionTestResult test(OutputDataSourceTestRequest request) {
        OutputDataSourceEntity entity = request.getId() == null ? null : findOwned(request.getId());
        String dbType = firstNonBlank(request.getDbType(), entity == null ? null : entity.getDbType());
        String url = firstNonBlank(request.getUrl(), entity == null ? null : entity.getUrl());
        if (dbType == null || url == null) {
            throw new IllegalArgumentException("测试连接需要数据库类型和 JDBC URL");
        }
        validateJdbcUrl(url);
        String username = request.getUsername() != null
                ? request.getUsername()
                : entity == null ? "" : nullToEmpty(entity.getUsername());
        String password = request.getPassword() != null && !request.getPassword().isBlank()
                ? request.getPassword()
                : entity == null ? "" : secretCipher.decrypt(nullToEmpty(entity.getEncryptedPassword()));
        String driverClass = driverCatalog.resolve(
                dbType,
                firstNonBlank(request.getDriverClass(), entity == null ? null : entity.getDriverClass()));
        driverCatalog.load(driverClass);
        String dialect = canonicalDialect(
                dbType,
                firstNonBlank(request.getDialect(), entity == null ? null : entity.getDialect()),
                url);

        long startedAt = System.currentTimeMillis();
        try {
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                DatabaseMetaData metadata = connection.getMetaData();
                long latency = System.currentTimeMillis() - startedAt;
                ConnectionTestResult result = new ConnectionTestResult(
                        true,
                        "连接成功",
                        latency,
                        safe(metadata.getDatabaseProductName()),
                        safe(metadata.getDatabaseProductVersion()),
                        safe(metadata.getDriverName()),
                        safe(metadata.getDriverVersion()));
                saveTestResult(entity, true, result.message());
                return result;
            }
        } catch (SQLException exception) {
            long latency = System.currentTimeMillis() - startedAt;
            String message = "连接失败：" + safeSqlMessage(exception);
            saveTestResult(entity, false, message);
            return new ConnectionTestResult(false, message, latency, "", "", "", "");
        }
    }

    public List<TableInfo> tables(Long id) {
        JdbcConnectionSettings settings = connectionForExecution(id);
        List<TableInfo> tables = new ArrayList<>();
        try (Connection connection = open(settings)) {
            DatabaseMetaData metadata = connection.getMetaData();
            String schema = blankToNull(settings.schemaName());
            try (ResultSet resultSet = metadata.getTables(
                    connection.getCatalog(), schema, "%", new String[]{"TABLE", "VIEW"})) {
                while (resultSet.next() && tables.size() < MAX_TABLES) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    if (tableName == null || tableName.isBlank()) continue;
                    tables.add(new TableInfo(
                            tableName,
                            nullToEmpty(resultSet.getString("TABLE_SCHEM")),
                            nullToEmpty(resultSet.getString("TABLE_TYPE")),
                            nullToEmpty(resultSet.getString("REMARKS"))));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取数据表列表失败：" + safeSqlMessage(exception), exception);
        }
        tables.sort(Comparator.comparing(TableInfo::name, String.CASE_INSENSITIVE_ORDER));
        return tables;
    }

    public List<ColumnInfo> columns(Long id, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        JdbcConnectionSettings settings = connectionForExecution(id);
        String schema = settings.schemaName();
        String targetTable = tableName;
        int dot = tableName.lastIndexOf('.');
        if (dot >= 0) {
            schema = tableName.substring(0, dot);
            targetTable = tableName.substring(dot + 1);
        }

        Map<String, Boolean> primaryKeys = new HashMap<>();
        List<ColumnInfo> columns = new ArrayList<>();
        try (Connection connection = open(settings)) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet keys = metadata.getPrimaryKeys(connection.getCatalog(), blankToNull(schema), targetTable)) {
                while (keys.next()) {
                    primaryKeys.put(keys.getString("COLUMN_NAME"), true);
                }
            }
            try (ResultSet resultSet = metadata.getColumns(
                    connection.getCatalog(), blankToNull(schema), targetTable, null)) {
                while (resultSet.next()) {
                    String name = resultSet.getString("COLUMN_NAME");
                    columns.add(new ColumnInfo(
                            name,
                            nullToEmpty(resultSet.getString("TYPE_NAME")),
                            resultSet.getInt("COLUMN_SIZE"),
                            resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            primaryKeys.getOrDefault(name, false),
                            nullToEmpty(resultSet.getString("REMARKS"))));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取表字段失败：" + safeSqlMessage(exception), exception);
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("未找到表或字段：" + tableName);
        }
        return columns;
    }

    public JdbcConnectionSettings connectionForExecution(Long id) {
        if (id == null) throw new IllegalArgumentException("输出节点未选择数据源");
        OutputDataSourceEntity entity = findOwned(id);
        String driverClass = driverCatalog.resolve(entity.getDbType(), entity.getDriverClass());
        driverCatalog.load(driverClass);
        String dialect = canonicalDialect(entity.getDbType(), entity.getDialect(), entity.getUrl());
        return new JdbcConnectionSettings(
                entity.getId(),
                entity.getDbType(),
                driverClass,
                entity.getUrl(),
                nullToEmpty(entity.getUsername()),
                secretCipher.decrypt(nullToEmpty(entity.getEncryptedPassword())),
                dialect,
                nullToEmpty(entity.getSchemaName()));
    }

    private Connection open(JdbcConnectionSettings settings) throws SQLException {
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        return DriverManager.getConnection(settings.url(), settings.username(), settings.password());
    }

    private OutputDataSourceEntity findOwned(Long id) {
        OutputDataSourceEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在：" + id));
        UserEntity user = AuthContext.get();
        if (user != null
                && !entity.getOwnerId().equals(user.getId())
                && !AuthService.isAdmin(user.getRole())) {
            throw new IllegalStateException("无权访问该数据源");
        }
        return entity;
    }

    private void applyRequest(OutputDataSourceEntity entity, OutputDataSourceRequest request) {
        entity.setName(request.getName().trim());
        entity.setDbType(request.getDbType().trim().toLowerCase(Locale.ROOT));
        entity.setUrl(request.getUrl().trim());
        entity.setUsername(nullToEmpty(request.getUsername()).trim());
        entity.setSchemaName(nullToEmpty(request.getSchemaName()).trim());
    }

    private void saveTestResult(OutputDataSourceEntity entity, boolean success, String message) {
        if (entity == null) return;
        entity.setTestStatus(success ? "ok" : "failed");
        entity.setTestMessage(message);
        entity.setTestAt(Instant.now());
        repository.save(entity);
    }

    private OutputDataSourceView view(OutputDataSourceEntity entity) {
        return new OutputDataSourceView(
                entity.getId(),
                entity.getName(),
                entity.getDbType(),
                nullToEmpty(entity.getDriverClass()),
                entity.getUrl(),
                nullToEmpty(entity.getUsername()),
                entity.getEncryptedPassword() != null && !entity.getEncryptedPassword().isBlank(),
                nullToEmpty(entity.getDialect()),
                nullToEmpty(entity.getSchemaName()),
                nullToEmpty(entity.getTestStatus()),
                nullToEmpty(entity.getTestMessage()),
                entity.getTestAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private String canonicalDialect(String dbType, String explicitDialect, String url) {
        return JdbcDialect.resolve(dbType, url, explicitDialect).key();
    }

    private void validateJdbcUrl(String url) {
        if (url == null || !url.trim().toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
            throw new IllegalArgumentException("JDBC URL 必须以 jdbc: 开头");
        }
    }

    private Long requireUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) throw new IllegalStateException("请先登录后再管理数据库数据源");
        return userId;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeSqlMessage(SQLException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() > 800 ? message.substring(0, 800) : message;
    }
}
