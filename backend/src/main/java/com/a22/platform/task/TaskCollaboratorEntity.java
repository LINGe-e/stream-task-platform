package com.a22.platform.task;

import com.a22.platform.auth.UserEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(
        name = "task_collaborators",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_collaborator_task_user",
                columnNames = {"task_id", "user_id"}),
        indexes = {
                @Index(name = "idx_task_collaborator_task", columnList = "task_id"),
                @Index(name = "idx_task_collaborator_user", columnList = "user_id")
        })
public class TaskCollaboratorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TaskEntity task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserEntity user;

    @Column(nullable = false, length = 30)
    private String relationType;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TaskCollaboratorEntity() { }

    TaskCollaboratorEntity(TaskEntity task, UserEntity user, String relationType) {
        this.task = task;
        this.user = user;
        this.relationType = relationType;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    void updateRelationType(String relationType) {
        this.relationType = relationType;
    }

    public Long getId() { return id; }
    public TaskEntity getTask() { return task; }
    public UserEntity getUser() { return user; }
    public String getRelationType() { return relationType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
