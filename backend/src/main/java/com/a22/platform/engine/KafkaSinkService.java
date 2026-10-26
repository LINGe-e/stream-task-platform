package com.a22.platform.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KafkaSinkService {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;

    public KafkaSinkService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper mapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
    }

    public int write(String topic, String keyField, List<Map<String, Object>> data) throws Exception {
        String t = (topic == null || topic.isBlank()) ? "a22-output" : topic;
        int count = 0;
        for (Map<String, Object> item : data) {
            String json = mapper.writeValueAsString(item);
            String key = (keyField != null && !keyField.isBlank()) ? String.valueOf(item.getOrDefault(keyField, "")) : null;
            if (key != null) {
                kafkaTemplate.send(t, key, json).get();
            } else {
                kafkaTemplate.send(t, json).get();
            }
            count++;
        }
        return count;
    }
}