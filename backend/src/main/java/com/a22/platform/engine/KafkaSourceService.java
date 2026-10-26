package com.a22.platform.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
public class KafkaSourceService {
    private final ObjectMapper mapper;
    private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();

    public KafkaSourceService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${app.kafka.topic:a22-transactions}", groupId = "a22-platform")
    public void listen(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> data = mapper.readValue(record.value(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            queue.offer(data);
        } catch (Exception ignored) {}
    }

    public List<Map<String, Object>> read(String topic, int maxRecords, int timeoutMs) {
        List<Map<String, Object>> result = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (result.size() < maxRecords && System.currentTimeMillis() < deadline) {
                Map<String, Object> item = queue.poll(100, TimeUnit.MILLISECONDS);
                if (item != null) {
                    result.add(item);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    public Map<String, Object> consumeOne(long timeoutMs) {
        try {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}