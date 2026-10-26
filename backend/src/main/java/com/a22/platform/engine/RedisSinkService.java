package com.a22.platform.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RedisSinkService {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;

    public RedisSinkService(StringRedisTemplate redisTemplate, ObjectMapper mapper) {
        this.redisTemplate = redisTemplate;
        this.mapper = mapper;
    }

    public int write(String stream, List<Map<String, Object>> data) {
        String key = (stream == null || stream.isBlank()) ? "a22:task-output" : stream;
        int count = 0;
        for (Map<String, Object> item : data) {
            try {
                redisTemplate.opsForList().rightPush(key, mapper.writeValueAsString(item));
                count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    public void writeHash(String key, Map<String, Object> data) {
        redisTemplate.opsForHash().putAll(key, data);
    }
}