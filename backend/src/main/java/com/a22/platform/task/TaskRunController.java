package com.a22.platform.task;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/task-runs")
@CrossOrigin
public class TaskRunController {
    private final TaskRunService runService;

    public TaskRunController(TaskRunService runService) {
        this.runService = runService;
    }

    @GetMapping
    public List<TaskRun> list() {
        return runService.listRecent();
    }

    @GetMapping("/task/{taskId}")
    public List<TaskRun> listByTask(@PathVariable long taskId) {
        return runService.listByTaskId(taskId);
    }
}