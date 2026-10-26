package com.a22.platform.datasource;

import java.time.Instant;

public record OutputDataSourceView(
        Long id,
        String name,
        String dbType,
        String driverClass,
        String url,
        String username,
        boolean hasPassword,
        String dialect,
        String schemaName,
        String testStatus,
        String testMessage,
        Instant testAt,
        Instant createdAt,
        Instant updatedAt) {
}
