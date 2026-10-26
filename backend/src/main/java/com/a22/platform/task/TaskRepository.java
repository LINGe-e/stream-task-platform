package com.a22.platform.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    /** 按创建者用户名模糊搜索 */
    List<TaskEntity> findByCreatorUsernameContainingIgnoreCaseOrderByUpdatedAtDesc(String creatorUsername);

    /** 按更新时间范围筛选 */
    List<TaskEntity> findByUpdatedAtBetweenOrderByUpdatedAtDesc(Instant start, Instant end);

    /** 按创建者用户名 + 更新时间范围组合筛选 */
    @Query("SELECT t FROM TaskEntity t WHERE " +
           "(:creatorUsername IS NULL OR LOWER(t.creatorUsername) LIKE LOWER(CONCAT('%', :creatorUsername, '%'))) " +
           "AND (:startDate IS NULL OR t.updatedAt >= :startDate) " +
           "AND (:endDate IS NULL OR t.updatedAt <= :endDate) " +
           "ORDER BY t.updatedAt DESC")
    List<TaskEntity> findByFilters(
            @Param("creatorUsername") String creatorUsername,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate);

    @Query("SELECT t FROM TaskEntity t WHERE " +
           "t.runMode = 'CONTINUOUS' " +
           "AND t.status = com.a22.platform.task.TaskStatus.PUBLISHED " +
           "AND t.continuousRunning = false " +
           "AND t.retryCount < t.maxRetryCount " +
           "AND (t.nextRunAt IS NULL OR t.nextRunAt <= :now) " +
           "ORDER BY t.nextRunAt ASC, t.id ASC")
    List<TaskEntity> findDueContinuousTasks(@Param("now") Instant now);
}
