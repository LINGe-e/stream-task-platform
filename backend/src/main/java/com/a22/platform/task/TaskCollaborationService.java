package com.a22.platform.task;

import com.a22.platform.auth.AuthContext;
import com.a22.platform.auth.UserEntity;
import com.a22.platform.auth.UserNotFoundException;
import com.a22.platform.auth.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TaskCollaborationService {

    private static final Set<String> ALLOWED_RELATIONS =
            Set.of("LEADER", "COLLABORATOR", "VIEWER", "OPS");

    private final TaskCollaboratorRepository collaboratorRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;

    public TaskCollaborationService(TaskCollaboratorRepository collaboratorRepository,
                                    TaskRepository taskRepository,
                                    UserRepository userRepository) {
        this.collaboratorRepository = collaboratorRepository;
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<TaskCollaborator> list() {
        requireCurrentUser();
        return collaboratorRepository.findAllByOrderByTaskIdAscCreatedAtAsc().stream()
                .map(this::toCollaborator)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CollaborationUser> listCandidates() {
        UserEntity current = requireCurrentUser();
        if (!canWrite(current)) {
            throw new IllegalStateException("\u5f53\u524d\u8d26\u53f7\u53ea\u80fd\u67e5\u770b\u534f\u4f5c\u5173\u7cfb");
        }
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "username")).stream()
                .map(user -> new CollaborationUser(
                        user.getId(), user.getUsername(), user.getDisplayName(), user.getRole()))
                .toList();
    }

    @Transactional
    public TaskCollaborator upsert(UpsertTaskCollaboratorRequest request) {
        TaskEntity task = taskRepository.findById(request.taskId())
                .orElseThrow(() -> new TaskNotFoundException(request.taskId()));
        ensureCanManage(task);

        String username = request.username().trim();
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException(username));
        if (isOwner(task, user)) {
            throw new IllegalArgumentException("\u4efb\u52a1\u521b\u5efa\u8005\u5df2\u7ecf\u662f\u8d1f\u8d23\u4eba\uff0c\u65e0\u9700\u91cd\u590d\u6dfb\u52a0");
        }

        String relationType = normalizeRelation(request.relationType());
        TaskCollaboratorEntity entity = collaboratorRepository
                .findByTaskIdAndUserId(task.getId(), user.getId())
                .orElseGet(() -> new TaskCollaboratorEntity(task, user, relationType));
        entity.updateRelationType(relationType);
        return toCollaborator(collaboratorRepository.save(entity));
    }

    @Transactional
    public void remove(Long taskId, Long userId) {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        ensureCanManage(task);
        TaskCollaboratorEntity entity = collaboratorRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("\u534f\u4f5c\u5173\u7cfb\u4e0d\u5b58\u5728"));
        collaboratorRepository.delete(entity);
    }

    private String normalizeRelation(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_RELATIONS.contains(normalized)) {
            throw new IllegalArgumentException("\u4e0d\u652f\u6301\u7684\u534f\u4f5c\u5173\u7cfb\uff1a" + value);
        }
        return normalized;
    }

    private void ensureCanManage(TaskEntity task) {
        UserEntity current = requireCurrentUser();
        if (isAdmin(current)) return;
        if (!"USER".equals(current.getRole()) || !isOwner(task, current)) {
            throw new IllegalStateException("\u53ea\u80fd\u7ef4\u62a4\u81ea\u5df1\u521b\u5efa\u4efb\u52a1\u7684\u534f\u4f5c\u8005");
        }
    }

    private boolean isOwner(TaskEntity task, UserEntity user) {
        if (task.getCreatorId() != null && user.getId() != null) {
            return task.getCreatorId().equals(user.getId());
        }
        return task.getCreatorUsername() != null
                && task.getCreatorUsername().equalsIgnoreCase(user.getUsername());
    }

    private static boolean isAdmin(UserEntity user) {
        return "SADMIN".equals(user.getRole()) || "ADMIN".equals(user.getRole());
    }

    private static boolean canWrite(UserEntity user) {
        return isAdmin(user) || "USER".equals(user.getRole());
    }

    private static UserEntity requireCurrentUser() {
        UserEntity user = AuthContext.get();
        if (user == null) {
            throw new IllegalStateException("\u672a\u767b\u5f55\uff0c\u8bf7\u5148\u767b\u5f55");
        }
        return user;
    }

    private TaskCollaborator toCollaborator(TaskCollaboratorEntity entity) {
        UserEntity user = entity.getUser();
        return new TaskCollaborator(
                entity.getId(),
                entity.getTask().getId(),
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                entity.getRelationType(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
