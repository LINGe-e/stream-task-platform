package com.a22.platform.engine;

import java.util.List;
import java.util.Map;

public record DagNodeResult(
        String nodeId,
        String nodeLabel,
        String nodeType,
        String kind,
        int inputCount,
        int outputCount,
        String branchName,
        String targetTable,
        String exportFileName,
        String ruleSummary,
        String outputLocation,
        List<Map<String, Object>> inputPreview
) {}
