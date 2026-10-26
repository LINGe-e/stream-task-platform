package com.a22.platform.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpsertTaskCollaboratorRequest(
        @NotNull Long taskId,
        @NotBlank @Size(max = 60) String username,
        @NotBlank @Size(max = 30) String relationType
) { }
