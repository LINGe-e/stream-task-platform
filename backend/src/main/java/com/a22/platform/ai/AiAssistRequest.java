package com.a22.platform.ai;

import java.util.Map;

public record AiAssistRequest(
        String mode,
        String userPrompt,
        Map<String, Object> context
) {
}