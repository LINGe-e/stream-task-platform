package com.a22.platform.task;

import java.time.Instant;
import java.util.List;

public record Task(
        Long id,
        String name,
        String sourceType,
        String sinkType,
        List<String> transforms,
        TaskStatus status,
        long recordsPerSecond,
        Instant updatedAt,
        String graphJson,
        Long creatorId,
        String creatorUsername,
        String runMode,
        String scheduleType,
        String scheduleExpression,
        String continuousRemark,
        Instant lastRunAt,
        Instant nextRunAt,
        boolean continuousRunning,
        int retryCount,
        int maxRetryCount,
        String lastError,
        String incrementalMode,
        String cursorField,
        String lastCursorValue,
        String processedField,
        String unprocessedValue,
        String processedValue,
        String dedupKeys
) {}
