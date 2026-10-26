package com.a22.platform.datasource;

public record JdbcDriverInfo(String dbType, String label, String driverClass, String dialect, String exampleUrl) { }
