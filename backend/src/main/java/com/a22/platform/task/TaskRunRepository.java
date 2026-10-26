package com.a22.platform.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRunRepository extends JpaRepository<TaskRunEntity, Long> {
    List<TaskRunEntity> findTop100ByOrderByStartedAtDesc();
    List<TaskRunEntity> findByTaskId(Long taskId);
    boolean existsByTaskIdAndStatus(Long taskId, String status);
}
