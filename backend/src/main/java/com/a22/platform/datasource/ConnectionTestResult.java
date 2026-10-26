package com.a22.platform.datasource;

public record ConnectionTestResult(
        boolean success,
        String message,
        long latencyMs,
        String databaseProduct,
        String databaseVersion,
        String driverName,
        String driverVersion) {
}
