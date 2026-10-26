package com.a22.platform.engine;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/engine")
@CrossOrigin
public class NodeTestController {
    private final TaskExecutionEngine engine;

    public NodeTestController(TaskExecutionEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/node-test")
    public NodeTestResult testNode(@RequestBody NodeTestRequest request) {
        return engine.testNode(request);
    }
}
