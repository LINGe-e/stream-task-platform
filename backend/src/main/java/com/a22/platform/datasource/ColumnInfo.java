package com.a22.platform.datasource;

public record ColumnInfo(
        String name,
        String typeName,
        int size,
        boolean nullable,
        boolean primaryKey,
        String remarks) {
}
