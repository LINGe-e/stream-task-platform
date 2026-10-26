package com.a22.platform.engine;

import java.util.List;
import java.util.Map;

public record NodeTestResult(
        String kind,
        String label,
        int inputCount,
        int outputCount,
        List<Map<String, Object>> preview,
        List<String> logs
) {}
