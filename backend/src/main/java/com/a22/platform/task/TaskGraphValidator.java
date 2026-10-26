package com.a22.platform.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public class TaskGraphValidator {

    public static void validate(JsonNode graph) {
        if (!graph.has("nodes") || !graph.get("nodes").isArray() || graph.get("nodes").isEmpty()) {
            throw new IllegalArgumentException("任务画布至少需要一个节点");
        }

        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        for (JsonNode node : graph.get("nodes")) {
            if (!node.hasNonNull("id")) throw new IllegalArgumentException("画布节点缺少 id");
            nodes.put(node.get("id").asText(), node);
        }

        boolean hasSource = false, hasSink = false;
        for (JsonNode node : nodes.values()) {
            String nodeType = node.path("data").path("nodeType").asText();
            if ("source".equals(nodeType)) hasSource = true;
            if ("sink".equals(nodeType)) hasSink = true;
        }
        if (!hasSource) throw new IllegalArgumentException("任务画布缺少输入节点");
        if (!hasSink) throw new IllegalArgumentException("任务画布缺少输出节点");

        if (graph.has("edges") && graph.get("edges").isArray()) {
            for (JsonNode edge : graph.get("edges")) {
                String src = edge.hasNonNull("source") ? edge.get("source").asText() : null;
                String tgt = edge.hasNonNull("target") ? edge.get("target").asText() : null;
                if (src == null || tgt == null || !nodes.containsKey(src) || !nodes.containsKey(tgt)) {
                    throw new IllegalArgumentException("画布包含无效连线");
                }
            }

            Map<String, Integer> indegree = new LinkedHashMap<>();
            Map<String, List<String>> next = new LinkedHashMap<>();
            nodes.keySet().forEach(id -> { indegree.put(id, 0); next.put(id, new ArrayList<>()); });
            for (JsonNode edge : graph.get("edges")) {
                String src = edge.get("source").asText();
                String tgt = edge.get("target").asText();
                next.get(src).add(tgt);
                indegree.merge(tgt, 1, Integer::sum);
            }

            Queue<String> queue = new ArrayDeque<>();
            indegree.forEach((id, deg) -> { if (deg == 0) queue.add(id); });
            int count = 0;
            while (!queue.isEmpty()) {
                String id = queue.poll();
                count++;
                for (String nid : next.get(id)) {
                    if (indegree.merge(nid, -1, Integer::sum) == 0) queue.add(nid);
                }
            }
            if (count != nodes.size()) throw new IllegalArgumentException("任务画布存在循环依赖");
        }
    }
}