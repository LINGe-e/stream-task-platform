package com.a22.platform.engine;

import com.a22.platform.task.Task;

import java.util.Arrays;
import java.util.List;

public record IncrementalExecutionContext(
        String mode,
        String cursorField,
        String lastCursorValue,
        String processedField,
        String unprocessedValue,
        String processedValue,
        List<String> dedupKeys
) {
    public static IncrementalExecutionContext disabled() {
        return new IncrementalExecutionContext("NONE", "", "", "", "UNPROCESSED", "DONE", List.of());
    }

    public static IncrementalExecutionContext fromTask(Task task) {
        if (task == null || task.incrementalMode() == null || task.incrementalMode().isBlank()) {
            return disabled();
        }
        return new IncrementalExecutionContext(
                safe(task.incrementalMode(), "NONE").toUpperCase(),
                safe(task.cursorField(), ""),
                safe(task.lastCursorValue(), ""),
                safe(task.processedField(), ""),
                safe(task.unprocessedValue(), "UNPROCESSED"),
                safe(task.processedValue(), "DONE"),
                parseKeys(task.dedupKeys())
        );
    }

    public boolean enabled() {
        return !"NONE".equalsIgnoreCase(mode);
    }

    public boolean statusMode() {
        return "STATUS".equalsIgnoreCase(mode);
    }

    public boolean cursorMode() {
        return "CURSOR".equalsIgnoreCase(mode);
    }

    private static List<String> parseKeys(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[,;，；]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
