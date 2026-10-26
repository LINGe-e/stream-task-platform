package com.a22.platform.engine;

import java.util.List;

public record ExecuteResult(
        int recordsRead,
        int recordsWritten,
        List<String> logs,
        String targetTable,
        List<ExportFile> exports,
        List<DagBranchResult> branchResults,
        List<DagNodeResult> nodeResults,
        String incrementalMode,
        int incrementalInputCount,
        int incrementalWrittenCount,
        int duplicateSkippedCount,
        String cursorField,
        String lastCursorBefore,
        String lastCursorAfter,
        int processedMarkedCount
) {
    public ExecuteResult(int recordsRead, int recordsWritten, List<String> logs) {
        this(recordsRead, recordsWritten, logs, "", List.of(), List.of(), List.of(),
                "NONE", 0, recordsWritten, 0, "", "", "", 0);
    }

    public ExecuteResult(int recordsRead, int recordsWritten, List<String> logs, String targetTable) {
        this(recordsRead, recordsWritten, logs, targetTable, List.of(), List.of(), List.of(),
                "NONE", 0, recordsWritten, 0, "", "", "", 0);
    }

    public ExecuteResult(int recordsRead, int recordsWritten, List<String> logs, String targetTable, List<ExportFile> exports) {
        this(recordsRead, recordsWritten, logs, targetTable, exports, List.of(), List.of(),
                "NONE", 0, recordsWritten, 0, "", "", "", 0);
    }

    public ExecuteResult(int recordsRead, int recordsWritten, List<String> logs, String targetTable,
                         List<ExportFile> exports, List<DagBranchResult> branchResults, List<DagNodeResult> nodeResults) {
        this(recordsRead, recordsWritten, logs, targetTable, exports, branchResults, nodeResults,
                "NONE", 0, recordsWritten, 0, "", "", "", 0);
    }

    public ExecuteResult withProcessedMarkedCount(int processedMarkedCount) {
        return new ExecuteResult(recordsRead, recordsWritten, logs, targetTable, exports, branchResults, nodeResults,
                incrementalMode, incrementalInputCount, incrementalWrittenCount, duplicateSkippedCount,
                cursorField, lastCursorBefore, lastCursorAfter, processedMarkedCount);
    }
}
