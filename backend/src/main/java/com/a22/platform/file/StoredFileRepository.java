package com.a22.platform.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    /**
     * Workspace pages only need metadata. Excluding contentJson prevents a large JSON column
     * from being read and sorted for every uploaded file on each refresh.
     */
    @Query("""
            select new com.a22.platform.file.FileSummary(
                f.id, f.originalName, f.fileType, f.fileSize, f.recordCount, f.createdAt, f.uploaderUsername
            )
            from StoredFile f
            order by f.createdAt desc, f.id desc
            """)
    List<FileSummary> findAllSummariesOrderByCreatedAtDesc();
}
