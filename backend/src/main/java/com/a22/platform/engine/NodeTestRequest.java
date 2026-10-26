package com.a22.platform.engine;

import java.util.List;
import java.util.Map;

public record NodeTestRequest(
        String kind,
        String label,
        String config,
        List<Map<String, Object>> records
) {}
