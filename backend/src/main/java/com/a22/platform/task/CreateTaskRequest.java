package com.a22.platform.task;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateTaskRequest(
        @NotBlank(message = "任务名称不能为空") String name,
        @NotBlank(message = "来源类型不能为空") String sourceType,
        @NotBlank(message = "目标类型不能为空") String sinkType,
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
