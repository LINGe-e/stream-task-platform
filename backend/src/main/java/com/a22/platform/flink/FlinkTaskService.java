package com.a22.platform.flink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FlinkTaskService {
    private static final Logger log = LoggerFactory.getLogger(FlinkTaskService.class);

    private final String restUrl;
    private final String jobJar;
    private final String bootstrapServers;
    private final String sourceTopic;
    private final String flinkMysqlUrl;
    private final String flinkMysqlUser;
    private final String flinkMysqlPassword;
    private final String targetTable;
    private final PipelineConfigGenerator pipelineGenerator;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, FlinkJobSubmission> submittedJobs = new ConcurrentHashMap<>();

    public FlinkTaskService(
            @Value("${app.flink.rest-url:http://localhost:8081}") String restUrl,
            @Value("${app.flink.job-jar:}") String jobJar,
            @Value("${app.flink.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${app.flink.source-topic:a22-transactions}") String sourceTopic,
            @Value("${app.flink.mysql-url:}") String flinkMysqlUrl,
            @Value("${app.flink.mysql-username:root}") String flinkMysqlUser,
            @Value("${app.flink.mysql-password:}") String flinkMysqlPassword,
            @Value("${app.flink.target-table:flink_output}") String targetTable,
            PipelineConfigGenerator pipelineGenerator) {
        this.restUrl = restUrl;
        this.jobJar = jobJar;
        this.bootstrapServers = bootstrapServers;
        this.sourceTopic = sourceTopic;
        this.flinkMysqlUrl = flinkMysqlUrl;
        this.flinkMysqlUser = flinkMysqlUser;
        this.flinkMysqlPassword = flinkMysqlPassword;
        this.targetTable = targetTable;
        this.pipelineGenerator = pipelineGenerator;
    }

    public FlinkJobSubmission submitTask(long taskId, int parallelism) {
        return doSubmit("a22-task-" + taskId, parallelism);
    }

    public FlinkJobSubmission submitGlobalJob(int parallelism) {
        return doSubmit("a22-stream-job", parallelism);
    }

    @SuppressWarnings("unchecked")
    private FlinkJobSubmission doSubmit(String jobName, int parallelism) {
        // 1. 检查 JAR 文件
        File jarFile = new File(jobJar);
        if (!jarFile.exists()) {
            return FlinkJobSubmission.failed(jobName, jobName,
                    "Flink Job JAR 不存在: " + jobJar + "，请先编译 flink-jobs。");
        }

        String localJobId = "flink-" + System.currentTimeMillis();

        try {
            // 2. 优先复用已经上传到 Flink 集群的 JAR，避免重复上传导致作业提交失败
            String jarId = findExistingJar(jobJar);
            if (jarId == null) {
                log.info("正在上传 JAR 到 Flink 集群: {}", restUrl);
                jarId = uploadJar(jarFile);
                log.info("JAR 上传成功, jarId: {}", jarId);
            } else {
                log.info("复用已上传的 Flink JAR: {}", jarId);
            }

            // 3. 提交作业
            String flinkJobId = runJar(jarId, jobName, parallelism, buildKafkaDemoProgramArgs(jobName));
            log.info("作业提交成功, flinkJobId: {}", flinkJobId);

            FlinkJobSubmission submission = new FlinkJobSubmission(
                    localJobId, jobName, "RUNNING", flinkJobId,
                    LocalDateTime.now(), "作业已提交到 Flink 集群");
            submittedJobs.put(localJobId, submission);
            return submission;

        } catch (Exception e) {
            log.error("提交 Flink 作业失败: {}", e.getMessage(), e);
            return FlinkJobSubmission.failed(localJobId, jobName, "提交失败: " + e.getMessage());
        }
    }

    private String uploadJar(File jarFile) {
        String uploadUrl = restUrl + "/jars/upload";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("jarfile", new FileSystemResource(jarFile));

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(uploadUrl, HttpMethod.POST, request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("JAR 上传失败, 状态码: " + response.getStatusCode());
        }

        // 解析返回的 filename 字段获取 jarId
        String filename = (String) response.getBody().get("filename");
        if (filename == null || filename.isBlank()) {
            throw new RuntimeException("JAR 上传返回格式异常: " + response.getBody());
        }

        // filename 格式类似: /flink/jars/abc123.jar，取最后一段作为 jarId
        String[] parts = filename.replace("\\", "/").split("/");
        return parts[parts.length - 1];
    }

    @SuppressWarnings("unchecked")
    private String runJar(String jarId, String jobName, int parallelism) {
        String runUrl = restUrl + "/jars/" + jarId + "/run";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parallelism", parallelism);
        body.put("entryClass", "com.a22.flink.DynamicPipelineJob");
        body.put("programArgs",
                "--bootstrapServers " + bootstrapServers
                + " --sourceTopic " + sourceTopic
                + " --targetTable " + targetTable
                + " --flinkMysqlUrl " + flinkMysqlUrl
                + " --flinkMysqlUser " + flinkMysqlUser
                + " --flinkMysqlPassword " + flinkMysqlPassword);
        body.put("allowNonRestoredState", false);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(runUrl, HttpMethod.POST, request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("作业运行请求失败, 状态码: " + response.getStatusCode());
        }

        String flinkJobId = (String) response.getBody().get("jobid");
        if (flinkJobId == null || flinkJobId.isBlank()) {
            throw new RuntimeException("作业运行返回格式异常: " + response.getBody());
        }

        return flinkJobId;
    }

    /**
     * Flink 的入口 DynamicPipelineJob 强制要求 --config。独立“提交测试作业”
     * 也必须提供完整管道配置，不能再提交一个缺少 config 的空命令。
     */
    private String buildKafkaDemoProgramArgs(String jobName) {
        String config = """
                {"jobName":"%s","source":{"type":"kafka","bootstrapServers":"%s","topic":"%s","groupId":"a22-demo-%d"},"transforms":[{"type":"filter","field":"status","op":"=","value":"PAID"}],"sink":{"type":"kafka","bootstrapServers":"%s","topic":"a22-output"}}
                """.formatted(json(jobName), json(bootstrapServers), json(sourceTopic), System.currentTimeMillis(), json(bootstrapServers));
        return "--config " + Base64.getEncoder().encodeToString(config.getBytes(StandardCharsets.UTF_8));
    }

    private String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 带自定义参数的 runJar 重载 */
    private String runJar(String jarId, String jobName, int parallelism, String programArgs) {
        String runUrl = restUrl + "/jars/" + jarId + "/run";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parallelism", parallelism);
        body.put("entryClass", "com.a22.flink.DynamicPipelineJob");
        body.put("programArgs", programArgs);
        body.put("allowNonRestoredState", false);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response = restTemplate.exchange(runUrl, HttpMethod.POST, request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("作业运行请求失败, 状态码: " + response.getStatusCode());
        }

        String flinkJobId = (String) response.getBody().get("jobid");
        if (flinkJobId == null || flinkJobId.isBlank()) {
            throw new RuntimeException("作业运行返回格式异常: " + response.getBody());
        }

        return flinkJobId;
    }

    /**
     * 提交动态管道作业：将画布 graph 翻译为管道配置，提交通用 Flink 作业。
     * 替代原来硬编码的 Kafka→MySQL 作业，支持任意 source→transform→sink 编排。
     */
    public FlinkJobSubmission submitDynamicPipeline(long taskId, String graphJson, String sourceTable, String sinkTable) {
        String jobName = "a22-task-" + taskId;
        String localJobId = "flink-" + System.currentTimeMillis();

        File jarFile = new File(jobJar);
        if (!jarFile.exists()) {
            return FlinkJobSubmission.failed(localJobId, jobName,
                    "Flink Job JAR 不存在: " + jobJar + "，请先编译 flink-jobs（mvn package）。");
        }

        try {
            // 1. 将画布 graph 翻译为管道配置
            String configJson = pipelineGenerator.generate(graphJson, sourceTable, sinkTable);
            String configBase64 = Base64.getEncoder().encodeToString(configJson.getBytes(StandardCharsets.UTF_8));
            String programArgs = "--config " + configBase64;

            log.info("提交动态管道作业: {}, 配置大小: {} bytes", jobName, configJson.length());

            // 2. 优先复用已经上传到 Flink 集群的 JAR
            String jarId = findExistingJar(jobJar);
            if (jarId == null) {
                jarId = uploadJar(jarFile);
                log.info("JAR ok: {}", jarId);
            } else {
                log.info("existing JAR: {}", jarId);
            }


            // 3. 提交作业（带管道配置参数）
            String flinkJobId = runJar(jarId, jobName, 1, programArgs);
            log.info("动态管道作业提交成功, flinkJobId: {}", flinkJobId);

            FlinkJobSubmission submission = new FlinkJobSubmission(
                    localJobId, jobName, "RUNNING", flinkJobId,
                    LocalDateTime.now(), "动态管道作业已提交到 Flink 集群");
            submittedJobs.put(localJobId, submission);
            return submission;

        } catch (Exception e) {
            log.error("提交动态管道作业失败: {}", e.getMessage(), e);
            return FlinkJobSubmission.failed(localJobId, jobName, "提交失败: " + e.getMessage());
        }
    }

    public FlinkJobSubmission getJobDetail(String jobId) {
        FlinkJobSubmission cached = submittedJobs.get(jobId);
        if (cached == null) {
            return new FlinkJobSubmission(jobId, jobId, "NOT_FOUND", null, null, "作业未找到");
        }

        // 从 Flink 集群查询最新状态
        if (cached.flinkJobId() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> detail = restTemplate.getForObject(
                        restUrl + "/jobs/" + cached.flinkJobId(), Map.class);
                if (detail != null) {
                    String state = (String) detail.get("state");
                    String status = switch (state != null ? state : "") {
                        case "RUNNING" -> "RUNNING";
                        case "FINISHED" -> "FINISHED";
                        case "CANCELED" -> "CANCELED";
                        case "FAILED" -> "FAILED";
                        case "RESTARTING" -> "RESTARTING";
                        default -> state;
                    };
                    return new FlinkJobSubmission(
                            cached.jobId(), cached.jobName(), status, cached.flinkJobId(),
                            cached.submittedAt(), "Flink 状态: " + state);
                }
            } catch (Exception e) {
                log.warn("查询 Flink 作业状态失败: {}", e.getMessage());
            }
        }

        return cached;
    }

    public String getJobStatus(String jobId) {
        FlinkJobSubmission detail = getJobDetail(jobId);
        return detail.status();
    }

    public Map<String, Object> cancelJob(String jobId) {
        Map<String, Object> result = new LinkedHashMap<>();
        FlinkJobSubmission cached = submittedJobs.get(jobId);

        if (cached == null || cached.flinkJobId() == null) {
            result.put("jobId", jobId);
            result.put("error", "作业未找到");
            return result;
        }

        try {
            restTemplate.patchForObject(restUrl + "/jobs/" + cached.flinkJobId(), null, Map.class);
            result.put("jobId", jobId);
            result.put("flinkJobId", cached.flinkJobId());
            result.put("status", "CANCELLED");
            result.put("message", "取消请求已发送到 Flink 集群");

            // 更新缓存
            submittedJobs.put(jobId, new FlinkJobSubmission(
                    cached.jobId(), cached.jobName(), "CANCELLED", cached.flinkJobId(),
                    cached.submittedAt(), "已取消"));

        } catch (Exception e) {
            result.put("jobId", jobId);
            result.put("error", "取消失败: " + e.getMessage());
        }

        return result;
    }


    /** findExistingJar */
    private String findExistingJar(String localJarPath) {
        try {
            String jarName = new File(localJarPath).getName();
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> resp = restTemplate.getForEntity(restUrl + "/jars", Map.class);
            if (resp.getBody() != null) {
                @SuppressWarnings("unchecked")
                var files = (java.util.List<Map<String, Object>>) resp.getBody().get("files");
                if (files != null) {
                    for (Map<String, Object> f : files) {
                        if (jarName.equals(f.get("name"))) return (String) f.get("id");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("findExistingJar failed: {}", e.getMessage());
        }
        return null;
    }
}


