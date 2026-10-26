package com.a22.platform.task;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "task_runs")
public class TaskRunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long taskId;
    private String taskName;
    private String status;
    private Integer recordsRead;
    private Integer recordsWritten;
    private String targetTable;
    private String errorMessage;
    private String failureNodeId;
    private String failureNodeLabel;
    private String failureNodeType;
    @Column(length = 30)
    private String triggerType = "MANUAL";
    private Integer retryAttempt = 0;
    private Instant nextRunAt;
    private Boolean maxRetriesReached = false;
    @Column(length = 30)
    private String incrementalMode = "NONE";
    private Integer incrementalInputCount = 0;
    private Integer incrementalWrittenCount = 0;
    private Integer duplicateSkippedCount = 0;
    @Column(length = 80)
    private String cursorField;
    @Column(length = 200)
    private String lastCursorBefore;
    @Column(length = 200)
    private String lastCursorAfter;
    private Integer processedMarkedCount = 0;
    @Column(columnDefinition = "JSON")
    private String logsJson;
    @Column(columnDefinition = "JSON")
    private String branchResultsJson;
    @Column(columnDefinition = "JSON")
    private String nodeResultsJson;
    private Long executorId;
    @Column(length = 60)
    private String executorUsername;
    private Instant startedAt;
    private Instant finishedAt;

    public TaskRunEntity() {}

    public TaskRunEntity(Long taskId, String taskName) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.status = "RUNNING";
        this.recordsRead = 0;
        this.recordsWritten = 0;
        this.startedAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRecordsRead() { return recordsRead; }
    public void setRecordsRead(Integer recordsRead) { this.recordsRead = recordsRead; }
    public Integer getRecordsWritten() { return recordsWritten; }
    public void setRecordsWritten(Integer recordsWritten) { this.recordsWritten = recordsWritten; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getFailureNodeId() { return failureNodeId; }
    public void setFailureNodeId(String failureNodeId) { this.failureNodeId = failureNodeId; }
    public String getFailureNodeLabel() { return failureNodeLabel; }
    public void setFailureNodeLabel(String failureNodeLabel) { this.failureNodeLabel = failureNodeLabel; }
    public String getFailureNodeType() { return failureNodeType; }
    public void setFailureNodeType(String failureNodeType) { this.failureNodeType = failureNodeType; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public Integer getRetryAttempt() { return retryAttempt; }
    public void setRetryAttempt(Integer retryAttempt) { this.retryAttempt = retryAttempt; }
    public Instant getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }
    public Boolean getMaxRetriesReached() { return maxRetriesReached; }
    public void setMaxRetriesReached(Boolean maxRetriesReached) { this.maxRetriesReached = maxRetriesReached; }
    public String getIncrementalMode() { return incrementalMode; }
    public void setIncrementalMode(String incrementalMode) { this.incrementalMode = incrementalMode; }
    public Integer getIncrementalInputCount() { return incrementalInputCount; }
    public void setIncrementalInputCount(Integer incrementalInputCount) { this.incrementalInputCount = incrementalInputCount; }
    public Integer getIncrementalWrittenCount() { return incrementalWrittenCount; }
    public void setIncrementalWrittenCount(Integer incrementalWrittenCount) { this.incrementalWrittenCount = incrementalWrittenCount; }
    public Integer getDuplicateSkippedCount() { return duplicateSkippedCount; }
    public void setDuplicateSkippedCount(Integer duplicateSkippedCount) { this.duplicateSkippedCount = duplicateSkippedCount; }
    public String getCursorField() { return cursorField; }
    public void setCursorField(String cursorField) { this.cursorField = cursorField; }
    public String getLastCursorBefore() { return lastCursorBefore; }
    public void setLastCursorBefore(String lastCursorBefore) { this.lastCursorBefore = lastCursorBefore; }
    public String getLastCursorAfter() { return lastCursorAfter; }
    public void setLastCursorAfter(String lastCursorAfter) { this.lastCursorAfter = lastCursorAfter; }
    public Integer getProcessedMarkedCount() { return processedMarkedCount; }
    public void setProcessedMarkedCount(Integer processedMarkedCount) { this.processedMarkedCount = processedMarkedCount; }
    public String getLogsJson() { return logsJson; }
    public void setLogsJson(String logsJson) { this.logsJson = logsJson; }
    public String getBranchResultsJson() { return branchResultsJson; }
    public void setBranchResultsJson(String branchResultsJson) { this.branchResultsJson = branchResultsJson; }
    public String getNodeResultsJson() { return nodeResultsJson; }
    public void setNodeResultsJson(String nodeResultsJson) { this.nodeResultsJson = nodeResultsJson; }
    public Long getExecutorId() { return executorId; }
    public void setExecutorId(Long executorId) { this.executorId = executorId; }
    public String getExecutorUsername() { return executorUsername; }
    public void setExecutorUsername(String executorUsername) { this.executorUsername = executorUsername; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public void succeed(int recordsRead, int recordsWritten, String targetTable) {
        succeed(recordsRead, recordsWritten, targetTable, null);
    }

    public void succeed(int recordsRead, int recordsWritten, String targetTable, String logsJson) {
        this.status = "SUCCEEDED";
        this.recordsRead = recordsRead;
        this.recordsWritten = recordsWritten;
        this.targetTable = targetTable;
        this.logsJson = logsJson;
        this.finishedAt = Instant.now();
    }

    public void succeed(int recordsRead, int recordsWritten, String targetTable, String logsJson,
                        String branchResultsJson, String nodeResultsJson) {
        succeed(recordsRead, recordsWritten, targetTable, logsJson);
        this.branchResultsJson = branchResultsJson;
        this.nodeResultsJson = nodeResultsJson;
    }

    public void fail(String errorMessage) {
        this.status = "FAILED";
        this.errorMessage = errorMessage != null && errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
        this.finishedAt = Instant.now();
    }

    public void fail(String errorMessage, String failureNodeId, String failureNodeLabel, String failureNodeType) {
        fail(errorMessage);
        this.failureNodeId = failureNodeId;
        this.failureNodeLabel = failureNodeLabel;
        this.failureNodeType = failureNodeType;
    }
}
