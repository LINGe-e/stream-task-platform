package com.a22.platform.flink;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/flink")
@CrossOrigin
public class FlinkController {
    private final FlinkTaskService flinkTaskService;
    private final String restUrl;

    public FlinkController(FlinkTaskService flinkTaskService,
                           @Value("${app.flink.rest-url:http://localhost:8081}") String restUrl) {
        this.flinkTaskService = flinkTaskService;
        this.restUrl = restUrl;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            RestTemplate restTemplate = new RestTemplate();
            String overview = restTemplate.getForObject(restUrl + "/overview", String.class);
            result.put("status", "UP");
            result.put("available", true);
            result.put("jobManagerUrl", restUrl);
            result.put("flink", overview);
            if (overview != null && overview.contains("flink-version")) {
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(overview, Map.class);
                result.put("flinkVersion", parsed.get("flink-version"));
            }
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("available", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobs() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            Map<String, Object> overview = restTemplate.getForObject(restUrl + "/jobs/overview", Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "UP");
            result.put("jobManagerUrl", restUrl);
            result.put("overview", overview);
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "DOWN");
            result.put("error", e.getMessage());
            return result;
        }
    }

    @PostMapping("/jobs")
    public FlinkJobSubmission submitJob(@RequestBody(required = false) FlinkSubmitRequest request) {
        int parallelism = (request != null) ? request.safeParallelism() : 1;
        return flinkTaskService.submitGlobalJob(parallelism);
    }

    @GetMapping("/jobs/{jobId}")
    public FlinkJobSubmission jobStatus(@PathVariable String jobId) {
        return flinkTaskService.getJobDetail(jobId);
    }

    @DeleteMapping("/jobs/{jobId}")
    public Map<String, Object> cancelJob(@PathVariable String jobId) {
        return flinkTaskService.cancelJob(jobId);
    }
}
