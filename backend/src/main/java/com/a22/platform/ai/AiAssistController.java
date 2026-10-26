package com.a22.platform.ai;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiAssistController {
    private final AiAssistService aiAssistService;

    public AiAssistController(AiAssistService aiAssistService) {
        this.aiAssistService = aiAssistService;
    }

    @PostMapping("/assist")
    public AiAssistResponse assist(@Valid @RequestBody AiAssistRequest request) {
        return aiAssistService.assist(request);
    }
}