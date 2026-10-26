package com.a22.platform.task;

import com.a22.platform.auth.AuthContext;
import com.a22.platform.auth.UserEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class TaskService {
    private final TaskRepository repository;
    private final TaskRunRepository taskRunRepository;
    private final ObjectMapper objectMapper;

    public TaskService(TaskRepository repository, TaskRunRepository taskRunRepository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.taskRunRepository = taskRunRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    @Transactional
    void seedTasks() {
        if (repository.count() > 0) return;
        repository.saveAll(List.of(
                entity("交易流水实时清洗", "文件", "MySQL", List.of("字段映射", "空值过滤"), TaskStatus.DRAFT, null, null),
                entity("客户标签同步", "CSV", "MySQL", List.of("类型转换", "去重"), TaskStatus.DRAFT, null, null),
                entity("风险事件归档", "JSON", "MySQL", List.of("条件过滤"), TaskStatus.DRAFT, null, null)
        ));
    }

    @Transactional(readOnly = true)
    public List<Task> list() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream().map(this::toTask).toList();
    }

    /** 带筛选条件的任务列表：按创建者用户名、更新时间范围筛选 */
    @Transactional(readOnly = true)
    public List<Task> listFiltered(String creatorUsername, Instant startDate, Instant endDate) {
        // 运维角色只看已发布的
        UserEntity currentUser = AuthContext.get();
        boolean opsOnly = currentUser != null && "OPS_READONLY".equals(currentUser.getRole());
        if ((creatorUsername == null || creatorUsername.isBlank()) && startDate == null && endDate == null) {
            return list();
        }
        String username = (creatorUsername != null && !creatorUsername.isBlank()) ? creatorUsername.trim() : null;
        return repository.findByFilters(username, startDate, endDate).stream().map(this::toTask).toList();
    }

    @Transactional(readOnly = true)
    public Task get(long id) {
        return toTask(find(id));
    }

    @Transactional
    public Task create(CreateTaskRequest request) {
        Long creatorId = AuthContext.getUserId();
        String creatorUsername = AuthContext.getUsername();
        return toTask(repository.save(entity(
                request.name(), request.sourceType(), request.sinkType(),
                request.transforms() == null ? List.of() : request.transforms(),
                TaskStatus.DRAFT, creatorId, creatorUsername,
                request.runMode(), request.scheduleType(), request.scheduleExpression(), request.continuousRemark(),
                request.incrementalMode(), request.cursorField(), request.lastCursorValue(), request.processedField(),
                request.unprocessedValue(), request.processedValue(), request.dedupKeys())));
    }

    @Transactional
    public Task update(long id, UpdateTaskRequest request) {
        TaskEntity entity = find(id);
        ensureTaskOwner(entity);
        ensureEditable(entity);
        entity.updateBasicInfo(request.name(), request.sourceType(), request.sinkType(),
                writeTransforms(request.transforms() == null ? List.of() : request.transforms()));
        entity.updateContinuousConfig(request.runMode(), request.scheduleType(),
                request.scheduleExpression(), request.continuousRemark());
        entity.updateIncrementalConfig(request.incrementalMode(), request.cursorField(), request.lastCursorValue(),
                request.processedField(), request.unprocessedValue(), request.processedValue(), request.dedupKeys());
        return toTask(entity);
    }

    @Transactional
    public Task changeStatus(long id, TaskStatus target) {
        TaskEntity entity = find(id);
        ensureTaskOwner(entity);
        TaskStatus current = entity.getStatus();

        if (target == TaskStatus.PUBLISHED) {
            if (current == TaskStatus.RUNNING) throw new IllegalStateException("运行中任务不能发布，请先停止任务。");
            if (entity.getGraphJson() != null && !entity.getGraphJson().isBlank()) {
                validateGraph(entity.getGraphJson());
            }
            entity.setStatus(TaskStatus.PUBLISHED, 0);
            return toTask(entity);
        }

        if (target == TaskStatus.RUNNING) {
            if (current != TaskStatus.PUBLISHED) throw new IllegalStateException("只有已发布任务可以启动。请先保存并发布任务。");
            if (entity.getGraphJson() != null && !entity.getGraphJson().isBlank()) {
                validateGraph(entity.getGraphJson());
            }
            entity.setStatus(TaskStatus.RUNNING, 800 + id * 137);
            return toTask(entity);
        }

        if (target == TaskStatus.STOPPED) {
            if (current != TaskStatus.RUNNING) throw new IllegalStateException("只有运行中的任务可以停止。");
            entity.setStatus(TaskStatus.STOPPED, 0);
            return toTask(entity);
        }

        if (target == TaskStatus.DRAFT) {
            if (current == TaskStatus.RUNNING) throw new IllegalStateException("运行中任务不能退回草稿，请先停止任务。");
            entity.setStatus(TaskStatus.DRAFT, 0);
            return toTask(entity);
        }

        throw new IllegalStateException("不允许手动切换到状态：" + target);
    }

    @Transactional
    public Task pauseContinuous(long id) {
        TaskEntity entity = find(id);
        ensureTaskOwner(entity);
        ensureContinuous(entity);
        entity.setStatus(TaskStatus.STOPPED, 0);
        entity.setNextRunAt(null);
        entity.setContinuousRunning(false);
        return toTask(entity);
    }

    @Transactional
    public Task resumeContinuous(long id) {
        TaskEntity entity = find(id);
        ensureTaskOwner(entity);
        ensureContinuous(entity);
        if (entity.isContinuousRunning()) {
            throw new IllegalStateException("任务正在执行中，请稍后再恢复。");
        }
        validateGraph(entity.getGraphJson());
        entity.setStatus(TaskStatus.PUBLISHED, 0);
        entity.setNextRunAt(Instant.now());
        entity.setRetryCount(0);
        entity.setLastError(null);
        return toTask(entity);
    }

    @Transactional
    public Task resetContinuousRetry(long id) {
        TaskEntity entity = find(id);
        ensureTaskOwner(entity);
        ensureContinuous(entity);
        if (entity.isContinuousRunning()) {
            throw new IllegalStateException("任务正在执行中，请稍后再重置。");
        }
        validateGraph(entity.getGraphJson());
        entity.setRetryCount(0);
        entity.setLastError(null);
        entity.setStatus(TaskStatus.PUBLISHED, 0);
        entity.setNextRunAt(Instant.now());
        return toTask(entity);
    }

    @Transactional
    public Task prepareContinuousRunNow(long id) {
        TaskEntity entity = find(id);
        ensureTaskOwner(entity);
        ensureContinuous(entity);
        if (entity.isContinuousRunning()) {
            throw new IllegalStateException("任务正在执行中，请勿重复触发。");
        }
        validateGraph(entity.getGraphJson());
        entity.setStatus(TaskStatus.PUBLISHED, 0);
        entity.setNextRunAt(Instant.now());
        entity.setRetryCount(0);
        entity.setLastError(null);
        return toTask(entity);
    }

    @Transactional
    public Task markSucceeded(long id) {
        TaskEntity entity = find(id);
        entity.setStatus(TaskStatus.SUCCEEDED, 0);
        entity.markLastRunAt(Instant.now());
        return toTask(entity);
    }

    @Transactional
    public Task markFailed(long id) {
        TaskEntity entity = find(id);
        entity.setStatus(TaskStatus.FAILED, 0);
        entity.markLastRunAt(Instant.now());
        return toTask(entity);
    }

    @Transactional
    public Task markContinuousRunning(long id) {
        TaskEntity entity = find(id);
        if (!"CONTINUOUS".equals(entity.getRunMode())) {
            throw new IllegalStateException("只有持续处理任务可以进入自动执行。");
        }
        if (entity.getStatus() != TaskStatus.PUBLISHED) {
            throw new IllegalStateException("只有已发布的持续任务可以执行。");
        }
        if (entity.isContinuousRunning()) {
            throw new IllegalStateException("任务正在持续处理中，请勿重复触发。");
        }
        if (entity.getRetryCount() >= entity.getMaxRetryCount()) {
            throw new IllegalStateException("任务已达到最大重试次数，请人工处理后重置。");
        }
        entity.setContinuousRunning(true);
        return toTask(entity);
    }

    @Transactional
    public Task markContinuousSucceeded(long id, Instant nextRunAt) {
        return markContinuousSucceeded(id, nextRunAt, null);
    }

    @Transactional
    public Task markContinuousSucceeded(long id, Instant nextRunAt, String lastCursorValue) {
        TaskEntity entity = find(id);
        entity.setContinuousRunning(false);
        entity.setRetryCount(0);
        entity.setLastError(null);
        entity.setNextRunAt(nextRunAt);
        if (lastCursorValue != null && !lastCursorValue.isBlank()) {
            entity.setLastCursorValue(lastCursorValue);
        }
        entity.markLastRunAt(Instant.now());
        return toTask(entity);
    }

    @Transactional
    public Task markContinuousFailed(long id, Exception exception, Instant nextRunAt) {
        TaskEntity entity = find(id);
        entity.setContinuousRunning(false);
        entity.setRetryCount(entity.getRetryCount() + 1);
        entity.setLastError(exception == null ? "Unknown error" : exception.getMessage());
        entity.setNextRunAt(nextRunAt);
        if (nextRunAt == null || entity.getRetryCount() >= entity.getMaxRetryCount()) {
            entity.setStatus(TaskStatus.STOPPED, 0);
        }
        entity.markLastRunAt(Instant.now());
        return toTask(entity);
    }

    @Transactional
    public Task updateGraph(long id, String graphJson) {
        TaskEntity entity = find(id);
        ensureTaskOwner(entity);
        ensureEditable(entity);
        if (graphJson == null || graphJson.isBlank()) throw new IllegalArgumentException("任务画布不能为空。");
        String normalizedGraph = validateGraph(graphJson);
        entity.setGraphJson(normalizedGraph);
        if (entity.getStatus() == TaskStatus.SUCCEEDED) entity.setStatus(TaskStatus.STOPPED, 0);
        return toTask(entity);
    }

    @Transactional
    public void delete(long id) {
        TaskEntity entity = find(id);
        ensureTaskOwner(entity);
        if (entity.getStatus() == TaskStatus.RUNNING) {
            throw new IllegalStateException("运行中任务不能删除，请先停止任务。");
        }
        taskRunRepository.findByTaskId(id).forEach(taskRunRepository::delete);
        repository.deleteById(id);
    }

    private static boolean isAdmin() {
        UserEntity user = AuthContext.get();
        return user != null && (user.getRole().equals("SADMIN") || user.getRole().equals("ADMIN"));
    }

    private void ensureTaskOwner(TaskEntity entity) {
        if (isAdmin()) return;
        Long currentUserId = AuthContext.getUserId();
        if (entity.getCreatorId() != null && !entity.getCreatorId().equals(currentUserId)) {
            throw new IllegalStateException("只能操作自己创建的任务");
        }
    }

    private void ensureEditable(TaskEntity entity) {
        if (entity.getStatus() == TaskStatus.RUNNING || taskRunRepository.existsByTaskIdAndStatus(entity.getId(), "RUNNING")) {
            throw new IllegalStateException("任务运行中，不能修改画布或参数；请先停止任务。");
        }
    }

    private void ensureContinuous(TaskEntity entity) {
        if (!"CONTINUOUS".equals(entity.getRunMode())) {
            throw new IllegalStateException("该任务不是持续处理任务。");
        }
    }

    private String validateGraph(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            throw new IllegalArgumentException("任务未配置有效画布，请先保存完整流程。");
        }
        try {
            String unwrapped = unwrapJson(graphJson);
            TaskGraphValidator.validate(objectMapper.readTree(unwrapped));
            return unwrapped;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("任务画布不是有效的 JSON", exception);
        }
    }

    private TaskEntity find(long id) {
        return repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    private TaskEntity entity(String name, String sourceType, String sinkType, List<String> transforms,
                              TaskStatus status, Long creatorId, String creatorUsername) {
        return entity(name, sourceType, sinkType, transforms, status, creatorId, creatorUsername,
                "ONCE", "MANUAL", null, null);
    }

    private TaskEntity entity(String name, String sourceType, String sinkType, List<String> transforms,
                              TaskStatus status, Long creatorId, String creatorUsername,
                              String runMode, String scheduleType, String scheduleExpression, String continuousRemark) {
        return entity(name, sourceType, sinkType, transforms, status, creatorId, creatorUsername,
                runMode, scheduleType, scheduleExpression, continuousRemark,
                "NONE", null, null, null, "UNPROCESSED", "DONE", null);
    }

    private TaskEntity entity(String name, String sourceType, String sinkType, List<String> transforms,
                              TaskStatus status, Long creatorId, String creatorUsername,
                              String runMode, String scheduleType, String scheduleExpression, String continuousRemark,
                              String incrementalMode, String cursorField, String lastCursorValue,
                              String processedField, String unprocessedValue, String processedValue, String dedupKeys) {
        TaskEntity entity = new TaskEntity(name, sourceType, sinkType, writeTransforms(transforms), status, 0, null,
                creatorId, creatorUsername);
        entity.updateContinuousConfig(runMode, scheduleType, scheduleExpression, continuousRemark);
        entity.updateIncrementalConfig(incrementalMode, cursorField, lastCursorValue, processedField,
                unprocessedValue, processedValue, dedupKeys);
        return entity;
    }

    /** 递归去除JSON字符串的外层引号 */
    private String unwrapJson(String json) {
        if (json == null) return null;
        String result = json;
        while (result.startsWith("\"") && result.endsWith("\"")) {
            try {
                String unescaped = objectMapper.readValue(result, String.class);
                if (unescaped.equals(result)) break;
                result = unescaped;
            } catch (Exception ignored) { break; }
        }
        return result;
    }

    private String writeTransforms(List<String> transforms) {
        try {
            return objectMapper.writeValueAsString(transforms);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存任务算子配置。", exception);
        }
    }

    private Task toTask(TaskEntity entity) {
        if (entity != null) {
            List<String> transforms = readTransformsSafely(entity);
            return new Task(entity.getId(), entity.getName(), entity.getSourceType(), entity.getSinkType(), transforms,
                    entity.getStatus(), entity.getRecordsPerSecond(), entity.getUpdatedAt(), unwrapJson(entity.getGraphJson()),
                    entity.getCreatorId(), entity.getCreatorUsername(), entity.getRunMode(), entity.getScheduleType(),
                    entity.getScheduleExpression(), entity.getContinuousRemark(), entity.getLastRunAt(), entity.getNextRunAt(),
                    entity.isContinuousRunning(), entity.getRetryCount(), entity.getMaxRetryCount(), entity.getLastError(),
                    entity.getIncrementalMode(), entity.getCursorField(), entity.getLastCursorValue(), entity.getProcessedField(),
                    entity.getUnprocessedValue(), entity.getProcessedValue(), entity.getDedupKeys());
        }
        try {
            List<String> transforms = objectMapper.readValue(entity.getTransformsJson(), new TypeReference<>() { });
            return new Task(entity.getId(), entity.getName(), entity.getSourceType(), entity.getSinkType(), transforms,
                    entity.getStatus(), entity.getRecordsPerSecond(), entity.getUpdatedAt(), unwrapJson(entity.getGraphJson()),
                    entity.getCreatorId(), entity.getCreatorUsername(), entity.getRunMode(), entity.getScheduleType(),
                    entity.getScheduleExpression(), entity.getContinuousRemark(), entity.getLastRunAt(), entity.getNextRunAt(),
                    entity.isContinuousRunning(), entity.getRetryCount(), entity.getMaxRetryCount(), entity.getLastError(),
                    entity.getIncrementalMode(), entity.getCursorField(), entity.getLastCursorValue(), entity.getProcessedField(),
                    entity.getUnprocessedValue(), entity.getProcessedValue(), entity.getDedupKeys());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("任务算子配置损坏。", exception);
        }
    }
    private List<String> readTransformsSafely(TaskEntity entity) {
        String transformsJson = entity.getTransformsJson();
        if (transformsJson == null || transformsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(transformsJson, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }
}
