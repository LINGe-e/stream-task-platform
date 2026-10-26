package com.a22.platform.datasource;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 内置 JDBC 驱动目录；Generic 类型允许使用已放入后端 classpath 的其他驱动。
 */
@Component
public class JdbcDriverCatalog {

    private static final List<JdbcDriverInfo> OPTIONS = List.of(
            new JdbcDriverInfo("mysql", "MySQL", "com.mysql.cj.jdbc.Driver", "mysql", "jdbc:mysql://127.0.0.1:3306/database"),
            new JdbcDriverInfo("mariadb", "MariaDB", "org.mariadb.jdbc.Driver", "mysql", "jdbc:mariadb://127.0.0.1:3306/database"),
            new JdbcDriverInfo("postgresql", "PostgreSQL", "org.postgresql.Driver", "postgresql", "jdbc:postgresql://127.0.0.1:5432/database"),
            new JdbcDriverInfo("oracle", "Oracle", "oracle.jdbc.OracleDriver", "oracle", "jdbc:oracle:thin:@127.0.0.1:1521/service"),
            new JdbcDriverInfo("sqlserver", "SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "sqlserver", "jdbc:sqlserver://127.0.0.1:1433;databaseName=database"),
            new JdbcDriverInfo("oceanbase", "OceanBase", "com.mysql.cj.jdbc.Driver", "mysql", "jdbc:mysql://127.0.0.1:2881/database"),
            new JdbcDriverInfo("tidb", "TiDB", "com.mysql.cj.jdbc.Driver", "mysql", "jdbc:mysql://127.0.0.1:4000/database"),
            new JdbcDriverInfo("h2", "H2", "org.h2.Driver", "mysql", "jdbc:h2:mem:test"),
            new JdbcDriverInfo("generic", "其他 JDBC 数据库", "", "", "jdbc:database://host/database"));

    public List<JdbcDriverInfo> options() {
        return OPTIONS;
    }

    public String resolve(String dbType, String requestedDriverClass) {
        String requested = requestedDriverClass == null ? "" : requestedDriverClass.trim();
        if (!requested.isBlank()) return requested;
        String normalized = dbType == null ? "" : dbType.trim().toLowerCase(Locale.ROOT);
        return OPTIONS.stream()
                .filter(option -> option.dbType().equalsIgnoreCase(normalized))
                .map(JdbcDriverInfo::driverClass)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Generic 数据库必须指定 driverClass"));
    }

    public void load(String driverClass) {
        if (driverClass == null || driverClass.isBlank()) return;
        try {
            Class.forName(driverClass, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("未找到 JDBC 驱动类：" + driverClass
                    + "。请确认数据库驱动已加入后端 classpath。", exception);
        } catch (LinkageError error) {
            throw new IllegalArgumentException("JDBC 驱动加载失败：" + driverClass + "，" + error.getMessage(), error);
        }
    }
}
