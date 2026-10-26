package com.a22.platform.engine;

import com.a22.platform.engine.transform.TransformContext;
import com.a22.platform.engine.transform.TransformPlugin;
import com.a22.platform.engine.transform.TransformResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisLookupTransformPlugin implements TransformPlugin {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;

    public RedisLookupTransformPlugin(StringRedisTemplate redisTemplate, ObjectMapper mapper) {
        this.redisTemplate = redisTemplate;
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "redis-lookup";
    }

    @Override
    public TransformResult transform(TransformContext context) throws Exception {
        List<Map<String, Object>> records = context.getRecords();
        if (records == null || records.isEmpty()) return new TransformResult(List.of());
        if (context.getConfig() == null || context.getConfig().isBlank()) return new TransformResult(records);

        JsonNode config = mapper.readTree(context.getConfig());
        String sourceField = config.path("sourceField").asText("");
        String keyPattern = config.path("keyPattern").asText(config.path("redisKeyPattern").asText("{value}"));
        if (sourceField.isBlank()) return new TransformResult(records);
        Map<String, String> mappings = parseMappings(config.path("targetFields"));
        if (mappings.isEmpty()) return new TransformResult(records);

        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            Map<String, Object> row = new LinkedHashMap<>(record);
            Object sourceValue = record.get(sourceField);
            if (sourceValue != null && !String.valueOf(sourceValue).isBlank()) {
                String redisKey = keyPattern.replace("{value}", String.valueOf(sourceValue));
                for (Map.Entry<String, String> mapping : mappings.entrySet()) {
                    Object value = redisTemplate.opsForHash().get(redisKey, mapping.getKey());
                    if (value != null) {
                        row.put(mapping.getValue(), value);
                    } else if (config.has("defaultValue")) {
                        row.putIfAbsent(mapping.getValue(), config.path("defaultValue").asText(""));
                    }
                }
            }
            result.add(row);
        }
        return new TransformResult(result);
    }

    private Map<String, String> parseMappings(JsonNode node) {
        Map<String, String> mappings = new LinkedHashMap<>();
        if (node == null || node.isMissingNode() || node.isNull()) return mappings;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> mappings.put(entry.getKey(), entry.getValue().asText(entry.getKey())));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String source = item.path("source").asText(item.path("redisField").asText(""));
                String target = item.path("target").asText(item.path("targetField").asText(source));
                if (!source.isBlank() && !target.isBlank()) mappings.put(source, target);
            }
        }
        return mappings;
    }
}
