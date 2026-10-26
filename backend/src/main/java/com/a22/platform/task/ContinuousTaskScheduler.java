package com.a22.platform.task;

import com.a22.platform.engine.ExecuteResult;
import com.a22.platform.engine.IncrementalExecutionContext;
import com.a22.platform.engine.TaskExecutionEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Service
public class ContinuousTaskScheduler {
    private final TaskRepository repository;
    private final TaskService taskService;
    private final TaskRunService runService;
    private final TaskExecutionEngine engine;
    private final boolean enabled;

    public ContinuousTaskScheduler(TaskRepository repository, TaskService taskService,
                                   TaskRunService runService, TaskExecutionEngine engine,
                                   @Value("${app.continuous.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.taskService = taskService;
        this.runService = runService;
        this.engine = engine;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.continuous.scan-delay-ms:30000}")
    public void scanAndRunDueTasks() {
        if (!enabled) return;
        Instant now = Instant.now();
        List<TaskEntity> dueTasks = repository.findDueContinuousTasks(now);
        for (TaskEntity entity : dueTasks) {
            runOne(entity.getId());
        }
    }

    void runOne(long taskId) {
        Task task = taskService.markContinuousRunning(taskId);
        Instant nextRunAt = calculateNextRunAt(task, Instant.now());
        TaskRun run = runService.startContinuous(task, task.retryCount() + 1);
        try {
            ExecuteResult result = engine.execute(task.graphJson(), null, IncrementalExecutionContext.fromTask(task));
            taskService.markContinuousSucceeded(taskId, nextRunAt, result.lastCursorAfter());
            runService.succeedContinuous(run.id(), result, nextRunAt);
        } catch (Exception exception) {
            boolean maxReached = task.retryCount() + 1 >= task.maxRetryCount();
            taskService.markContinuousFailed(taskId, exception, maxReached ? null : nextRunAt);
            runService.failContinuous(run.id(), exception, maxReached ? null : nextRunAt, maxReached);
        }
    }

    static Instant calculateNextRunAt(Task task, Instant base) {
        String scheduleType = safeUpper(task.scheduleType(), "MANUAL");
        String expression = task.scheduleExpression();
        if ("FIXED_RATE".equals(scheduleType)) {
            return base.plus(parseDuration(expression));
        }
        if ("DAILY".equals(scheduleType)) {
            return nextDailyTime(base, expression);
        }
        if ("CRON".equals(scheduleType)) {
            return base.plus(Duration.ofMinutes(30));
        }
        return base.plus(Duration.ofMinutes(30));
    }

    private static Duration parseDuration(String expression) {
        if (expression == null || expression.isBlank()) return Duration.ofMinutes(30);
        String text = expression.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("s")) return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
            if (text.endsWith("m")) return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
            if (text.endsWith("h")) return Duration.ofHours(Long.parseLong(text.substring(0, text.length() - 1)));
            if (text.endsWith("d")) return Duration.ofDays(Long.parseLong(text.substring(0, text.length() - 1)));
            return Duration.ofMinutes(Long.parseLong(text));
        } catch (Exception ignored) {
            return Duration.ofMinutes(30);
        }
    }

    private static Instant nextDailyTime(Instant base, String expression) {
        LocalTime time;
        try {
            time = LocalTime.parse(expression == null || expression.isBlank() ? "08:30" : expression.trim());
        } catch (Exception ignored) {
            time = LocalTime.of(8, 30);
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.ofInstant(base, zone);
        Instant candidate = today.atTime(time).atZone(zone).toInstant();
        if (!candidate.isAfter(base)) {
            candidate = today.plusDays(1).atTime(time).atZone(zone).toInstant();
        }
        return candidate;
    }

    private static String safeUpper(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }
}
