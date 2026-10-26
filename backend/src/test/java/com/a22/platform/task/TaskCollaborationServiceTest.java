package com.a22.platform.task;

import com.a22.platform.auth.AuthContext;
import com.a22.platform.auth.UserEntity;
import com.a22.platform.auth.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskCollaborationServiceTest {

    private TaskCollaboratorRepository collaboratorRepository;
    private TaskRepository taskRepository;
    private UserRepository userRepository;
    private TaskCollaborationService service;
    private UserEntity owner;
    private UserEntity worker;
    private TaskEntity task;

    @BeforeEach
    void setUp() {
        collaboratorRepository = mock(TaskCollaboratorRepository.class);
        taskRepository = mock(TaskRepository.class);
        userRepository = mock(UserRepository.class);
        service = new TaskCollaborationService(collaboratorRepository, taskRepository, userRepository);

        owner = user(1L, "owner", "USER");
        worker = user(2L, "worker", "USER");
        task = new TaskEntity("orders", "CSV", "MySQL", "[]", TaskStatus.DRAFT, 0, null,
                owner.getId(), owner.getUsername());
        setId(task, 10L);

        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(userRepository.findByUsername("worker")).thenReturn(Optional.of(worker));
        when(collaboratorRepository.findByTaskIdAndUserId(10L, 2L)).thenReturn(Optional.empty());
        when(collaboratorRepository.save(any(TaskCollaboratorEntity.class))).thenAnswer(invocation -> {
            TaskCollaboratorEntity entity = invocation.getArgument(0);
            setId(entity, 100L);
            entity.prePersist();
            return entity;
        });
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void ownerCanAddCollaborator() {
        AuthContext.set(owner);

        TaskCollaborator result = service.upsert(
                new UpsertTaskCollaboratorRequest(10L, "worker", "leader"));

        assertEquals(10L, result.taskId());
        assertEquals(2L, result.userId());
        assertEquals("worker", result.username());
        assertEquals("LEADER", result.relationType());
        verify(collaboratorRepository).save(any(TaskCollaboratorEntity.class));
    }

    @Test
    void nonOwnerCannotManageCollaborators() {
        AuthContext.set(worker);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.upsert(
                new UpsertTaskCollaboratorRequest(10L, "worker", "COLLABORATOR")));

        assertFalse(error.getMessage().isBlank());
        verify(collaboratorRepository, never()).save(any());
    }

    @Test
    void adminCanRemoveCollaborator() {
        UserEntity admin = user(3L, "admin", "ADMIN");
        TaskCollaboratorEntity relation = new TaskCollaboratorEntity(task, worker, "VIEWER");
        setId(relation, 100L);
        relation.prePersist();
        when(collaboratorRepository.findByTaskIdAndUserId(10L, 2L)).thenReturn(Optional.of(relation));
        AuthContext.set(admin);

        service.remove(10L, 2L);

        verify(collaboratorRepository).delete(relation);
    }

    @Test
    void readonlyOperatorCannotListAssignmentCandidates() {
        AuthContext.set(user(4L, "ops", "OPS_READONLY"));

        assertThrows(IllegalStateException.class, service::listCandidates);
        verify(userRepository, never()).findAll(any(org.springframework.data.domain.Sort.class));
    }

    private UserEntity user(Long id, String username, String role) {
        UserEntity user = new UserEntity(username, "hash", role, username);
        setId(user, id);
        return user;
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
