package com.a22.platform.task;

import java.time.Instant;

public record TaskCollaborator(
        Long id,
        Long taskId,
        Long userId,
        String username,
        String displayName,
        String role,
        String relationType,
        Instant createdAt,
        Instant updatedAt
) { }
