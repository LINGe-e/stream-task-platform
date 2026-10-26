package com.a22.platform.task;

public record CollaborationUser(
        Long id,
        String username,
        String displayName,
        String role
) { }
