package com.a22.platform.task;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/task-collaborations")
@CrossOrigin
public class TaskCollaborationController {

    private final TaskCollaborationService service;

    public TaskCollaborationController(TaskCollaborationService service) {
        this.service = service;
    }

    @GetMapping
    public List<TaskCollaborator> list() {
        return service.list();
    }

    @GetMapping("/candidates")
    public List<CollaborationUser> listCandidates() {
        return service.listCandidates();
    }

    @PostMapping
    public TaskCollaborator upsert(@Valid @RequestBody UpsertTaskCollaboratorRequest request) {
        return service.upsert(request);
    }

    @DeleteMapping("/{taskId}/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long taskId, @PathVariable Long userId) {
        service.remove(taskId, userId);
    }
}
