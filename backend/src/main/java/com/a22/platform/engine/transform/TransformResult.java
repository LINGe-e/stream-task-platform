package com.a22.platform.engine.transform;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class TransformResult {
    private final List<Map<String, Object>> records;

    public TransformResult(List<Map<String, Object>> records) {
        this.records = records == null ? Collections.emptyList() : records;
    }

    public List<Map<String, Object>> getRecords() {
        return records;
    }
}
