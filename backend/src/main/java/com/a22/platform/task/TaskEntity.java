package com.a22.platform.task;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tasks")
public class TaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 40)
    private String sourceType;

    @Column(nullable = false, length = 40)
    private String sinkType;

    @Column(nullable = false, columnDefinition = "JSON")
    private String transformsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Column(nullable = false)
    private long recordsPerSecond;

    @Column(columnDefinition = "JSON")
    private String graphJson;

    /** 创建者用户 ID（用户删除后可能为 null） */
    @Column
    private Long creatorId;

    /** 创建者用户名（冗余存储，确保用户删除后仍可展示） */
    @Column(length = 60)
    private String creatorUsername;

    /** 运行模式：ONCE=一次性执行，CONTINUOUS=持续处理配置 */
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30) default 'ONCE'")
    private String runMode = "ONCE";

    /** 持续处理周期类型：MANUAL/FIXED_RATE/DAILY/CRON */
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30) default 'MANUAL'")
    private String scheduleType = "MANUAL";

    /** 周期表达式，例如 30m、08:30、cron 表达式等 */
    @Column(length = 120)
    private String scheduleExpression;

    /** 持续处理说明，方便答辩和交接时说明数据扫描策略 */
    @Column(length = 300)
    private String continuousRemark;

    /** 最近一次真实执行结束时间 */
    @Column
    private Instant lastRunAt;

    /** 下一次持续处理触发时间 */
    @Column
    private Instant nextRunAt;

    /** 持续处理执行锁，避免同一任务重复触发 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean continuousRunning = false;

    /** 当前连续失败次数 */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int retryCount = 0;

    /** 最大连续失败次数 */
    @Column(nullable = false, columnDefinition = "integer default 3")
    private int maxRetryCount = 3;

    /** 最近一次持续处理错误 */
    @Column(length = 500)
    private String lastError;

    /** 增量模式：NONE / STATUS / CURSOR */
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30) default 'NONE'")
    private String incrementalMode = "NONE";

    /** 游标字段，例如 id / created_at / updated_at */
    @Column(length = 80)
    private String cursorField;

    /** 上次处理到的游标值 */
    @Column(length = 200)
    private String lastCursorValue;

    /** 业务处理状态字段，例如 process_status */
    @Column(length = 80)
    private String processedField;

    /** 未处理状态值 */
    @Column(length = 80)
    private String unprocessedValue = "UNPROCESSED";

    /** 已处理状态值 */
    @Column(length = 80)
    private String processedValue = "DONE";

    /** 输出去重字段，逗号分隔，例如 order_id */
    @Column(length = 200)
    private String dedupKeys;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TaskEntity() { }

    TaskEntity(String name, String sourceType, String sinkType, String transformsJson,
               TaskStatus status, long recordsPerSecond, String graphJson,
               Long creatorId, String creatorUsername) {
        this.name = name;
        this.sourceType = sourceType;
        this.sinkType = sinkType;
        this.transformsJson = transformsJson;
        this.status = status;
        this.recordsPerSecond = recordsPerSecond;
        this.graphJson = graphJson;
        this.creatorId = creatorId;
        this.creatorUsername = creatorUsername;
    }

    @PrePersist
    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSourceType() { return sourceType; }
    public String getSinkType() { return sinkType; }
    public String getTransformsJson() { return transformsJson; }
    public TaskStatus getStatus() { return status; }
    public long getRecordsPerSecond() { return recordsPerSecond; }
    public String getGraphJson() { return graphJson; }
    public Long getCreatorId() { return creatorId; }
    public String getCreatorUsername() { return creatorUsername; }
    public String getRunMode() { return runMode; }
    public String getScheduleType() { return scheduleType; }
    public String getScheduleExpression() { return scheduleExpression; }
    public String getContinuousRemark() { return continuousRemark; }
    public Instant getLastRunAt() { return lastRunAt; }
    public Instant getNextRunAt() { return nextRunAt; }
    public boolean isContinuousRunning() { return continuousRunning; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetryCount() { return maxRetryCount; }
    public String getLastError() { return lastError; }
    public String getIncrementalMode() { return incrementalMode; }
    public String getCursorField() { return cursorField; }
    public String getLastCursorValue() { return lastCursorValue; }
    public String getProcessedField() { return processedField; }
    public String getUnprocessedValue() { return unprocessedValue; }
    public String getProcessedValue() { return processedValue; }
    public String getDedupKeys() { return dedupKeys; }
    public Instant getUpdatedAt() { return updatedAt; }

    void updateBasicInfo(String name, String sourceType, String sinkType, String transformsJson) {
        this.name = name;
        this.sourceType = sourceType;
        this.sinkType = sinkType;
        this.transformsJson = transformsJson;
    }

    void updateContinuousConfig(String runMode, String scheduleType, String scheduleExpression, String continuousRemark) {
        this.runMode = normalize(runMode, "ONCE");
        this.scheduleType = normalize(scheduleType, "MANUAL");
        this.scheduleExpression = blankToNull(scheduleExpression);
        this.continuousRemark = blankToNull(continuousRemark);
        if (!"CONTINUOUS".equals(this.runMode)) {
            this.nextRunAt = null;
            this.continuousRunning = false;
            this.retryCount = 0;
            this.lastError = null;
        } else if (this.nextRunAt == null) {
            this.nextRunAt = Instant.now();
        }
    }

    void updateIncrementalConfig(String incrementalMode, String cursorField, String lastCursorValue,
                                 String processedField, String unprocessedValue, String processedValue, String dedupKeys) {
        this.incrementalMode = normalize(incrementalMode, "NONE");
        this.cursorField = blankToNull(cursorField);
        this.lastCursorValue = blankToNull(lastCursorValue);
        this.processedField = blankToNull(processedField);
        this.unprocessedValue = valueOrDefault(unprocessedValue, "UNPROCESSED");
        this.processedValue = valueOrDefault(processedValue, "DONE");
        this.dedupKeys = blankToNull(dedupKeys);
        if ("NONE".equals(this.incrementalMode)) {
            this.cursorField = null;
            this.lastCursorValue = null;
            this.processedField = null;
            this.dedupKeys = null;
        }
    }

    void setStatus(TaskStatus status, long recordsPerSecond) {
        this.status = status;
        this.recordsPerSecond = recordsPerSecond;
    }

    void markLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }

    void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }
    void setContinuousRunning(boolean continuousRunning) { this.continuousRunning = continuousRunning; }
    void setRetryCount(int retryCount) { this.retryCount = Math.max(0, retryCount); }
    void setMaxRetryCount(int maxRetryCount) { this.maxRetryCount = Math.max(1, maxRetryCount); }
    void setLastError(String lastError) {
        this.lastError = lastError != null && lastError.length() > 500 ? lastError.substring(0, 500) : lastError;
    }

    void setLastCursorValue(String lastCursorValue) { this.lastCursorValue = blankToNull(lastCursorValue); }

    void setGraphJson(String graphJson) { this.graphJson = graphJson; }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
