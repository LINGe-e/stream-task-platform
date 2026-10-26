package com.a22.platform.flink;

import java.time.LocalDateTime;

public record FlinkJobSubmission(
        String jobId,
        String jobName,
        String status,
        String flinkJobId,
        LocalDateTime submittedAt,
        String message
) {
    public static FlinkJobSubmission submitted(String jobId, String jobName, String flinkJobId) {
        return new FlinkJobSubmission(jobId, jobName, "SUBMITTED", flinkJobId, LocalDateTime.now(), "作业已提交到 Flink");
    }

    public static FlinkJobSubmission failed(String jobId, String jobName, String error) {
        return new FlinkJobSubmission(jobId, jobName, "FAILED", null, LocalDateTime.now(), error);
    }
}
