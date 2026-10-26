package com.a22.platform.ai;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AiAssistService {
    private static final String SAFETY_RULES = """
            You are the AI work assistant for the A22 realtime data flow console.
            Reply in Simplified Chinese.
            You may only analyze the current page context and provide suggestions.
            Never start, stop, delete, withdraw, or otherwise operate tasks directly.
            Never modify the database, canvas, task configuration, or user permissions.
            Keep the answer concise: first a conclusion, then at most five actionable suggestions.
            """;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String provider;
    private final String model;
    private final String endpoint;
    private final String apiKey;

    public AiAssistService(
            ObjectMapper objectMapper,
            @Value("${app.ai.provider:aliyun-bailian}") String provider,
            @Value("${app.ai.model:qwen-plus}") String model,
            @Value("${app.ai.endpoint:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}") String endpoint,
            @Value("${app.ai.api-key:}") String apiKey
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.provider = provider;
        this.model = model;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    public AiAssistResponse assist(AiAssistRequest request) {
        String mode = StringUtils.hasText(request.mode()) ? request.mode() : "general";
        if (!StringUtils.hasText(apiKey)) {
            return new AiAssistResponse(false, provider, model, mode,
                    "AI Key has not been configured. Set AI_API_KEY before starting the backend.",
                    List.of("Set AI_API_KEY and restart the backend.", "The AI assistant only gives suggestions and will not operate tasks or modify data."),
                    "");
        }

        try {
            String content = callModel(mode, request.userPrompt(), request.context());
            return new AiAssistResponse(true, provider, model, mode, firstLine(content), extractSuggestions(content), content);
        } catch (Exception ex) {
            return new AiAssistResponse(true, provider, model, mode,
                    "AI analysis failed: " + ex.getMessage(),
                    List.of("Check whether AI_API_KEY is correct.", "Check whether the backend can reach Alibaba Cloud Bailian.", "Try again with shorter page context."),
                    "");
        }
    }

    private String callModel(String mode, String userPrompt, Map<String, Object> context) throws IOException, InterruptedException {
        String contextJson = objectMapper.writeValueAsString(context == null ? Map.<String, Object>of() : context);
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemRules(mode)),
                        Map.of("role", "user", "content", buildPrompt(mode, userPrompt, contextJson))
                ),
                "temperature", 0.2
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Bailian returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            Map<String, Object> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
            return objectMapper.writeValueAsString(raw);
        }
        return content.asText();
    }

    private String systemRules(String mode) {
        if ("canvas-orchestration".equals(mode)) {
            return """
                    你是 A22 数据流画布编排器。你的任务是把用户的业务要求转换为可直接执行的画布 JSON，而不是只列出控件名称。
                    必须把过滤阈值、字段映射、脱敏字段、去重键、目标表、Topic、文件路径等要求写入对应节点的 config。
                    只返回一个纯 JSON 对象，不要 Markdown，不要解释，不要执行任何任务操作。
                    """;
        }
        return SAFETY_RULES;
    }

    private String buildPrompt(String mode, String userPrompt, String contextJson) {
        if ("canvas-orchestration".equals(mode)) {
            return """
                    根据用户需求和页面上下文生成完整、可执行的画布 JSON。

                    必须满足：
                    - 先截取“写入/输出/导出”之前的输入描述判断 source，后面的写入目标不能反过来改变 source
                    - 只能使用上下文 availableNodes 中存在的 type/kind，config 必须遵循 configSpecs 的字段和示例
                    - 用户明确写出的字段、条件、数值、表名、Topic、路径和输出目标必须落入 config，不得只生成同名控件
                    - 每个业务要求对应一个或多个 transform 节点；自由文本要求也必须给出可执行或可验证的 config
                    - 返回 start、source、按需求排列的 transform、sink、end，且恰好各有一个 start/end
                    - 连线必须完整：start→source→transform...→sink→end；只有 sink 可以连接 end，禁止 transform→end
                    - 节点按数据流从左到右排列，分支节点使用不同 y 坐标；所有 edges 必须引用返回的节点 id

                    输出格式（纯 JSON，无代码块和解释）：
                    {"nodes":[{"id":"start","type":"native","position":{"x":80,"y":270},"data":{"nodeType":"start","kind":"start","label":"开始"}},{"id":"source1","type":"native","position":{"x":300,"y":270},"data":{"nodeType":"source","kind":"csv","label":"CSV输入","config":"{}"}}],"edges":[{"id":"e1","source":"start","target":"source1"}]}

                    用户需求：
                    %s

                    页面上下文 JSON：
                    %s
                    """.formatted(
                    StringUtils.hasText(userPrompt) ? userPrompt : "生成一个基本的数据处理流程",
                    contextJson
            );
        }
        if ("run-analysis".equals(mode)) {
            return """
                    你是 A22 数据流平台的失败任务诊断助手。只分析本次失败运行，不分析成功任务，不执行任何操作。

                    分析原则：
                    1. 只使用给定日志、错误信息和失败节点上下文，区分“日志事实”和“推断原因”。
                    2. 已知的数据库、JAR、Java 版本、权限、画布配置问题优先给出确定结论。
                    3. 证据不足时必须明确写“证据不足”，不要编造根因。
                    4. 建议必须具体、可检查、可执行，但不得声称已经修复或自动修改任务。

                    用户目标：
                    %s

                    失败上下文 JSON：
                    %s

                    严格按以下格式用简体中文回复：
                    结论：一句话说明最可能的失败原因和证据充分度。
                    可能原因：
                    1. 基于日志事实的原因
                    2. 需要进一步验证的推断
                    排查建议：
                    1. 第一步检查什么
                    2. 根据检查结果如何继续
                    风险等级：高/中/低
                    证据判断：一句话说明当前证据还缺什么。
                    """.formatted(
                    StringUtils.hasText(userPrompt) ? userPrompt : "分析失败原因并给出排查建议。",
                    contextJson
            );
        }
        return """
                Current page work mode: %s
                User question: %s

                Page context JSON:
                %s

                Please reply in Simplified Chinese with this format:
                Conclusion: ...
                Suggestions:
                1. ...
                2. ...
                """.formatted(mode, StringUtils.hasText(userPrompt) ? userPrompt : "Analyze the current page state and give suggestions.", contextJson);
    }

    private String firstLine(String text) {
        if (!StringUtils.hasText(text)) return "AI returned no content.";
        return text.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("AI returned no content.");
    }

    private List<String> extractSuggestions(String text) {
        if (!StringUtils.hasText(text)) return List.of();
        List<String> suggestions = new ArrayList<>();
        text.lines()
                .map(String::trim)
                .filter(line -> line.matches("^([0-9]+[.]|[-*]).+"))
                .map(line -> line.replaceFirst("^[-*\\d.\\s]+", ""))
                .filter(StringUtils::hasText)
                .limit(5)
                .forEach(suggestions::add);
        return suggestions;
    }
}
