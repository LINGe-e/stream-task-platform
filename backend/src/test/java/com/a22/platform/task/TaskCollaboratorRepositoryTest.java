package com.a22.platform.task;

import com.a22.platform.auth.UserEntity;
import com.a22.platform.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:collaboration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TaskCollaboratorRepositoryTest {

    @Autowired
    private TaskCollaboratorRepository collaboratorRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsRelationsAndDeletesThemWithTask() {
        UserEntity owner = userRepository.save(new UserEntity("owner", "hash", "USER", "Owner"));
        UserEntity worker = userRepository.save(new UserEntity("worker", "hash", "USER", "Worker"));
        TaskEntity task = taskRepository.save(new TaskEntity(
                "orders", "CSV", "MySQL", "[]", TaskStatus.DRAFT, 0, null,
                owner.getId(), owner.getUsername()));
        collaboratorRepository.saveAndFlush(new TaskCollaboratorEntity(task, worker, "COLLABORATOR"));

        List<TaskCollaboratorEntity> relations =
                collaboratorRepository.findAllByOrderByTaskIdAscCreatedAtAsc();
        assertEquals(1, relations.size());
        assertEquals("worker", relations.get(0).getUser().getUsername());

        Long taskId = task.getId();
        entityManager.clear();
        taskRepository.deleteById(taskId);
        taskRepository.flush();
        entityManager.clear();
        assertEquals(0, collaboratorRepository.count());
    }

    @Test
    void deletesRelationsWithCollaboratingUser() {
        UserEntity owner = userRepository.save(new UserEntity("owner2", "hash", "USER", "Owner 2"));
        UserEntity worker = userRepository.save(new UserEntity("worker2", "hash", "USER", "Worker 2"));
        TaskEntity task = taskRepository.save(new TaskEntity(
                "customers", "JSON", "MySQL", "[]", TaskStatus.DRAFT, 0, null,
                owner.getId(), owner.getUsername()));
        collaboratorRepository.saveAndFlush(new TaskCollaboratorEntity(task, worker, "VIEWER"));

        Long workerId = worker.getId();
        entityManager.clear();
        userRepository.deleteById(workerId);
        userRepository.flush();
        entityManager.clear();

        assertEquals(0, collaboratorRepository.count());
    }
}
