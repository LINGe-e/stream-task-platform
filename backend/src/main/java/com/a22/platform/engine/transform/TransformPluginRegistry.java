package com.a22.platform.engine.transform;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TransformPluginRegistry {
    private final Map<String, TransformPlugin> pluginMap;

    public TransformPluginRegistry(List<TransformPlugin> plugins) {
        this.pluginMap = new LinkedHashMap<>();
        for (TransformPlugin plugin : plugins) {
            pluginMap.put(plugin.kind(), plugin);
        }
    }

    public TransformPlugin get(String kind) {
        TransformPlugin plugin = pluginMap.get(kind);
        if (plugin == null) {
            throw new IllegalArgumentException("unsupported transform kind: " + kind);
        }
        return plugin;
    }
}
