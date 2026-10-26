package com.a22.platform.file;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "uploaded_files", indexes = {
        @Index(name = "idx_uploaded_files_created_at_id", columnList = "created_at, id")
})
public class StoredFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String originalName;

    @Column(nullable = false, length = 40)
    private String fileType;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false)
    private int recordCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column
    private Long uploaderId;

    @Column(length = 80)
    private String uploaderUsername;

    /** MySQL JSON column: the normalized content is always a JSON array of records. */
    @Column(nullable = false, columnDefinition = "JSON")
    private String contentJson;

    protected StoredFile() { }

    public StoredFile(String originalName, String fileType, long fileSize, int recordCount, String contentJson, Long uploaderId, String uploaderUsername) {
        this.originalName = originalName;
        this.fileType = fileType;
        this.fileSize = fileSize;
        this.recordCount = recordCount;
        this.contentJson = contentJson;
        this.uploaderId = uploaderId;
        this.uploaderUsername = uploaderUsername;
    }

    @PrePersist
    void setCreatedAt() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getFileType() { return fileType; }
    public long getFileSize() { return fileSize; }
    public int getRecordCount() { return recordCount; }
    public Instant getCreatedAt() { return createdAt; }
    public String getContentJson() { return contentJson; }
    public Long getUploaderId() { return uploaderId; }
    public String getUploaderUsername() { return uploaderUsername; }
}
