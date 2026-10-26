package com.a22.platform.task;

import java.util.List;

public record UpdateTaskRequest(
        String name,
        String sourceType,
        String sinkType,
        List<String> transforms,
        String runMode,
        String scheduleType,
        String scheduleExpression,
        String continuousRemark,
        String incrementalMode,
        String cursorField,
        String lastCursorValue,
        String processedField,
        String unprocessedValue,
        String processedValue,
        String dedupKeys
) {}
