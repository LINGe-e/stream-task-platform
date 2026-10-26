package com.a22.platform.task;

import com.a22.platform.engine.ExecuteResult;
import com.a22.platform.engine.NodeExecutionException;
import com.a22.platform.auth.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TaskRunService {
    private final TaskRunRepository repository;
    private final ObjectMapper objectMapper;

    public TaskRunService(TaskRunRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public TaskRun start(Task task) {
        TaskRunEntity entity = new TaskRunEntity(task.id(), task.name());
        entity.setExecutorId(AuthContext.getUserId());
        entity.setExecutorUsername(AuthContext.getUsername());
        return TaskRun.fromEntity(repository.save(entity));
    }

    public TaskRun startContinuous(Task task, int retryAttempt) {
        TaskRunEntity entity = new TaskRunEntity(task.id(), task.name());
        entity.setTriggerType("CONTINUOUS");
        entity.setRetryAttempt(retryAttempt);
        entity.setExecutorUsername("system-scheduler");
        return TaskRun.fromEntity(repository.save(entity));
    }

    public TaskRun succeed(long runId, ExecuteResult result) {
        TaskRunEntity entity = repository.findById(runId).orElseThrow();
        entity.succeed(result.recordsRead(), result.recordsWritten(), result.targetTable(), toLogsJson(result),
                toJson(result.branchResults()), toJson(result.nodeResults()));
        applyIncrementalStats(entity, result);
        return TaskRun.fromEntity(repository.save(entity));
    }

    public TaskRun succeedContinuous(long runId, ExecuteResult result, Instant nextRunAt) {
        TaskRunEntity entity = repository.findById(runId).orElseThrow();
        entity.succeed(result.recordsRead(), result.recordsWritten(), result.targetTable(), toLogsJson(result),
                toJson(result.branchResults()), toJson(result.nodeResults()));
        entity.setNextRunAt(nextRunAt);
        entity.setMaxRetriesReached(false);
        applyIncrementalStats(entity, result);
        return TaskRun.fromEntity(repository.save(entity));
    }

    private void applyIncrementalStats(TaskRunEntity entity, ExecuteResult result) {
        entity.setIncrementalMode(result.incrementalMode());
        entity.setIncrementalInputCount(result.incrementalInputCount());
        entity.setIncrementalWrittenCount(result.incrementalWrittenCount());
        entity.setDuplicateSkippedCount(result.duplicateSkippedCount());
        entity.setCursorField(result.cursorField());
        entity.setLastCursorBefore(result.lastCursorBefore());
        entity.setLastCursorAfter(result.lastCursorAfter());
        entity.setProcessedMarkedCount(result.processedMarkedCount());
    }

    private String toLogsJson(ExecuteResult result) {
        return toJson(result.logs());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    public TaskRun fail(long runId, Exception exception) {
        TaskRunEntity entity = repository.findById(runId).orElseThrow();
        String message = exception.getMessage() != null ? exception.getMessage().substring(0, Math.min(exception.getMessage().length(), 200)) : "Unknown error";
        if (exception instanceof NodeExecutionException nodeException) {
            entity.fail(message, nodeException.getNodeId(), nodeException.getNodeLabel(), nodeException.getNodeType());
        } else {
            entity.fail(message);
        }
        return TaskRun.fromEntity(repository.save(entity));
    }

    public TaskRun failContinuous(long runId, Exception exception, Instant nextRunAt, boolean maxRetriesReached) {
        TaskRunEntity entity = repository.findById(runId).orElseThrow();
        String message = exception.getMessage() != null ? exception.getMessage().substring(0, Math.min(exception.getMessage().length(), 200)) : "Unknown error";
        if (exception instanceof NodeExecutionException nodeException) {
            entity.fail(message, nodeException.getNodeId(), nodeException.getNodeLabel(), nodeException.getNodeType());
        } else {
            entity.fail(message);
        }
        entity.setNextRunAt(nextRunAt);
        entity.setMaxRetriesReached(maxRetriesReached);
        return TaskRun.fromEntity(repository.save(entity));
    }

    public List<TaskRun> listByTaskId(long taskId) {
        return repository.findByTaskId(taskId).stream().map(TaskRun::fromEntity).toList();
    }

    public List<TaskRun> listRecent() {
        return repository.findTop100ByOrderByStartedAtDesc().stream().map(TaskRun::fromEntity).toList();
    }
}
