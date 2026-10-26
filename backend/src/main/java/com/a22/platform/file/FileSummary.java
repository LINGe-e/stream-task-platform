package com.a22.platform.file;

import java.time.Instant;

public record FileSummary(Long id, String originalName, String fileType, long fileSize, int recordCount, Instant createdAt, String uploaderUsername) {
    static FileSummary from(StoredFile file) {
        return new FileSummary(file.getId(), file.getOriginalName(), file.getFileType(), file.getFileSize(), file.getRecordCount(), file.getCreatedAt(), file.getUploaderUsername());
    }
}