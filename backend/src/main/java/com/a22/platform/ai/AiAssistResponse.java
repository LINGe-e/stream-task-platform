package com.a22.platform.ai;

import java.util.List;

public record AiAssistResponse(
        boolean configured,
        String provider,
        String model,
        String mode,
        String summary,
        List<String> suggestions,
        String rawText
) {
}