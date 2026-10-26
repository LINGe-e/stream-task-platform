package com.a22.platform.feedback;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "feedback")
public class FeedbackEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long taskId;
    private Long userId;
    private String content;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public FeedbackEntity() {}

    public FeedbackEntity(Long taskId, Long userId, String content) {
        this.taskId = taskId;
        this.userId = userId;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
