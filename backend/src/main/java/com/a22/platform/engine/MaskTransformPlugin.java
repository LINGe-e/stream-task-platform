package com.a22.platform.engine;

import com.a22.platform.engine.transform.TransformContext;
import com.a22.platform.engine.transform.TransformPlugin;
import com.a22.platform.engine.transform.TransformResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段脱敏控件：支持全局类型与按字段规则脱敏。
 * 新增能力：
 * 1) 字段级别规则（rules 数组）
 * 2) 自定义掩码字符、前/后保留位数
 * 3) 保持原有 phone/name/idcard/email/default 兼容
 */
@Component
public class MaskTransformPlugin implements TransformPlugin {
    private final ObjectMapper mapper = new ObjectMapper();

    public String kind() { return "mask"; }

    public TransformResult transform(TransformContext context) throws Exception {
        String config = context.getConfig();
        List<Map<String, Object>> data = context.getRecords();
        JsonNode node = mapper.readTree(config);
        String globalType = node.path("type").asText(node.path("mask").asText("default"));
        String maskChar = node.path("maskChar").asText("*");
        int showFirst = node.path("showFirst").asInt(-1);
        int showLast = node.path("showLast").asInt(-1);

        Map<String, FieldRule> fieldRules = new LinkedHashMap<>();
        if (node.has("rules") && node.path("rules").isArray()) {
            for (JsonNode rule : node.path("rules")) {
                String field = rule.path("field").asText("");
                if (!field.isBlank()) {
                    fieldRules.put(field, new FieldRule(
                            rule.path("type").asText(globalType),
                            rule.path("maskChar").asText(maskChar),
                            rule.path("showFirst").asInt(showFirst),
                            rule.path("showLast").asInt(showLast)
                    ));
                }
            }
        }

        List<String> fields = new ArrayList<>();
        if (!fieldRules.isEmpty()) {
            fields.addAll(fieldRules.keySet());
        } else if (node.has("fields") && node.path("fields").isArray()) {
            for (JsonNode f : node.path("fields")) {
                fields.add(f.asText(""));
            }
        } else {
            String single = node.path("field").asText("");
            if (!single.isEmpty()) fields.add(single);
        }

        if (fields.isEmpty()) return new TransformResult(data);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : data) {
            Map<String, Object> newRow = new LinkedHashMap<>(row);
            for (String field : fields) {
                Object value = row.get(field);
                if (value == null || String.valueOf(value).isBlank()) continue;
                FieldRule rule = fieldRules.getOrDefault(field, new FieldRule(globalType, maskChar, showFirst, showLast));
                newRow.put(field, mask(String.valueOf(value), rule));
            }
            result.add(newRow);
        }
        return new TransformResult(result);
    }

    private String mask(String value, FieldRule rule) {
        if (value == null || value.isEmpty()) return value;
        String type = rule.type();
        if ("phone".equals(type)) return maskPhone(value, rule);
        if ("name".equals(type)) return maskName(value, rule);
        if ("idcard".equals(type)) return maskIdCard(value, rule);
        if ("email".equals(type)) return maskEmail(value, rule);
        return maskDefault(value, rule);
    }

    private String maskPhone(String phone, FieldRule rule) {
        String normalized = phone.replaceAll("[^0-9+]", "");
        int first = rule.showFirst() >= 0 ? rule.showFirst() : 3;
        int last = rule.showLast() >= 0 ? rule.showLast() : 4;
        if (normalized.length() <= first + last || normalized.length() < 7) {
            return rule.maskChar().repeat(Math.max(phone.length(), 1));
        }
        return maskPreserve(phone, first, last, rule.maskChar());
    }

    private String maskName(String name, FieldRule rule) {
        if (name == null || name.isEmpty()) return name;
        String mc = rule.maskChar();
        int first = rule.showFirst() >= 0 ? rule.showFirst() : 1;
        int last = rule.showLast() >= 0 ? rule.showLast() : 1;
        // 名字长度<=2：只保留前一个字，如"张三"→"张*"
        if (name.length() <= 2 && rule.showFirst() < 0 && rule.showLast() < 0) {
            return name.substring(0, 1) + mc.repeat(name.length() - 1);
        }
        // 名字长度>2：保留前一后一，如"欧阳锋"→"欧*锋"
        return maskPreserve(name, first, last, mc);
    }

    private String maskIdCard(String id, FieldRule rule) {
        int first = rule.showFirst() >= 0 ? rule.showFirst() : 3;
        int last = rule.showLast() >= 0 ? rule.showLast() : 4;
        if (id.length() <= first + last || id.length() < 7) {
            return rule.maskChar().repeat(Math.max(id.length(), 1));
        }
        return maskPreserve(id, first, last, rule.maskChar());
    }

    private String maskEmail(String email, FieldRule rule) {
        if (email.length() <= 3) {
            return rule.maskChar().repeat(email.length());
        }
        int at = email.indexOf("@");
        if (at <= 0) return maskDefault(email, rule);
        int first = rule.showFirst() >= 0 ? rule.showFirst() : 1;
        String prefix = email.substring(0, Math.min(first, at));
        return prefix + rule.maskChar().repeat(3) + email.substring(at);
    }

    private String maskDefault(String value, FieldRule rule) {
        int first = rule.showFirst() >= 0 ? rule.showFirst() : Math.min(3, value.length());
        int last = rule.showLast() >= 0 ? rule.showLast() : Math.min(4, value.length());
        if (value.length() <= first + last) {
            return rule.maskChar().repeat(Math.max(value.length(), 1));
        }
        return maskPreserve(value, first, last, rule.maskChar());
    }

    private String maskPreserve(String value, int first, int last, String maskChar) {
        if (value.length() <= first + last) {
            return maskChar.repeat(Math.max(value.length(), 1));
        }
        int maskedLength = value.length() - first - last;
        return value.substring(0, first) + maskChar.repeat(maskedLength) + value.substring(value.length() - last);
    }

    private record FieldRule(String type, String maskChar, int showFirst, int showLast) {}
}
