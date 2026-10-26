package com.a22.platform.datasource;

/**
 * 解密后的 JDBC 连接设置，仅在服务端执行期间使用。
 */
public record JdbcConnectionSettings(
        Long id,
        String dbType,
        String driverClass,
        String url,
        String username,
        String password,
        String dialect,
        String schemaName) {
}
