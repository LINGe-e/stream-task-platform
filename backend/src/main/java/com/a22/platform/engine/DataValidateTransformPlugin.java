package com.a22.platform.engine;

import com.a22.platform.engine.transform.TransformContext;
import com.a22.platform.engine.transform.TransformPlugin;
import com.a22.platform.engine.transform.TransformResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 数据校验/审计控件：对数据进行规则校验并输出审计信息。
 * 支持：
 * 1) 必填校验（required）
 * 2) 正则校验（regex）
 * 3) 数值范围（min/max）
 * 4) 枚举白名单（allowed）
 * 5) 日期格式校验（dateFormat）
 * 6) 审计字段追加（_issues、_audit_valid）
 */
@Component
public class DataValidateTransformPlugin implements TransformPlugin {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String kind() {
        return "data-validate";
    }

    @Override
    public TransformResult transform(TransformContext context) throws Exception {
        List<Map<String, Object>> records = context.getRecords();
        if (records == null || records.isEmpty()) {
            return new TransformResult(Collections.emptyList());
        }
        String config = context.getConfig();
        if (config == null || config.isBlank()) {
            return new TransformResult(records);
        }

        JsonNode node = mapper.readTree(config);
        boolean tagRows = node.path("tagRows").asBoolean(true);
        String issueField = node.path("issueField").asText("_issues");
        String validField = node.path("validField").asText("_audit_valid");
        List<ValidateRule> rules = parseRules(node);
        if (rules.isEmpty()) {
            return new TransformResult(records);
        }

        List<Map<String, Object>> result = new ArrayList<>(records.size());
        int totalIssues = 0;
        for (Map<String, Object> record : records) {
            List<String> issues = validate(record, rules);
            totalIssues += issues.size();
            if (tagRows) {
                Map<String, Object> tagged = new LinkedHashMap<>(record);
                tagged.put(issueField, issues.isEmpty() ? "" : String.join("; ", issues));
                tagged.put(validField, issues.isEmpty());
                result.add(tagged);
            } else {
                result.add(record);
            }
        }
        return new TransformResult(result);
    }

    private List<String> validate(Map<String, Object> record, List<ValidateRule> rules) {
        List<String> issues = new ArrayList<>();
        for (ValidateRule rule : rules) {
            Object value = record.get(rule.field());
            String text = value == null ? "" : String.valueOf(value).trim();
            if (rule.required() && text.isEmpty()) {
                issues.add(rule.field() + " 不能为空");
                continue;
            }
            if (!rule.regex().isBlank() && !text.isEmpty() && !text.matches(rule.regex())) {
                issues.add(rule.field() + " 格式不正确");
            }
            if (!rule.dateFormat().isBlank() && !text.isEmpty() && !isValidDate(text, rule.dateFormat())) {
                issues.add(rule.field() + " 日期格式不符合 " + rule.dateFormat());
            }
            if (!text.isEmpty() && (rule.min() != null || rule.max() != null)) {
                try {
                    double number = Double.parseDouble(text);
                    if (rule.min() != null && number < rule.min()) {
                        issues.add(rule.field() + " 不能小于 " + rule.min());
                    }
                    if (rule.max() != null && number > rule.max()) {
                        issues.add(rule.field() + " 不能大于 " + rule.max());
                    }
                } catch (NumberFormatException ignored) {
                    // 非数值字段不处理范围
                }
            }
            if (!rule.allowed().isEmpty() && !text.isEmpty() && !rule.allowed().contains(text)) {
                issues.add(rule.field() + " 不在允许值范围内");
            }
        }
        return issues;
    }

    private List<ValidateRule> parseRules(JsonNode node) {
        List<ValidateRule> rules = new ArrayList<>();
        JsonNode rulesNode = node.path("rules");
        if (!rulesNode.isArray()) return rules;
        for (JsonNode item : rulesNode) {
            String field = item.path("field").asText("");
            if (field.isBlank()) continue;
            boolean required = item.path("required").asBoolean(false);
            String regex = item.path("regex").asText("");
            String dateFormat = item.path("dateFormat").asText("");
            Double min = item.has("min") ? item.path("min").asDouble() : null;
            Double max = item.has("max") ? item.path("max").asDouble() : null;
            List<String> allowed = new ArrayList<>();
            if (item.has("allowed") && item.path("allowed").isArray()) {
                for (JsonNode v : item.path("allowed")) {
                    allowed.add(v.asText(""));
                }
            }
            rules.add(new ValidateRule(field, required, regex, dateFormat, min, max, allowed));
        }
        return rules;
    }

    private boolean isValidDate(String value, String format) {
        try {
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern(format);
            java.time.LocalDate.parse(value, formatter);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private record ValidateRule(String field, boolean required, String regex, String dateFormat, Double min, Double max, List<String> allowed) {}
}
