package com.a22.platform.engine;

import com.a22.platform.engine.transform.TransformContext;
import com.a22.platform.engine.transform.TransformPlugin;
import com.a22.platform.engine.transform.TransformResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 数据补全/修正控件：根据数据库已有信息补全上游缺失字段。
 * 支持：
 * 1) 多键联合匹配（keys 数组）
 * 2) 映射后补全（preMapping / postMapping）
 */
@Component
public class DataRepairTransformPlugin implements TransformPlugin {
    private static final String KEY_SEPARATOR = "\u001F";
    private final DynamicTableService tableService;
    private final ObjectMapper mapper = new ObjectMapper();

    public DataRepairTransformPlugin(DynamicTableService tableService) {
        this.tableService = tableService;
    }

    @Override
    public String kind() {
        return "data-repair";
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
        String lookupTable = node.path("lookupTable").asText("");
        int limit = parseLimit(node.path("limit").asText("100000"));
        boolean ignoreCase = node.path("ignoreCase").asBoolean(false);
        boolean overwrite = node.path("overwrite").asBoolean(false);
        List<KeyMapping> keyMappings = parseKeyMappings(node);
        Map<String, String> preMapping = parseMappingObject(node.path("preMapping"));
        Map<String, String> postMapping = parseMappingObject(node.path("postMapping"));

        if (lookupTable.isBlank() || keyMappings.isEmpty()) {
            throw new IllegalArgumentException("数据补全配置缺少 lookupTable 或 keys/lookupKey");
        }

        List<Map<String, Object>> lookupRows = tableService.readData(lookupTable, limit);
        if (lookupRows.isEmpty()) {
            return new TransformResult(records);
        }
        Map<String, Map<String, Object>> lookupIndex = buildLookupIndex(lookupRows, keyMappings, ignoreCase);
        List<RepairRule> rules = parseRepairRules(node);
        if (rules.isEmpty()) {
            return new TransformResult(records);
        }

        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            Map<String, Object> working = new LinkedHashMap<>(record);
            if (preMapping != null && !preMapping.isEmpty()) {
                working = applyMapping(working, preMapping);
            }
            Map<String, Object> repaired = new LinkedHashMap<>(working);
            String compositeKey = buildCompositeKey(working, keyMappings, ignoreCase);
            Map<String, Object> lookupRow = compositeKey == null ? null : lookupIndex.get(compositeKey);

            for (RepairRule rule : rules) {
                Object current = repaired.get(rule.targetField());
                boolean needRepair = overwrite || isBlankValue(current);
                if (!needRepair || lookupRow == null) {
                    continue;
                }
                Object lookupValue = lookupRow.get(rule.lookupField());
                if (!isBlankValue(lookupValue)) {
                    repaired.put(rule.targetField(), lookupValue);
                }
            }
            if (postMapping != null && !postMapping.isEmpty()) {
                repaired = applyMapping(repaired, postMapping);
            }
            result.add(repaired);
        }
        return new TransformResult(result);
    }

    private Map<String, Map<String, Object>> buildLookupIndex(List<Map<String, Object>> rows, List<KeyMapping> keyMappings, boolean ignoreCase) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = buildCompositeKey(row, keyMappings, ignoreCase, true);
            if (key != null) {
                index.put(key, row);
            }
        }
        return index;
    }

    private String buildCompositeKey(Map<String, Object> row, List<KeyMapping> keyMappings, boolean ignoreCase) {
        return buildCompositeKey(row, keyMappings, ignoreCase, false);
    }

    private String buildCompositeKey(Map<String, Object> row, List<KeyMapping> keyMappings, boolean ignoreCase, boolean useLookupField) {
        StringBuilder builder = new StringBuilder();
        for (KeyMapping mapping : keyMappings) {
            String field = useLookupField ? mapping.lookupField() : mapping.recordField();
            String text = normalizeText(row.get(field), ignoreCase);
            if (text == null) {
                return null;
            }
            if (builder.length() > 0) {
                builder.append(KEY_SEPARATOR);
            }
            builder.append(text);
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private List<KeyMapping> parseKeyMappings(JsonNode node) {
        List<KeyMapping> mappings = new ArrayList<>();
        JsonNode keysNode = node.path("keys");
        if (keysNode.isArray()) {
            for (JsonNode item : keysNode) {
                String recordField = item.path("recordField").asText("");
                String lookupField = item.path("lookupField").asText(recordField);
                if (!recordField.isBlank() && !lookupField.isBlank()) {
                    mappings.add(new KeyMapping(recordField, lookupField));
                }
            }
            return mappings;
        }
        // 兼容旧配置：单键 lookupKey
        String lookupKey = node.path("lookupKey").asText("");
        if (!lookupKey.isBlank()) {
            mappings.add(new KeyMapping(lookupKey, lookupKey));
        }
        return mappings;
    }

    private List<RepairRule> parseRepairRules(JsonNode node) {
        List<RepairRule> rules = new ArrayList<>();
        JsonNode fieldsNode = node.path("fields");
        if (fieldsNode.isArray()) {
            for (JsonNode item : fieldsNode) {
                String sourceField = item.path("sourceField").asText("");
                String lookupField = item.path("lookupField").asText("");
                String targetField = item.path("targetField").asText(sourceField);
                if (!sourceField.isBlank() && !lookupField.isBlank()) {
                    rules.add(new RepairRule(sourceField, lookupField, targetField));
                }
            }
            return rules;
        }
        if (fieldsNode.isObject()) {
            fieldsNode.fields().forEachRemaining(entry -> {
                String sourceField = entry.getKey();
                String lookupField = entry.getValue().asText("");
                if (!lookupField.isBlank()) {
                    rules.add(new RepairRule(sourceField, lookupField, sourceField));
                }
            });
            return rules;
        }
        String sourceField = node.path("sourceField").asText("");
        String lookupField = node.path("lookupField").asText("");
        String targetField = node.path("targetField").asText(sourceField);
        if (!sourceField.isBlank() && !lookupField.isBlank()) {
            rules.add(new RepairRule(sourceField, lookupField, targetField));
        }
        return rules;
    }

    private Map<String, String> parseMappingObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Collections.emptyMap();
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> mapping.put(entry.getKey(), entry.getValue().asText("")));
            return mapping;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String from = item.path("from").asText("");
                String to = item.path("to").asText("");
                if (!from.isBlank() && !to.isBlank()) {
                    mapping.put(from, to);
                }
            }
        }
        return mapping;
    }

    private Map<String, Object> applyMapping(Map<String, Object> record, Map<String, String> mapping) {
        Map<String, Object> result = new LinkedHashMap<>();
        record.forEach((key, value) -> result.put(mapping.getOrDefault(key, key), value));
        return result;
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text);
    }

    private String normalizeText(Object value, boolean ignoreCase) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return ignoreCase ? text.toLowerCase(Locale.ROOT) : text;
    }

    private int parseLimit(String value) {
        if (value == null || value.isBlank()) {
            return 100_000;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(value.trim()), 100_000));
        } catch (NumberFormatException e) {
            return 100_000;
        }
    }

    private record KeyMapping(String recordField, String lookupField) {}
    private record RepairRule(String sourceField, String lookupField, String targetField) {}
}
