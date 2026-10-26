package com.a22.platform.task;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskCollaboratorRepository extends JpaRepository<TaskCollaboratorEntity, Long> {

    @EntityGraph(attributePaths = {"task", "user"})
    List<TaskCollaboratorEntity> findAllByOrderByTaskIdAscCreatedAtAsc();

    @EntityGraph(attributePaths = {"task", "user"})
    Optional<TaskCollaboratorEntity> findByTaskIdAndUserId(Long taskId, Long userId);
}
