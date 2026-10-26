package com.a22.platform.engine;

import com.a22.platform.file.StoredFile;
import com.a22.platform.file.StoredFileRepository;
import com.a22.platform.file.XmlJsonConverter;
import com.a22.platform.task.TaskGraphValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.a22.platform.engine.transform.TransformPluginRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class TaskExecutionEngine {
    private final DynamicTableService tableService;
    private final StoredFileRepository fileRepository;
    private final ObjectMapper mapper;
    private final XmlJsonConverter xmlJsonConverter;
    private final ExportService exportService;
    private final RedisSinkService redisSinkService;
    private final RedisSourceService redisSourceService;
    private final JdbcDataSourceService jdbcDataSourceService;
    private final KafkaSinkService kafkaSinkService;
    private final KafkaSourceService kafkaSourceService;
    private final HdfsSourceService hdfsSourceService;
    private final HdfsSinkService hdfsSinkService;
    private final S3SourceService s3SourceService;
    private final S3SinkService s3SinkService;
    private final TransformPluginRegistry transformPluginRegistry;

    public TaskExecutionEngine(DynamicTableService tableService,
                               StoredFileRepository fileRepository,
                               ObjectMapper mapper,
                               XmlJsonConverter xmlJsonConverter,
                               ExportService exportService,
                               RedisSinkService redisSinkService,
                               RedisSourceService redisSourceService,
                               JdbcDataSourceService jdbcDataSourceService,
                               KafkaSinkService kafkaSinkService,
                               KafkaSourceService kafkaSourceService,
                               HdfsSourceService hdfsSourceService,
                               HdfsSinkService hdfsSinkService,
                               S3SourceService s3SourceService,
                               S3SinkService s3SinkService,
                               TransformPluginRegistry transformPluginRegistry) {
        this.tableService = tableService;
        this.fileRepository = fileRepository;
        this.mapper = mapper;
        this.xmlJsonConverter = xmlJsonConverter;
        this.exportService = exportService;
        this.redisSinkService = redisSinkService;
        this.redisSourceService = redisSourceService;
        this.jdbcDataSourceService = jdbcDataSourceService;
        this.kafkaSinkService = kafkaSinkService;
        this.kafkaSourceService = kafkaSourceService;
        this.hdfsSourceService = hdfsSourceService;
        this.hdfsSinkService = hdfsSinkService;
        this.s3SourceService = s3SourceService;
        this.s3SinkService = s3SinkService;
        this.transformPluginRegistry = transformPluginRegistry;
    }

    public ExecuteResult execute(String graphJson, Long sourceFileId) throws Exception {
        return execute(graphJson, sourceFileId, IncrementalExecutionContext.disabled());
    }

    public NodeTestResult testNode(NodeTestRequest request) {
        String kind = request == null ? "" : valueOrDefault(request.kind(), "");
        String label = request == null ? kind : valueOrDefault(request.label(), kind);
        String config = request == null ? "" : valueOrDefault(request.config(), "");
        List<Map<String, Object>> input = request == null || request.records() == null ? List.of() : request.records();
        if (kind.isBlank()) {
            throw new IllegalArgumentException("节点类型不能为空。");
        }
        List<Map<String, Object>> output = applyTransform(kind, config, input);
        List<String> logs = List.of(
                "节点级测试完成：" + label + "（" + kind + "）",
                "输入 " + input.size() + " 条，输出 " + output.size() + " 条",
                summarizeNodeRule(kind, config)
        );
        return new NodeTestResult(kind, label, input.size(), output.size(), output.stream().limit(20).toList(), logs);
    }

    public ExecuteResult execute(String graphJson, Long sourceFileId, IncrementalExecutionContext incrementalContext) throws Exception {
        IncrementalExecutionContext incremental = incrementalContext == null ? IncrementalExecutionContext.disabled() : incrementalContext;
        IncrementalState incrementalState = new IncrementalState(incremental);
        if (graphJson == null || graphJson.isBlank() || "null".equalsIgnoreCase(graphJson.trim())) {
            throw new IllegalArgumentException("任务画布为空：请先在作业编排页生成或配置流程，并点击“保存画布”后再运行。");
        }
        JsonNode graph = mapper.readTree(graphJson);
        TaskGraphValidator.validate(graph);
        DagGraph dag = buildDagGraph(graph);

        Map<String, List<Map<String, Object>>> nodeOutputs = new LinkedHashMap<>();
        Map<String, List<List<Map<String, Object>>>> nodeInputs = new LinkedHashMap<>();
        Map<String, String> nodePaths = new LinkedHashMap<>();
        List<String> logs = new ArrayList<>();
        List<ExportFile> exports = new ArrayList<>();
        List<String> targetTables = new ArrayList<>();
        List<DagBranchResult> branchResults = new ArrayList<>();
        List<DagNodeResult> nodeResults = new ArrayList<>();
        Set<String> sourceFileOverrideUsed = new HashSet<>();

        int recordsRead = 0;
        int recordsWritten = 0;
        int duplicateSkipped = 0;

        for (JsonNode node : topologicalOrder(dag)) {
            String nodeId = node.path("id").asText();
            String nodeType = node.path("data").path("nodeType").asText();
            String kind = node.path("data").path("kind").asText("");
            String label = node.path("data").path("label").asText(valueOrDefault(kind, nodeId));
            String config = node.path("data").path("config").asText("");
            List<Map<String, Object>> input = mergeIncoming(nodeInputs.get(nodeId));
            String nodePath = nodePaths.getOrDefault(nodeId, label);

            try {
            if ("source".equals(nodeType)) {
                List<Map<String, Object>> sourceData;
                if (sourceFileId != null && sourceFileOverrideUsed.isEmpty()) {
                    sourceData = readFromUploadedFile(sourceFileId);
                    sourceFileOverrideUsed.add(nodeId);
                    logs.add("输入节点[" + label + "]使用指定上传文件，读取 " + sourceData.size() + " 条");
                } else {
                    sourceData = readFromSourceNode(node, incrementalState);
                    logs.add("输入节点[" + label + "]读取 " + sourceData.size() + " 条");
                }
                recordsRead += sourceData.size();
                nodeOutputs.put(nodeId, sourceData);
                nodePaths.put(nodeId, label);
                nodeResults.add(new DagNodeResult(nodeId, label, nodeType, kind, 0, sourceData.size(), nodePaths.get(nodeId), "", "",
                        summarizeNodeRule(kind, config), "", previewRows(sourceData)));
                routeToNext(dag, nodeId, sourceData, nodeInputs, nodePaths);
                continue;
            }

            if ("transform".equals(nodeType)) {
                if (input.isEmpty()) {
                    nodeOutputs.put(nodeId, List.of());
                    logs.add("处理节点[" + label + "]未收到上游数据，输出 0 条");
                    nodeResults.add(new DagNodeResult(nodeId, label, nodeType, kind, 0, 0, nodePath, "", "",
                            summarizeNodeRule(kind, config), "", List.of()));
                    routeToNext(dag, nodeId, List.of(), nodeInputs, nodePaths);
                    continue;
                }
                if ("filter".equals(kind) && dag.next.getOrDefault(nodeId, List.of()).size() > 1) {
                    FilterBranch branch = splitFilter(config, input);
                    nodeOutputs.put(nodeId, branch.passed());
                    logs.add("条件过滤节点[" + label + "]符合 " + branch.passed().size() + " 条，不符合 " + branch.rejected().size() + " 条");
                    nodeResults.add(new DagNodeResult(nodeId, label, nodeType, kind, input.size(), input.size(), nodePath, "", "",
                            summarizeNodeRule(kind, config), "", previewRows(input)));
                    routeFilterBranches(dag, nodeId, branch, input.size(), config, nodeInputs, nodePaths, branchResults, logs);
                    continue;
                }
                List<Map<String, Object>> output = applyTransform(kind, config, input);
                nodeOutputs.put(nodeId, output);
                logs.add("处理节点[" + label + "]输入 " + input.size() + " 条，输出 " + output.size() + " 条");
                nodeResults.add(new DagNodeResult(nodeId, label, nodeType, kind, input.size(), output.size(), nodePath, "", "",
                        summarizeNodeRule(kind, config), "", previewRows(input)));
                routeToNext(dag, nodeId, output, nodeInputs, nodePaths);
                continue;
            }

            if ("sink".equals(nodeType)) {
                SinkWriteResult writeResult = writeSinkNode(node, input, logs, incremental);
                recordsWritten += writeResult.rowsWritten();
                duplicateSkipped += writeResult.duplicateSkipped();
                exports.addAll(writeResult.exports());
                if (!writeResult.targetTable().isBlank()) {
                    targetTables.add(writeResult.targetTable());
                }
                nodeOutputs.put(nodeId, input);
                nodeResults.add(new DagNodeResult(nodeId, label, nodeType, kind, input.size(), writeResult.rowsWritten(),
                        nodePath, writeResult.targetTable(), writeResult.exportFileName(),
                        summarizeNodeRule(kind, config), outputLocation(writeResult.targetTable(), writeResult.exportFileName()), previewRows(input)));
                routeToNext(dag, nodeId, input, nodeInputs, nodePaths);
                continue;
            }

            nodeOutputs.put(nodeId, input);
            if (!dag.next.getOrDefault(nodeId, List.of()).isEmpty()) {
                nodeResults.add(new DagNodeResult(nodeId, label, nodeType, kind, input.size(), input.size(), nodePath, "", "",
                        summarizeNodeRule(kind, config), "", previewRows(input)));
                routeToNext(dag, nodeId, input, nodeInputs, nodePaths);
            }
            } catch (NodeExecutionException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new NodeExecutionException(nodeId, label, nodeType, exception);
            } catch (Exception exception) {
                throw new NodeExecutionException(nodeId, label, nodeType, exception);
            }
        }

        if (recordsRead == 0) {
            logs.add("源数据为空，任务未写入结果表");
        }
        String targetTableSummary = targetTables.isEmpty() ? "" : String.join(",", targetTables);
        int processedMarkedCount = 0;
        if (incremental.statusMode() && !incrementalState.sourceTable.isBlank()
                && !incremental.processedField().isBlank() && !incrementalState.sourceIds.isEmpty()) {
            processedMarkedCount = tableService.markProcessedByIds(incrementalState.sourceTable,
                    incrementalState.sourceIds, incremental.processedField(), incremental.processedValue());
            logs.add("增量状态回写：源表 " + incrementalState.sourceTable + " 标记 " + processedMarkedCount
                    + " 条为 " + incremental.processedValue());
        }
        if (incremental.enabled()) {
            logs.add("增量处理统计：模式 " + incremental.mode()
                    + "，本次扫描 " + incrementalState.inputCount + " 条，写入 " + recordsWritten
                    + " 条，跳过重复 " + duplicateSkipped + " 条"
                    + (incrementalState.lastCursorAfter.isBlank() ? "" : "，游标推进到 " + incrementalState.lastCursorAfter));
        }
        logs.add("DAG 多链路执行完成：节点 " + dag.nodes.size() + " 个，连线 " + dag.edgeCount() + " 条，读取 " + recordsRead + " 条，写入 " + recordsWritten + " 条");
        return new ExecuteResult(recordsRead, recordsWritten, logs, targetTableSummary, exports, branchResults, nodeResults,
                incremental.mode(), incrementalState.inputCount, recordsWritten, duplicateSkipped,
                incremental.cursorField(), incremental.lastCursorValue(), incrementalState.lastCursorAfter, processedMarkedCount);
    }

    private List<Map<String, Object>> previewRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        return rows.stream()
                .limit(5)
                .map(row -> (Map<String, Object>) new LinkedHashMap<>(row))
                .toList();
    }

    private DagGraph buildDagGraph(JsonNode graph) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        graph.path("nodes").forEach(node -> {
            if (!node.hasNonNull("id")) throw new IllegalArgumentException("画布节点缺少 id");
            nodes.put(node.path("id").asText(), node);
        });
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<Edge>> next = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> {
            indegree.put(id, 0);
            next.put(id, new ArrayList<>());
        });
        if (graph.has("edges") && graph.path("edges").isArray()) {
            for (JsonNode edgeNode : graph.path("edges")) {
                String source = edgeNode.path("source").asText();
                String target = edgeNode.path("target").asText();
                if (!nodes.containsKey(source) || !nodes.containsKey(target)) {
                    throw new IllegalArgumentException("连线引用了不存在的节点");
                }
                Edge edge = new Edge(
                        edgeNode.path("id").asText(source + "->" + target),
                        source,
                        target,
                        edgeNode.path("label").asText(""),
                        edgeNode.path("condition").asText("")
                );
                next.get(source).add(edge);
                indegree.put(target, indegree.get(target) + 1);
            }
        }
        return new DagGraph(nodes, next, indegree);
    }

    private List<JsonNode> topologicalOrder(DagGraph dag) {
        Map<String, Integer> indegree = new LinkedHashMap<>(dag.indegree);
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) queue.add(id); });
        List<JsonNode> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            ordered.add(dag.nodes.get(id));
            for (Edge edge : dag.next.getOrDefault(id, List.of())) {
                int degree = indegree.merge(edge.target(), -1, Integer::sum);
                if (degree == 0) queue.addLast(edge.target());
            }
        }
        if (ordered.size() != dag.nodes.size()) {
            throw new IllegalArgumentException("任务画布存在循环连线，无法执行");
        }
        return ordered;
    }

    private List<Map<String, Object>> mergeIncoming(List<List<Map<String, Object>>> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        if (inputs.size() == 1) return cloneRecords(inputs.get(0));
        List<Map<String, Object>> merged = new ArrayList<>();
        for (List<Map<String, Object>> input : inputs) {
            merged.addAll(cloneRecords(input));
        }
        return merged;
    }

    private void routeToNext(DagGraph dag,
                             String sourceNodeId,
                             List<Map<String, Object>> records,
                             Map<String, List<List<Map<String, Object>>>> nodeInputs,
                             Map<String, String> nodePaths) {
        for (Edge edge : dag.next.getOrDefault(sourceNodeId, List.of())) {
            nodeInputs.computeIfAbsent(edge.target(), key -> new ArrayList<>()).add(cloneRecords(records));
            nodePaths.putIfAbsent(edge.target(), extendPath(nodePaths.get(sourceNodeId), nodeLabel(dag, sourceNodeId), nodeLabel(dag, edge.target())));
        }
    }

    private void routeFilterBranches(DagGraph dag,
                                      String sourceNodeId,
                                      FilterBranch branch,
                                      int inputCount,
                                      String filterConfig,
                                      Map<String, List<List<Map<String, Object>>>> nodeInputs,
                                      Map<String, String> nodePaths,
                                      List<DagBranchResult> branchResults,
                                      List<String> logs) {
        List<Edge> outgoing = dag.next.getOrDefault(sourceNodeId, List.of());
        for (int i = 0; i < outgoing.size(); i++) {
            Edge edge = outgoing.get(i);
            boolean rejectedBranch = isRejectedBranch(edge, i);
            List<Map<String, Object>> routed = rejectedBranch ? branch.rejected() : branch.passed();
            nodeInputs.computeIfAbsent(edge.target(), key -> new ArrayList<>()).add(cloneRecords(routed));
            logs.add("分支[" + edgeLabel(edge, rejectedBranch) + "]传递到节点 " + edge.target() + "：" + routed.size() + " 条");
            String sourceLabel = nodeLabel(dag, sourceNodeId);
            String targetLabel = nodeLabel(dag, edge.target());
            String branchName = edgeLabel(edge, rejectedBranch);
            String path = extendPath(extendPath(nodePaths.get(sourceNodeId), sourceLabel, branchName), branchName, targetLabel);
            nodePaths.put(edge.target(), path);
            branchResults.add(new DagBranchResult(branchName, rejectedBranch ? "REJECT" : "PASS",
                    sourceNodeId, sourceLabel, edge.target(), targetLabel, inputCount, routed.size(), path,
                    rejectedBranch ? "不满足：" + summarizeFilterRule(filterConfig) : summarizeFilterRule(filterConfig)));
        }
    }

    private String nodeLabel(DagGraph dag, String nodeId) {
        JsonNode node = dag.nodes.get(nodeId);
        if (node == null) return nodeId;
        String kind = node.path("data").path("kind").asText("");
        return node.path("data").path("label").asText(valueOrDefault(kind, nodeId));
    }

    private String extendPath(String currentPath, String fallbackSource, String targetLabel) {
        String base = currentPath == null || currentPath.isBlank() ? fallbackSource : currentPath;
        if (targetLabel == null || targetLabel.isBlank() || base.endsWith(targetLabel)) return base;
        return base + " -> " + targetLabel;
    }

    private boolean isRejectedBranch(Edge edge, int index) {
        String text = (edge.label() + " " + edge.condition()).toLowerCase(Locale.ROOT);
        if (text.contains("false") || text.contains("reject") || text.contains("fail") || text.contains("error")
                || text.contains("否") || text.contains("不符合") || text.contains("异常") || text.contains("失败")) {
            return true;
        }
        if (text.contains("true") || text.contains("pass") || text.contains("success")
                || text.contains("是") || text.contains("符合") || text.contains("正常") || text.contains("成功")) {
            return false;
        }
        return index == 1;
    }

    private String edgeLabel(Edge edge, boolean rejectedBranch) {
        if (!edge.label().isBlank()) return edge.label();
        if (!edge.condition().isBlank()) return edge.condition();
        return rejectedBranch ? "不符合条件" : "符合条件";
    }

    private FilterBranch splitFilter(String config, List<Map<String, Object>> data) {
        List<Map<String, Object>> passed = applyFilter(config, data);
        Set<Map<String, Object>> passedIdentity = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        passedIdentity.addAll(passed);
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (Map<String, Object> row : data) {
            if (!passedIdentity.contains(row)) {
                rejected.add(row);
            }
        }
        return new FilterBranch(passed, rejected);
    }

    private List<Map<String, Object>> cloneRecords(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return List.of();
        List<Map<String, Object>> cloned = new ArrayList<>(records.size());
        for (Map<String, Object> row : records) {
            cloned.add(new LinkedHashMap<>(row));
        }
        return cloned;
    }

    private SinkWriteResult writeSinkNode(JsonNode sink, List<Map<String, Object>> data, List<String> logs,
                                          IncrementalExecutionContext incremental) throws Exception {
        String sinkKind = sink.path("data").path("kind").asText();
        String label = sink.path("data").path("label").asText(valueOrDefault(sinkKind, "输出节点"));
        String config = sink.path("data").path("config").asText("");
        String targetTable = sink.path("data").path("targetTable").asText("");
        if (targetTable.isBlank()) targetTable = extractTargetTable(config);
        if (targetTable.isBlank()) targetTable = "task_output_" + sanitizeTableSuffix(sink.path("id").asText(String.valueOf(System.currentTimeMillis())));

        List<ExportFile> exports = new ArrayList<>();
        // 文件输出不写入动态结果表，避免宽表字段限制影响 CSV/Excel 导出。
        if ("csv".equals(sinkKind)) {
            ExportFile exportFile = exportService.exportCsv(extractOutputFileName(config, targetTable), data);
            exports.add(exportFile);
            logs.add("输出节点[" + label + "]生成 CSV 导出文件：" + exportFile.fileName());
            return new SinkWriteResult(data.size(), 0, "", exports, exportFile.fileName());
        }
        if ("excel".equals(sinkKind)) {
            ExportFile exportFile = exportService.exportExcel(extractOutputFileName(config, targetTable), data);
            exports.add(exportFile);
            logs.add("输出节点[" + label + "]生成 Excel 导出文件：" + exportFile.fileName());
            return new SinkWriteResult(data.size(), 0, "", exports, exportFile.fileName());
        }
        if ("hdfs".equals(sinkKind)) {
            Map<String, String> hdfsConfig = parseConfigToMap(config);
            int rowsWritten = hdfsSinkService.write(hdfsConfig, data);
            String path = hdfsConfig.getOrDefault("path", "");
            logs.add("HDFS sink [" + label + "] wrote " + rowsWritten + " rows to " + path);
            return new SinkWriteResult(rowsWritten, 0, "", exports, path);
        }
        Map<String, Object> jdbcOutputConfig = parseConfigMap(config);
        boolean mysqlUsesSavedConnection = "mysql".equals(sinkKind)
                && jdbcOutputConfig.get("connectionId") != null
                && !String.valueOf(jdbcOutputConfig.get("connectionId")).isBlank();
        if (mysqlUsesSavedConnection
                || "jdbc".equals(sinkKind)
                || "oceanbase".equals(sinkKind)
                || "postgresql".equals(sinkKind)
                || "oracle".equals(sinkKind)) {
            Map<String, Object> configMap = jdbcOutputConfig;
            if (!configMap.containsKey("table") && !targetTable.isBlank()) {
                configMap.put("table", targetTable);
            }
            int rowsWritten = jdbcDataSourceService.write(configMap, data);
            String table = String.valueOf(configMap.getOrDefault("table", targetTable));
            logs.add("输出节点[" + label + "]写入 JDBC 表 " + table + " 共 " + rowsWritten + " 条");
            return new SinkWriteResult(rowsWritten, 0, table, exports, "");
        }
        DynamicTableService.DedupWriteResult writeResult = tableService.writeDataDedup(targetTable, data, incremental.dedupKeys());
        int rowsWritten = writeResult.rowsWritten();
        int duplicateSkipped = writeResult.duplicateSkipped();
        logs.add("输出节点[" + label + "]写入结果表 " + targetTable + " 共 " + rowsWritten + " 条");
        if (duplicateSkipped > 0) {
            logs.add("输出节点[" + label + "]按去重字段跳过重复 " + duplicateSkipped + " 条");
        }

        if ("redis".equals(sinkKind)) {
            String stream = extractConfigValue(config, "stream");
            int published = redisSinkService.write(stream, data);
            logs.add("输出节点[" + label + "]写入 Redis Stream " + (stream.isBlank() ? "a22:task-output" : stream) + " 共 " + published + " 条");
        } else if ("kafka".equals(sinkKind)) {
            String topic = extractConfigValue(config, "topic");
            String keyField = extractConfigValue(config, "keyField");
            int published = kafkaSinkService.write(topic, keyField, data);
            logs.add("输出节点[" + label + "]写入 Kafka Topic " + topic + " 共 " + published + " 条");
        }
        String exportFileName = exports.isEmpty() ? "" : exports.get(exports.size() - 1).fileName();
        return new SinkWriteResult(rowsWritten, duplicateSkipped, targetTable, exports, exportFileName);
    }

    private String sanitizeTableSuffix(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
        return cleaned.isBlank() ? String.valueOf(System.currentTimeMillis()) : cleaned;
    }

    private record Edge(String id, String source, String target, String label, String condition) {}

    private record DagGraph(Map<String, JsonNode> nodes, Map<String, List<Edge>> next, Map<String, Integer> indegree) {
        int edgeCount() {
            return next.values().stream().mapToInt(List::size).sum();
        }
    }

    private record FilterBranch(List<Map<String, Object>> passed, List<Map<String, Object>> rejected) {}

    private record SinkWriteResult(int rowsWritten, int duplicateSkipped, String targetTable, List<ExportFile> exports, String exportFileName) {}

    private static class IncrementalState {
        private final IncrementalExecutionContext context;
        private String sourceTable = "";
        private List<Object> sourceIds = List.of();
        private int inputCount = 0;
        private String lastCursorAfter = "";

        private IncrementalState(IncrementalExecutionContext context) {
            this.context = context;
        }
    }

    private List<Object> extractIds(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) return List.of();
        return data.stream()
                .map(row -> row.get("id"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .toList();
    }

    private String maxCursor(List<Map<String, Object>> data, String cursorField, String fallback) {
        if (data == null || data.isEmpty() || cursorField == null || cursorField.isBlank()) return valueOrDefault(fallback, "");
        String max = valueOrDefault(fallback, "");
        for (Map<String, Object> row : data) {
            Object value = row.get(cursorField);
            if (value == null) continue;
            String text = String.valueOf(value);
            if (max.isBlank() || compareCursor(text, max) > 0) {
                max = text;
            }
        }
        return max;
    }

    private int compareCursor(String left, String right) {
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right));
        } catch (Exception ignored) {
        }
        Instant leftInstant = parseCursorInstant(left);
        Instant rightInstant = parseCursorInstant(right);
        if (leftInstant != null && rightInstant != null) {
            return leftInstant.compareTo(rightInstant);
        }
        return left.compareTo(right);
    }

    private Instant parseCursorInstant(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(text.replace(" ", "T")).atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(text).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private List<Map<String, Object>> readFromUploadedFile(Long fileId) throws Exception {
        StoredFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("文件不存在：" + fileId));
        String json = file.getContentJson();
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("上传文件内容为空或解析结果为空，请重新上传 CSV/Excel/JSON/XML 测试数据。");
        }
        // 尝试直接解析为数组
        try {
            JsonNode content = mapper.readTree(json);
            if (content.isArray()) {
                return mapper.convertValue(content, new TypeReference<>() {});
            }
            // H2 JSON列可能将数组作为字符串存储
            if (content.isTextual()) {
                JsonNode inner = mapper.readTree(content.asText());
                if (inner.isArray()) {
                    return mapper.convertValue(inner, new TypeReference<>() {});
                }
            }
        } catch (Exception ignored) {}
        // 最后尝试直接反序列化
        return mapper.readValue(json, new TypeReference<>() {});
    }
    private List<Map<String, Object>> readFromSourceNode(JsonNode source) {
        return readFromSourceNode(source, new IncrementalState(IncrementalExecutionContext.disabled()));
    }

    private List<Map<String, Object>> readFromSourceNode(JsonNode source, IncrementalState incrementalState) {
        String kind = source.path("data").path("kind").asText();
        String config = source.path("data").path("config").asText("");
        if ("mysql".equals(kind)) {
            String connectionId = extractConfigValue(config, "connectionId");
            if (!connectionId.isBlank()) {
                List<Map<String, Object>> data = jdbcDataSourceService.read(parseConfigMap(config));
                incrementalState.inputCount = data.size();
                return data;
            }
            String table = extractConfigValue(config, "table");
            if (table.isEmpty()) table = extractConfigValue(config, "sourceTable");
            if (table.isEmpty()) throw new IllegalArgumentException("MySQL 输入节点需要配置 table");
            int limit = parseLimit(extractConfigValue(config, "limit"));
            IncrementalExecutionContext incremental = incrementalState.context;
            List<Map<String, Object>> data;
            if (incremental.statusMode() && !incremental.processedField().isBlank()) {
                data = tableService.readDataByStatus(table, incremental.processedField(), incremental.unprocessedValue(), limit);
                incrementalState.sourceTable = table;
                incrementalState.sourceIds = extractIds(data);
            } else if (incremental.cursorMode() && !incremental.cursorField().isBlank()) {
                data = tableService.readDataByCursor(table, incremental.cursorField(), incremental.lastCursorValue(), limit);
                incrementalState.sourceTable = table;
                incrementalState.lastCursorAfter = maxCursor(data, incremental.cursorField(), incremental.lastCursorValue());
            } else {
                data = tableService.readData(table, limit);
            }
            incrementalState.inputCount = data.size();
            return data;
        }
        if ("kafka".equals(kind)) {
            String topic = extractConfigValue(config, "topic");
            return kafkaSourceService.read(topic, parseLimit(extractConfigValue(config, "limit")), parseTimeout(extractConfigValue(config, "timeoutMs")));
        }
        if ("redis".equals(kind)) {
            return redisSourceService.read(parseConfigMap(config));
        }
        if ("hdfs".equals(kind)) {
            return hdfsSourceService.read(parseConfigToMap(config));
        }
        if ("jdbc".equals(kind) || "oceanbase".equals(kind) || "postgresql".equals(kind) || "oracle".equals(kind)) {
            return jdbcDataSourceService.read(parseConfigMap(config));
        }

        List<Map<String, Object>> inlineData = readInlineData(config);
        if (!inlineData.isEmpty()) {
            return inlineData;
        }

        String fileIdStr = extractConfigValue(config, "fileId");
        if (!fileIdStr.isEmpty()) {
            try {
                return readFromUploadedFile(Long.parseLong(fileIdStr));
            } catch (Exception ignored) {
                return List.of();
            }
        }

        List<StoredFile> files = fileRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        if (files.isEmpty()) return List.of();
        try {
            return readFromUploadedFile(files.get(0).getId());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> readInlineData(String config) {
        if (config == null || config.isBlank()) return List.of();
        try {
            JsonNode cfg = mapper.readTree(config);
            JsonNode data = firstExisting(cfg, "data", "records", "rows", "json", "content");
            if (data == null || data.isMissingNode() || data.isNull()) return List.of();
            if (data.isTextual()) data = mapper.readTree(data.asText());
            if (data.isArray()) return mapper.convertValue(data, new TypeReference<>() {});
            if (data.isObject()) return List.of(mapper.convertValue(data, new TypeReference<>() {}));
        } catch (Exception ignored) {
            return List.of();
        }
        return List.of();
    }

    private JsonNode firstExisting(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key)) return node.get(key);
        }
        return null;
    }

    private List<Map<String, Object>> applyTransform(String kind, String config, List<Map<String, Object>> data) {
               try {
            return transformPluginRegistry.get(kind)
                    .transform(new com.a22.platform.engine.transform.TransformContext(kind, config, data))
                   .getRecords();
        } catch (IllegalArgumentException ignored) {
            return data;
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception checkedException) {
            throw new RuntimeException(checkedException);
        }
    }

    List<Map<String, Object>> applyConcat(String config, List<Map<String, Object>> data) {
        if (config == null || config.isBlank()) return data;
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = mapper.readTree(config);
        } catch (Exception e) {
            // 兼容旧格式 colA/colB/separator
            String colA = valueOrDefault(extractConfigValue(config, "colA"), "A");
            String colB = valueOrDefault(extractConfigValue(config, "colB"), "B");
            String target = valueOrDefault(extractConfigValue(config, "target"), "C");
            String separator = extractConfigValue(config, "separator");
            return data.stream().map(row -> {
                Map<String, Object> result = new LinkedHashMap<>(row);
                String left = String.valueOf(row.getOrDefault(colA, ""));
                String right = String.valueOf(row.getOrDefault(colB, ""));
                result.put(target, left + separator + right);
                return result;
            }).toList();
        }
        String target = node.path("target").asText("concat_result");
        String separator = node.path("separator").asText("");
        boolean skipBlank = node.path("skipBlank").asBoolean(true);
        String template = node.path("template").asText("");
        List<String> fields = new ArrayList<>();
        if (node.has("fields") && node.path("fields").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node.path("fields")) {
                fields.add(item.asText(""));
            }
        }
        if (!template.isBlank()) {
            return data.stream().map(row -> {
                Map<String, Object> result = new LinkedHashMap<>(row);
                String value = template;
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    value = value.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                }
                result.put(target, value);
                return result;
            }).toList();
        }
        return data.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>(row);
            List<String> parts = new ArrayList<>();
            for (String field : fields) {
                Object raw = row.get(field);
                String text = raw == null ? "" : String.valueOf(raw);
                if (skipBlank && text.isBlank()) continue;
                parts.add(text);
            }
            result.put(target, String.join(separator, parts));
            return result;
        }).toList();
    }

    List<Map<String, Object>> applyFilter(String config, List<Map<String, Object>> data) {
        if (config == null || config.isBlank()) return data;
        try {
            JsonNode node = mapper.readTree(config);
            return data.stream().filter(row -> evaluateFilterNode(node, row)).toList();
        } catch (Exception e) {
            String field = extractConfigValue(config, "field");
            String value = extractConfigValue(config, "value");
            if (field.isEmpty()) return data;
            return data.stream().filter(row -> !value.equals(String.valueOf(row.getOrDefault(field, "")))).toList();
    }
    }

    private boolean evaluateFilterNode(JsonNode node, Map<String, Object> row) {
        if (node == null || node.isMissingNode() || node.isNull()) return true;
        if (node.has("conditions") && node.path("conditions").isArray()) {
            String logic = node.path("logic").asText("AND").trim().toUpperCase(Locale.ROOT);
            boolean orLogic = "OR".equals(logic);
            boolean matched = !orLogic;
            for (JsonNode condition : node.path("conditions")) {
                boolean current = evaluateFilterNode(applyFilterDefaults(condition, node), row);
                if (orLogic && current) return true;
                if (!orLogic && !current) return false;
                matched = current;
            }
            return matched;
        }
        return evaluateSingleCondition(node, row);
    }

    private JsonNode applyFilterDefaults(JsonNode condition, JsonNode parent) {
        if (!condition.isObject()) return condition;
        com.fasterxml.jackson.databind.node.ObjectNode merged = condition.deepCopy();
        if (!merged.has("ignoreCase") && parent.has("ignoreCase")) merged.set("ignoreCase", parent.get("ignoreCase"));
        if (!merged.has("dateFormat") && parent.has("dateFormat")) merged.set("dateFormat", parent.get("dateFormat"));
        return merged;
    }

    private boolean evaluateSingleCondition(JsonNode node, Map<String, Object> row) {
        String field = node.path("field").asText("");
        String operator = node.path("operator").asText(node.path("op").asText("=")).trim().toLowerCase(Locale.ROOT);
        String value = node.path("value").asText("");
        boolean ignoreCase = node.path("ignoreCase").asBoolean(false);
        String dateFormat = node.path("dateFormat").asText("yyyy-MM-dd");
        List<String> keywords = extractTextList(node, "keywords");
        if (keywords.isEmpty()) keywords = extractTextList(node, "values");
        if (keywords.isEmpty() && node.hasNonNull("keyword")) keywords = List.of(node.path("keyword").asText(""));

        if (field.isEmpty() && !keywords.isEmpty()) {
            List<String> searchWords = keywords;
            return row.values().stream().anyMatch(cell -> matchKeywords(String.valueOf(cell), searchWords, ignoreCase));
        }
        if (field.isEmpty()) return true;

        Object fieldValue = row.get(field);
        String fieldStr = fieldValue == null ? "" : String.valueOf(fieldValue);
        String expected = ignoreCase ? value.toLowerCase(Locale.ROOT) : value;
        String actual = ignoreCase ? fieldStr.toLowerCase(Locale.ROOT) : fieldStr;
        return switch (operator) {
            case "=", "eq" -> expected.equals(actual);
            case "!=", "<>", "ne" -> !expected.equals(actual);
            case ">", ">=", "<", "<=" -> compareNumeric(fieldStr, value, operator);
            case "between", "range" -> compareBetween(fieldStr, node.path("min").asText(node.path("start").asText("")), node.path("max").asText(node.path("end").asText("")));
            case "not_between", "not_range" -> !compareBetween(fieldStr, node.path("min").asText(node.path("start").asText("")), node.path("max").asText(node.path("end").asText(""))); 
            case "in" -> containsValue(keywords, fieldStr, ignoreCase);
            case "not_in" -> !containsValue(keywords, fieldStr, ignoreCase);
            case "contains" -> containsText(fieldStr, value, ignoreCase);
            case "not_contains" -> !containsText(fieldStr, value, ignoreCase);
            case "contains_all" -> keywords.stream().allMatch(keyword -> containsText(fieldStr, keyword, ignoreCase));
            case "contains_any" -> keywords.isEmpty() || keywords.stream().anyMatch(keyword -> containsText(fieldStr, keyword, ignoreCase));
            case "starts_with" -> startsWithText(fieldStr, value, ignoreCase);
            case "ends_with" -> endsWithText(fieldStr, value, ignoreCase);
            case "regex" -> regexMatch(fieldStr, value, ignoreCase);
            case "date_before" -> compareDate(fieldStr, value, dateFormat) < 0;
            case "date_after" -> compareDate(fieldStr, value, dateFormat) > 0;
            case "date_between" -> {
                String start = node.path("start").asText(node.path("min").asText(""));
                String end = node.path("end").asText(node.path("max").asText(""));
                int startDate = start.isBlank() ? 1 : compareDate(fieldStr, start, dateFormat);
                int endDate = end.isBlank() ? 1 : compareDate(end, fieldStr, dateFormat);
                yield startDate >= 0 && endDate >= 0;
            }
            case "date_equals" -> compareDate(fieldStr, value, dateFormat) == 0;
            default -> true;
        };
    }

    private List<String> extractTextList(JsonNode node, String key) {
        List<String> values = new ArrayList<>();
        JsonNode list = node.path(key);
        if (list.isArray()) {
            for (JsonNode item : list) {
                String text = item.asText("");
                if (!text.isBlank()) values.add(text);
            }
        } else if (!list.isMissingNode() && !list.asText("").isBlank()) {
            values.add(list.asText(""));
        }
        return values;
    }

    private boolean containsValue(List<String> values, String fieldValue, boolean ignoreCase) {
        String actual = ignoreCase ? fieldValue.toLowerCase(Locale.ROOT) : fieldValue;
        return values.stream().map(value -> ignoreCase ? value.toLowerCase(Locale.ROOT) : value).anyMatch(actual::equals);
    }

    private boolean compareBetween(String value, String min, String max) {
        if (min != null && !min.isBlank() && !compareNumeric(value, min, ">=")) return false;
        return max == null || max.isBlank() || compareNumeric(value, max, "<=");
    }
    private boolean compareNumeric(String a, String b, String operator) {
        try {
            double da = Double.parseDouble(a);
            double db = Double.parseDouble(b);
            int result = Double.compare(da, db);
            return switch (operator) {
                case ">" -> result > 0;
                case ">=" -> result >= 0;
                case "<" -> result < 0;
                case "<=" -> result <= 0;
                case "=" -> result == 0;
                case "!=" -> result != 0;
                default -> false;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int compareNumeric(String a, String b) {
        try {
            double da = Double.parseDouble(a);
            double db = Double.parseDouble(b);
            return Double.compare(da, db);
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }


    private boolean matchKeywords(String text, java.util.List<String> keywords, boolean ignoreCase) {
        String source = ignoreCase && text != null ? text.toLowerCase(java.util.Locale.ROOT) : text;
        for (String keyword : keywords) {
            if (ignoreCase && keyword != null) keyword = keyword.toLowerCase(java.util.Locale.ROOT);
            if (source == null || !source.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsText(String text, String keyword, boolean ignoreCase) {
        if (text == null || keyword == null) return false;
        return ignoreCase ? text.toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT)) : text.contains(keyword);
    }

    private boolean startsWithText(String text, String prefix, boolean ignoreCase) {
        if (text == null || prefix == null) return false;
        return ignoreCase ? text.toLowerCase(java.util.Locale.ROOT).startsWith(prefix.toLowerCase(java.util.Locale.ROOT)) : text.startsWith(prefix);
    }

    private boolean endsWithText(String text, String suffix, boolean ignoreCase) {
        if (text == null || suffix == null) return false;
        return ignoreCase ? text.toLowerCase(java.util.Locale.ROOT).endsWith(suffix.toLowerCase(java.util.Locale.ROOT)) : text.endsWith(suffix);
    }

    private boolean regexMatch(String text, String pattern, boolean ignoreCase) {
        if (text == null || pattern == null || pattern.isBlank()) return false;
        try {
            int flags = ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0;
            return java.util.regex.Pattern.compile(pattern, flags).matcher(text).find();
        } catch (java.util.regex.PatternSyntaxException e) {
            return false;
        }
    }

    private int compareDate(String left, String right, String dateFormat) {
        try {
            java.time.LocalDate leftDate = parseLocalDateFormat(left, dateFormat);
            java.time.LocalDate rightDate = parseLocalDateFormat(right, dateFormat);
            return leftDate.compareTo(rightDate);
        } catch (Exception ignored) {
            return left.compareTo(right);
        }
    }

    private java.time.LocalDate parseLocalDateFormat(String value, String dateFormat) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("empty date");
        String trimmed = value.trim();
        if (trimmed.length() >= 10 && trimmed.charAt(4) == '-' && trimmed.charAt(7) == '-') {
            return java.time.LocalDate.parse(trimmed.substring(0, 10));
        }
        if ("yyyy-MM-dd".equals(dateFormat)) {
            return java.time.LocalDate.parse(trimmed);
        }
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern(dateFormat);
        return java.time.LocalDate.parse(trimmed, formatter);
    }
    List<Map<String, Object>> applyDedup(String config, List<Map<String, Object>> data) {
        if (config == null || config.isBlank()) return data.stream().distinct().toList();
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = mapper.readTree(config);
        } catch (Exception e) {
            String fallbackField = extractConfigValue(config, "field");
            if (fallbackField.isEmpty()) return data.stream().distinct().toList();
            Set<Object> seen = new HashSet<>();
            return data.stream().filter(row -> seen.add(row.get(fallbackField))).toList();
        }
        String keep = node.path("keep").asText("first");
        List<String> orderFields = new ArrayList<>();
        if (node.has("orderFields") && node.path("orderFields").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node.path("orderFields")) {
                String f = item.asText("");
                if (!f.isBlank()) orderFields.add(f);
            }
        }
        List<String> fields = new ArrayList<>();
        if (node.has("fields") && node.path("fields").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node.path("fields")) {
                String f = item.asText("");
                if (!f.isBlank()) fields.add(f);
            }
        } else {
            String single = node.path("field").asText("");
            if (!single.isBlank()) fields.add(single);
        }
        if (fields.isEmpty()) {
            if (orderFields.isEmpty()) return data.stream().distinct().toList();
            List<Map<String, Object>> ordered = new ArrayList<>(data);
            ordered.sort((a, b) -> compareByFields(a, b, orderFields, "last".equalsIgnoreCase(keep)));
            return new ArrayList<>(new LinkedHashSet<>(ordered));
        }
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : data) {
            String key = fields.stream().map(f -> String.valueOf(row.getOrDefault(f, ""))).collect(java.util.stream.Collectors.joining("\u001F"));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> result = new ArrayList<>(groups.size());
        for (List<Map<String, Object>> group : groups.values()) {
            if (orderFields.isEmpty()) {
                result.add("last".equalsIgnoreCase(keep) ? group.get(group.size() - 1) : group.get(0));
            } else {
                List<Map<String, Object>> sorted = new ArrayList<>(group);
                sorted.sort((a, b) -> compareByFields(a, b, orderFields, "last".equalsIgnoreCase(keep)));
                result.add(sorted.get(0));
            }
        }
        return result;
    }

    private int compareByFields(Map<String, Object> a, Map<String, Object> b, List<String> orderFields, boolean desc) {
        for (String field : orderFields) {
            Object va = a.get(field);
            Object vb = b.get(field);
            int cmp = compareObjects(va, vb);
            if (cmp != 0) return desc ? -cmp : cmp;
        }
        return 0;
    }

    private int compareObjects(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
    List<Map<String, Object>> applyFieldMap(String config, List<Map<String, Object>> data) {
        if (config == null || config.isBlank()) return data;
        Map<String, String> mapping = new LinkedHashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(config);
            if (node.has("mapping") && node.path("mapping").isObject()) {
                node.path("mapping").fields().forEachRemaining(entry -> mapping.put(entry.getKey(), entry.getValue().asText()));
            }
        } catch (Exception e) {
            for (String pair : config.split(",")) {
                String[] parts = pair.trim().split(":", 2);
                if (parts.length == 2) mapping.put(parts[0].trim(), parts[1].trim());
            }
        }
        if (mapping.isEmpty()) return data;
        return data.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            row.forEach((key, value) -> result.put(mapping.getOrDefault(key, key), value));
            return result;
        }).toList();
    }

    List<Map<String, Object>> applyTypeConvert(String config, List<Map<String, Object>> data) {
        if (config == null || config.isBlank()) return data;
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = mapper.readTree(config);
        } catch (Exception e) {
            String field = extractConfigValue(config, "field");
            String target = valueOrDefault(extractConfigValue(config, "target"), field);
            String targetType = extractConfigValue(config, "targetType");
            String onError = valueOrDefault(extractConfigValue(config, "onError"), "errorField");
            String defaultValue = extractConfigValue(config, "defaultValue");
            String errorField = valueOrDefault(extractConfigValue(config, "errorField"), "_convert_errors");
            if (field.isEmpty() || targetType.isEmpty()) return data;
            return applySimpleTypeConvert(field, target, targetType, null, null, onError, defaultValue, errorField, data);
        }
        if (node.isObject() && !node.has("field") && !node.has("rules")) {
            List<Map<String, Object>> result = data;
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = fields.next();
                result = applySimpleTypeConvert(entry.getKey(), entry.getKey(), entry.getValue().asText("STRING"), null, null,
                        "errorField", null, "_convert_errors", result);
            }
            return result;
        }
        if (node.has("rules") && node.path("rules").isArray()) {
            List<Map<String, Object>> result = data;
            String globalOnError = node.path("onError").asText("errorField");
            String globalDefaultValue = node.path("defaultValue").asText(null);
            String globalErrorField = node.path("errorField").asText("_convert_errors");
            for (com.fasterxml.jackson.databind.JsonNode rule : node.path("rules")) {
                String field = rule.path("field").asText("");
                String target = rule.path("target").asText(field);
                String targetType = rule.path("targetType").asText("STRING");
                String inputFormat = rule.path("inputFormat").asText("");
                String outputFormat = rule.path("outputFormat").asText("");
                String onError = rule.path("onError").asText(globalOnError);
                String defaultValue = rule.has("defaultValue") ? rule.path("defaultValue").asText(null) : globalDefaultValue;
                String errorField = rule.path("errorField").asText(globalErrorField);
                if (!field.isBlank() && !targetType.isBlank()) {
                    result = applySimpleTypeConvert(field, target, targetType, inputFormat, outputFormat, onError, defaultValue, errorField, result);
                }
            }
            return result;
        }
        String field = node.path("field").asText("");
        String target = node.path("target").asText(field);
        String targetType = node.path("targetType").asText("");
        String inputFormat = node.path("inputFormat").asText("");
        String outputFormat = node.path("outputFormat").asText("");
        String onError = node.path("onError").asText("errorField");
        String defaultValue = node.has("defaultValue") ? node.path("defaultValue").asText(null) : null;
        String errorField = node.path("errorField").asText("_convert_errors");
        if (field.isEmpty() || targetType.isEmpty()) return data;
        return applySimpleTypeConvert(field, target, targetType, inputFormat, outputFormat, onError, defaultValue, errorField, data);
    }

    private List<Map<String, Object>> applySimpleTypeConvert(String field,
                                                             String target,
                                                             String targetType,
                                                             String inputFormat,
                                                             String outputFormat,
                                                             String onError,
                                                             String defaultValue,
                                                             String errorField,
                                                             List<Map<String, Object>> data) {
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Map<String, Object> row : data) {
            Map<String, Object> result = new LinkedHashMap<>(row);
            Object value = row.get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                converted.add(result);
                continue;
            }
            try {
                result.put(target, convertValue(value, targetType, inputFormat, outputFormat));
                converted.add(result);
            } catch (IllegalArgumentException exception) {
                String strategy = normalizeOnError(onError);
                if ("skip".equals(strategy)) {
                    continue;
                }
                if ("default".equals(strategy)) {
                    Object fallback = convertDefaultValue(defaultValue, targetType, inputFormat, outputFormat);
                    result.put(target, fallback);
                    converted.add(result);
                    continue;
                }
                appendConvertError(result, valueOrDefault(errorField, "_convert_errors"), field, value, targetType, exception.getMessage());
                converted.add(result);
            }
        }
        return converted;
    }

    private String normalizeOnError(String onError) {
        if (onError == null || onError.isBlank()) return "errorField";
        return switch (onError.trim().toLowerCase(Locale.ROOT)) {
            case "skip", "drop", "ignore-row" -> "skip";
            case "default", "default_value", "use-default" -> "default";
            case "errorfield", "error_field", "mark", "keep", "record" -> "errorField";
            default -> "errorField";
        };
    }

    private Object convertDefaultValue(String defaultValue, String targetType, String inputFormat, String outputFormat) {
        if (defaultValue == null || defaultValue.isBlank()) return null;
        try {
            return convertValue(defaultValue, targetType, inputFormat, outputFormat);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private void appendConvertError(Map<String, Object> row, String errorField, String field, Object value, String targetType, String message) {
        String previous = row.get(errorField) == null ? "" : String.valueOf(row.get(errorField));
        String current = "字段[" + field + "]的值[" + value + "]无法转换为[" + targetType + "]: " + valueOrDefault(message, "格式不匹配");
        row.put(errorField, previous.isBlank() ? current : previous + "; " + current);
    }

    private Object convertValue(Object value, String targetType, String inputFormat, String outputFormat) {
        String text = String.valueOf(value).trim();
        try {
            return switch (targetType.toUpperCase()) {
                case "STRING" -> text;
                case "INTEGER", "INT" -> Integer.valueOf(text);
                case "LONG" -> Long.valueOf(text);
                case "DOUBLE" -> Double.valueOf(text);
                case "DECIMAL" -> new BigDecimal(text);
                case "BOOLEAN" -> switch (text.toLowerCase(java.util.Locale.ROOT)) {
                    case "true", "1", "yes", "y" -> true;
                    case "false", "0", "no", "n" -> false;
                    default -> throw new IllegalArgumentException("invalid boolean value: " + text);
                };
                case "DATE" -> parseLocalDate(text, inputFormat).toString();
                case "DATETIME", "TIMESTAMP" -> {
                    java.time.LocalDateTime dateTime = parseLocalDateTime(text, inputFormat);
                    java.time.format.DateTimeFormatter formatter = outputFormat == null || outputFormat.isBlank()
                            ? java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                            : java.time.format.DateTimeFormatter.ofPattern(outputFormat);
                    yield dateTime.format(formatter);
                }
                default -> throw new IllegalArgumentException("unsupported target type: " + targetType);
            };
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid value " + text + " for type " + targetType, exception);
        }
    }

    private Object convertValue(Object value, String targetType) {
        return convertValue(value, targetType, null, null);
    }

    private java.time.LocalDate parseLocalDate(String value, String inputFormat) {
        String text = value.trim();
        if (text.length() >= 10 && text.charAt(4) == '-' && text.charAt(7) == '-') {
            return java.time.LocalDate.parse(text.substring(0, 10));
        }
        if (inputFormat == null || inputFormat.isBlank() || "yyyy-MM-dd".equals(inputFormat)) {
            return java.time.LocalDate.parse(text);
        }
        return java.time.LocalDate.parse(text, java.time.format.DateTimeFormatter.ofPattern(inputFormat));
    }

    private java.time.LocalDateTime parseLocalDateTime(String value, String inputFormat) {
        String text = value.trim();
        if (text.chars().allMatch(Character::isDigit)) {
            long millis = Long.parseLong(text);
            if (text.length() >= 13) {
                return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
            }
            return java.time.Instant.ofEpochSecond(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        if (text.length() >= 19 && text.charAt(4) == '-' && text.charAt(7) == '-' && text.charAt(10) == 'T') {
            return java.time.LocalDateTime.parse(text.substring(0, 19));
        }
        if (text.length() >= 19 && text.charAt(4) == '-' && text.charAt(7) == '-' && text.charAt(10) == ' ') {
            return java.time.LocalDateTime.parse(text.substring(0, 19).replace(' ', 'T'));
        }
        if (inputFormat == null || inputFormat.isBlank()) {
            return java.time.LocalDateTime.parse(text);
        }
        return java.time.LocalDateTime.parse(text, java.time.format.DateTimeFormatter.ofPattern(inputFormat));
    }

    List<Map<String, Object>> applyXmlJson(String config, List<Map<String, Object>> data) {
        String field = extractConfigValue(config, "field");
        String target = valueOrDefault(extractConfigValue(config, "target"), field);
        String direction = valueOrDefault(extractConfigValue(config, "direction"), "xml-to-json").toLowerCase(java.util.Locale.ROOT);
        String root = extractConfigValue(config, "root");
        if (field.isEmpty()) return data;
        return data.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>(row);
            Object value = row.get(field);
            if (value == null || String.valueOf(value).isBlank()) return result;
            String converted = switch (direction) {
                case "xml-to-json" -> xmlJsonConverter.xmlToJson(String.valueOf(value));
                case "json-to-xml" -> xmlJsonConverter.jsonToXml(String.valueOf(value), root);
                default -> throw new IllegalArgumentException("unsupported xml/json direction: " + direction);
            };
            result.put(target, converted);
            return result;
        }).toList();
    }
    private String extractTargetTable(String config) {
        String table = extractConfigValue(config, "targetTable");
        return table.isEmpty() ? extractConfigValue(config, "table") : table;
    }

    private String extractOutputFileName(String config, String fallback) {
        return valueOrDefault(extractConfigValue(config, "fileName"), fallback);
    }

    private String outputLocation(String targetTable, String exportFileName) {
        List<String> parts = new ArrayList<>();
        if (targetTable != null && !targetTable.isBlank()) parts.add("结果表：" + targetTable);
        if (exportFileName != null && !exportFileName.isBlank()) parts.add("导出文件：" + exportFileName);
        return String.join("；", parts);
    }

    private String summarizeNodeRule(String kind, String config) {
        if (kind == null || kind.isBlank()) return "";
        if ("filter".equals(kind)) return summarizeFilterRule(config);
        if ("field-map".equals(kind)) return summarizeFieldMapRule(config);
        if ("data-validate".equals(kind)) return summarizeValidateRule(config);
        if ("type-convert".equals(kind)) return summarizeTypeConvertRule(config);
        if ("mask".equals(kind)) return "按配置对手机号、身份证、邮箱、姓名等字段脱敏";
        if ("concat".equals(kind)) return "按配置拼接多个字段";
        if ("dedup".equals(kind)) return "按配置字段去重";
        if ("data-repair".equals(kind)) return "按配置默认值/维表规则补全缺失数据";
        if ("mysql".equals(kind)) return "写入 MySQL 结果表";
        if ("oceanbase".equals(kind)) return "写入 OceanBase 结果表";
        if ("jdbc".equals(kind)) return "写入外部 JDBC 结果表";
        if ("csv".equals(kind)) return "写入动态结果表并导出 CSV";
        if ("excel".equals(kind)) return "写入动态结果表并导出 Excel";
        return "";
    }

    private String summarizeFilterRule(String config) {
        if (config == null || config.isBlank()) return "无过滤条件";
        try {
            JsonNode node = mapper.readTree(config);
            return summarizeFilterNode(node);
        } catch (Exception ignored) {
            String field = extractConfigValue(config, "field");
            String value = extractConfigValue(config, "value");
            return field.isBlank() ? "自定义过滤条件" : field + " 匹配 " + value;
        }
    }

    private String summarizeFilterNode(JsonNode node) {
        if (node.has("conditions") && node.path("conditions").isArray()) {
            String logic = node.path("logic").asText("AND").equalsIgnoreCase("OR") ? " 或 " : " 且 ";
            List<String> parts = new ArrayList<>();
            for (JsonNode condition : node.path("conditions")) {
                parts.add(summarizeFilterNode(condition));
            }
            return String.join(logic, parts);
        }
        String field = node.path("field").asText("全字段");
        String operator = node.path("operator").asText(node.path("op").asText("="));
        return switch (operator) {
            case "=", "eq" -> field + " = " + node.path("value").asText("");
            case "!=", "<>", "ne" -> field + " != " + node.path("value").asText("");
            case ">", ">=", "<", "<=" -> field + " " + operator + " " + node.path("value").asText("");
            case "between", "range" -> field + " 在 " + node.path("min").asText(node.path("start").asText("")) + " 到 " + node.path("max").asText(node.path("end").asText("")) + " 之间";
            case "not_between", "not_range" -> field + " 不在 " + node.path("min").asText(node.path("start").asText("")) + " 到 " + node.path("max").asText(node.path("end").asText("")) + " 之间";
            case "in" -> field + " 属于 " + String.join("/", extractTextList(node, "values"));
            case "not_in" -> field + " 不属于 " + String.join("/", extractTextList(node, "values"));
            case "contains" -> field + " 包含 " + node.path("value").asText("");
            case "not_contains" -> field + " 不包含 " + node.path("value").asText("");
            case "contains_all" -> field + " 同时包含 " + String.join("/", extractTextList(node, "keywords"));
            case "contains_any" -> field + " 包含任一 " + String.join("/", extractTextList(node, "keywords"));
            case "regex" -> field + " 匹配正则 " + node.path("value").asText("");
            case "date_between" -> field + " 日期在 " + node.path("start").asText("") + " 到 " + node.path("end").asText("") + " 之间";
            case "date_before" -> field + " 日期早于 " + node.path("value").asText("");
            case "date_after" -> field + " 日期晚于 " + node.path("value").asText("");
            default -> field + " " + operator + " " + node.path("value").asText("");
        };
    }

    private String summarizeFieldMapRule(String config) {
        if (config == null || config.isBlank()) return "未配置字段映射";
        try {
            JsonNode node = mapper.readTree(config);
            JsonNode mapping = node.has("mapping") ? node.path("mapping") : node.path("mappings");
            if (mapping.isObject()) {
                List<String> parts = new ArrayList<>();
                mapping.fields().forEachRemaining(entry -> parts.add(entry.getKey() + "->" + entry.getValue().asText()));
                return "字段映射：" + String.join("，", parts);
            }
        } catch (Exception ignored) {}
        return "字段映射：" + config;
    }

    private String summarizeValidateRule(String config) {
        if (config == null || config.isBlank()) return "未配置校验规则";
        try {
            JsonNode node = mapper.readTree(config);
            List<String> parts = new ArrayList<>();
            for (JsonNode rule : node.path("rules")) {
                String field = rule.path("field").asText("");
                if (field.isBlank()) continue;
                List<String> ruleParts = new ArrayList<>();
                if (rule.path("required").asBoolean(false)) ruleParts.add("必填");
                if (rule.has("min") || rule.has("max")) ruleParts.add("范围[" + rule.path("min").asText("-∞") + "," + rule.path("max").asText("+∞") + "]");
                if (rule.has("allowed")) ruleParts.add("枚举");
                if (rule.has("regex")) ruleParts.add("正则");
                if (rule.has("dateFormat")) ruleParts.add("日期格式");
                parts.add(field + "(" + String.join("/", ruleParts) + ")");
            }
            return parts.isEmpty() ? "数据校验" : "数据校验：" + String.join("，", parts);
        } catch (Exception ignored) {
            return "数据校验：" + config;
        }
    }

    private String summarizeTypeConvertRule(String config) {
        if (config == null || config.isBlank()) return "未配置类型转换";
        try {
            JsonNode node = mapper.readTree(config);
            List<String> parts = new ArrayList<>();
            JsonNode rules = node.path("rules");
            if (rules.isArray()) {
                for (JsonNode rule : rules) {
                    parts.add(rule.path("field").asText("") + "->" + rule.path("targetType").asText("STRING"));
                }
            } else if (node.has("field")) {
                parts.add(node.path("field").asText("") + "->" + node.path("targetType").asText("STRING"));
            }
            return parts.isEmpty() ? "类型转换" : "类型转换：" + String.join("，", parts);
        } catch (Exception ignored) {
            return "类型转换：" + config;
        }
    }

    private int parseLimit(String value) {
        if (value == null || value.isBlank()) return 1_000;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit 返回", exception);
        }
    }

    private int parseTimeout(String value) {
        if (value == null || value.isBlank()) return 3_000;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("timeoutMs 架后转指为", exception);
        }
    }

    private String extractConfigValue(String config, String key) {
        if (config == null || config.isBlank()) return "";
        try {
            JsonNode node = mapper.readTree(config);
            if (node.has(key)) return node.get(key).asText("");
        } catch (Exception ignored) {
            for (String part : config.split("[,;]")) {
                String[] kv = part.trim().split("[:=]", 2);
                if (kv.length == 2 && kv[0].trim().equals(key)) return kv[1].trim();
            }
        }
        return "";
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfigMap(String config) {
        if (config == null || config.isBlank()) {
            return java.util.Collections.emptyMap();
        }
        try {
            return mapper.readValue(config, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignored) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (String part : config.split("[;]]")) {
                String[] kv = part.trim().split("[:=]", 2);
                if (kv.length == 2) {
                    map.put(kv[0].trim(), kv[1].trim());
                }
            }
            return map;
        }
    }


    @SuppressWarnings("unchecked")
    private Map<String, String> parseConfigToMap(String config) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (config == null || config.isBlank()) return result;
        try {
            JsonNode node = mapper.readTree(config);
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText("")));
        } catch (Exception ignored) {
            for (String part : config.split("[,;]")) {
                String[] kv = part.trim().split("[:=]", 2);
                if (kv.length == 2) result.put(kv[0].trim(), kv[1].trim());
            }
        }
        return result;
    }
}


