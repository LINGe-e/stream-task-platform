package com.a22.platform.engine.transform;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class TransformContext {
    private final String kind;
    private final String config;
    private final List<Map<String, Object>> records;

    public TransformContext(String kind, String config, List<Map<String, Object>> records) {
        this.kind = kind;
        this.config = config == null ? "" : config;
        this.records = records == null ? Collections.emptyList() : records;
    }

    public String getKind() {
        return kind;
    }

    public String getConfig() {
        return config;
    }

    public List<Map<String, Object>> getRecords() {
        return records;
    }
}
