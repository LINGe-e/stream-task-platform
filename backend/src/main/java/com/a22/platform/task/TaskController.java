package com.a22.platform.task;

import com.a22.platform.engine.DynamicTableService;
import com.a22.platform.engine.ExecuteResult;
import com.a22.platform.engine.TaskExecutionEngine;
import com.a22.platform.file.StoredFile;
import com.a22.platform.file.StoredFileRepository;
import com.a22.platform.flink.FlinkJobSubmission;
import com.a22.platform.flink.FlinkSubmitRequest;
import com.a22.platform.flink.FlinkTaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@CrossOrigin
public class TaskController {
    private final TaskService service;
    private final TaskExecutionEngine engine;
    private final TaskRunService runService;
    private final FlinkTaskService flinkTaskService;
    private final StoredFileRepository fileRepository;
    private final DynamicTableService tableService;
    private final ObjectMapper objectMapper;
    private final ContinuousTaskScheduler taskScheduler;

    public TaskController(TaskService service, TaskExecutionEngine engine, TaskRunService runService,
                          FlinkTaskService flinkTaskService, StoredFileRepository fileRepository,
                          DynamicTableService tableService, ObjectMapper objectMapper,
                          ContinuousTaskScheduler taskScheduler) {
        this.service = service;
        this.engine = engine;
        this.runService = runService;
        this.flinkTaskService = flinkTaskService;
        this.fileRepository = fileRepository;
        this.tableService = tableService;
        this.objectMapper = objectMapper;
        this.taskScheduler = taskScheduler;
    }

    private String generateDefaultGraph(Task task) {
        String sourceType = task.sourceType() != null ? task.sourceType() : "JSON";
        String sinkType = task.sinkType() != null ? task.sinkType() : "MySQL";
        String src = sourceType.toLowerCase();
        if ("文件".equals(sourceType)) src = "json-file";
        return String.format("""
            {
              "nodes": [
                {"id": "start", "type": "start", "position": {"x": 50, "y": 200}},
                {"id": "source1", "type": "source", "position": {"x": 250, "y": 200}, "data": {"nodeType": "source", "kind": "%s", "label": "%s"}},
                {"id": "sink1", "type": "sink", "position": {"x": 500, "y": 200}, "data": {"nodeType": "sink", "kind": "%s", "label": "%s", "targetTable": "task_result"}}
              ],
              "edges": [
                {"id": "e1", "source": "start", "target": "source1"},
                {"id": "e2", "source": "source1", "target": "sink1"}
              ]
            }
            """, src, sourceType, sinkType.toLowerCase(), sinkType);
    }

    @GetMapping
    public List<Task> list(
            @RequestParam(required = false) String creatorUsername,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Instant start = parseInstant(startDate);
        Instant end = parseInstant(endDate);
        return service.listFiltered(creatorUsername, start, end);
    }

    /** 解析日期字符串，支持 ISO-8601 和 yyyy-MM-dd 格式 */
    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (Exception ignored) {}
        try { return java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(); } catch (Exception ignored) {}
        return null;
    }

    @GetMapping("/{id}")
    public Task get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Task create(@Valid @RequestBody CreateTaskRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public Task update(@PathVariable long id, @Valid @RequestBody UpdateTaskRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public Task changeStatus(@PathVariable long id, @RequestParam TaskStatus value) {
        return service.changeStatus(id, value);
    }

    @PostMapping("/{id}/continuous/pause")
    public Task pauseContinuous(@PathVariable long id) {
        return service.pauseContinuous(id);
    }

    @PostMapping("/{id}/continuous/resume")
    public Task resumeContinuous(@PathVariable long id) {
        return service.resumeContinuous(id);
    }

    @PostMapping("/{id}/continuous/reset-retry")
    public Task resetContinuousRetry(@PathVariable long id) {
        return service.resetContinuousRetry(id);
    }

    @PostMapping("/{id}/continuous/run-now")
    public Task runContinuousNow(@PathVariable long id) {
        service.prepareContinuousRunNow(id);
        taskScheduler.runOne(id);
        return service.get(id);
    }

    @PatchMapping("/{id}/graph")
    public Task updateGraph(@PathVariable long id, @RequestBody String graphJson) {
        if (graphJson != null && graphJson.startsWith("\"") && graphJson.endsWith("\"")) {
            try { graphJson = objectMapper.readValue(graphJson, String.class); } catch (Exception ignored) {}
        }
        return service.updateGraph(id, graphJson);
    }

    /**
     * 执行作业：优先提交到 Flink 集群（分布式），Flink 不可用时回退到内存引擎。
     */
    @PostMapping("/{id}/execute")
    public Map<String, Object> execute(@PathVariable long id, @RequestParam(required = false) Long sourceFileId) throws Exception {
        Task task = service.get(id);
        String graphJson = task.graphJson();
        if (requiresUploadedFile(graphJson) && sourceFileId == null) {
            throw new IllegalArgumentException("该画布使用文件输入节点。请在启动窗口选择本次要读取的 CSV、Excel、JSON 或 XML 文件后再执行。");
        }
        if (task.status() == TaskStatus.RUNNING) {
            service.markFailed(id);
            task = service.get(id);
        }
        if (task.status() != TaskStatus.PUBLISHED) {
            task = service.changeStatus(id, TaskStatus.PUBLISHED);
        }
        task = service.changeStatus(id, TaskStatus.RUNNING);
        TaskRun run = runService.start(task);

        // 文件导入场景会由本地 DAG 引擎同步处理，以便运行记录准确展示读取量、
        // 写入量和多分支结果；Kafka 实时单链路才提交到 Flink 集群。
        String sourceTable = null;
        if (sourceFileId != null) {
            sourceTable = prepareSourceTable(sourceFileId, id);
        }

        // 优先：提交动态管道作业到 Flink 集群
        FlinkJobSubmission submission = null;
        if (shouldSubmitToFlink(graphJson, sourceFileId)) {
            submission = flinkTaskService.submitDynamicPipeline(id, graphJson, sourceTable, null);
        }

        if (submission != null && ("RUNNING".equals(submission.status()) || "SUBMITTED".equals(submission.status()))) {
            runService.succeed(run.id(), new ExecuteResult(0, 0, List.of(
                    "动态管道作业已提交到 Flink 集群",
                    "Flink Job ID: " + submission.flinkJobId(),
                    "作业名称: " + submission.jobName(),
                    sourceTable != null ? "源数据表: " + sourceTable : "数据源: 画布配置",
                    "请在 Flink 管理页面查看运行状态")));
            service.markSucceeded(id);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobId", submission.flinkJobId());
            result.put("jobName", submission.jobName());
            result.put("status", "SUBMITTED");
            result.put("engine", "flink");
            result.put("message", "动态管道作业已提交到 Flink 集群");
            return result;
        }

        // 回退：Flink 不可用时使用内存执行引擎
        try {
            ExecuteResult result = engine.execute(graphJson, sourceFileId);
            runService.succeed(run.id(), result);
            service.markSucceeded(id);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "COMPLETED");
            response.put("engine", "local");
            response.put("recordsRead", result.recordsRead());
            response.put("recordsWritten", result.recordsWritten());
            response.put("targetTable", result.targetTable());
            response.put("logs", result.logs());
            response.put("branchResults", result.branchResults());
            response.put("nodeResults", result.nodeResults());
            response.put("message", "作业已在本地内存引擎执行完成");
            if (submission != null) {
                response.put("flinkError", submission.message());
            }
            return response;
        } catch (Exception e) {
            runService.fail(run.id(), e);
            service.markFailed(id);
            throw e;
        }
    }

    /**
     * 文件输入需要由前端明确选择本次上传文件，不能默默以空数据执行。
     */
    private boolean requiresUploadedFile(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) return false;
        try {
            for (com.fasterxml.jackson.databind.JsonNode node : objectMapper.readTree(graphJson).path("nodes")) {
                if (!"source".equals(node.path("data").path("nodeType").asText())) continue;
                String kind = node.path("data").path("kind").asText("").toLowerCase();
                if (kind.equals("csv") || kind.equals("json-file") || kind.equals("excel") || kind.equals("xml")) return true;
            }
        } catch (Exception ignored) {
            // 画布合法性由执行引擎统一校验。
        }
        return false;
    }

    /**
     * 当前 Flink 动态作业是单链路流式实现。带文件输入或任意分支的画布必须走
     * 本地 DAG 引擎，以保留通过/拒绝分支、多个输出和可复核的读写统计。
     */
    private boolean shouldSubmitToFlink(String graphJson, Long sourceFileId) {
        if (sourceFileId != null || graphJson == null || graphJson.isBlank()) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode graph = objectMapper.readTree(graphJson);
            boolean kafkaSource = false;
            Map<String, Integer> outgoing = new LinkedHashMap<>();
            for (com.fasterxml.jackson.databind.JsonNode edge : graph.path("edges")) {
                String source = edge.path("source").asText();
                if (!source.isBlank()) outgoing.merge(source, 1, Integer::sum);
            }
            for (com.fasterxml.jackson.databind.JsonNode node : graph.path("nodes")) {
                if (outgoing.getOrDefault(node.path("id").asText(), 0) > 1) return false;
                if ("source".equals(node.path("data").path("nodeType").asText())
                        && "kafka".equalsIgnoreCase(node.path("data").path("kind").asText())) {
                    kafkaSource = true;
                }
            }
            return kafkaSource;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 将上传文件的数据写入临时 MySQL 表，供 Flink JDBC 数据源读取。
     */
    private String prepareSourceTable(Long sourceFileId, long taskId) throws Exception {
        StoredFile file = fileRepository.findById(sourceFileId).orElse(null);
        if (file == null) return null;
        String tempTable = "flink_source_" + taskId;
        String json = file.getContentJson();
        com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(json);
        if (jsonNode.isTextual()) {
            jsonNode = objectMapper.readTree(jsonNode.asText());
        }
        List<Map<String, Object>> data = objectMapper.convertValue(jsonNode, new TypeReference<>() {});
        if (data != null && !data.isEmpty()) {
            tableService.writeData(tempTable, data);
        }
        return tempTable;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/flink")
    public FlinkJobSubmission submitToFlink(@PathVariable long id, @Valid @RequestBody(required = false) FlinkSubmitRequest request) {
        return flinkTaskService.submitTask(id, request == null ? 1 : request.safeParallelism());
    }
}
