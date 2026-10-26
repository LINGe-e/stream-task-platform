package com.a22.platform.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RedisSourceService {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;

    public RedisSourceService(StringRedisTemplate redisTemplate, ObjectMapper mapper) {
        this.redisTemplate = redisTemplate;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> read(Map<String, ?> config) {
        String type = get(config, "type", "list").toLowerCase();
        String key = get(config, "key", "");
        int count = Math.min(parseInt(get(config, "count", "1000")), 100_000);
        if (key.isBlank()) throw new IllegalArgumentException("Redis 输入需要配置 key");
        return switch (type) {
            case "hash" -> readHash(key);
            case "set" -> readSet(key, count);
            case "list" -> readList(key, count);
            default -> throw new IllegalArgumentException("不支持的 Redis 输入类型：" + type);
        };
    }

    private List<Map<String, Object>> readList(String key, int count) {
        List<String> values = redisTemplate.opsForList().range(key, 0, count - 1);
        if (values == null) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < values.size(); index += 1) {
            rows.add(parseOrWrap(values.get(index), index));
        }
        return rows;
    }

    private List<Map<String, Object>> readSet(String key, int count) {
        Set<String> values = redisTemplate.opsForSet().members(key);
        if (values == null) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        for (String value : values) {
            if (index >= count) break;
            rows.add(parseOrWrap(value, index));
            index += 1;
        }
        return rows;
    }

    private List<Map<String, Object>> readHash(String key) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) return List.of();
        Map<String, Object> row = new LinkedHashMap<>();
        entries.forEach((field, value) -> row.put(String.valueOf(field), value));
        return List.of(row);
    }

    private Map<String, Object> parseOrWrap(String value, int index) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ignored) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index);
            row.put("value", value);
            return row;
        }
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 1000;
        }
    }

    private String get(Map<String, ?> config, String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
