package com.a22.platform.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HDFS 数据源服务 - 支持从 HDFS 读取文件数据
 */
@Service
public class HdfsSourceService {
    private final ObjectMapper mapper;
    private final String defaultFs;
    private static final int MAX_LIMIT = 1_000_000;

    public HdfsSourceService(ObjectMapper mapper,
                             @Value("${app.hdfs.default-fs:hdfs://localhost:9000}") String defaultFs) {
        this.mapper = mapper;
        this.defaultFs = defaultFs;
    }

    public List<Map<String, Object>> read(Map<String, String> config) {
        String path = config.get("path");
        String format = config.getOrDefault("format", "csv");
        int limit = Math.min(parseIntSafe(config.getOrDefault("limit", "100000")), MAX_LIMIT);
        String pattern = config.getOrDefault("pattern", "");
        String fsUrl = config.getOrDefault("fs", defaultFs);

        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("HDFS 读取需要配置 path");
        }

        try {
            Configuration conf = new Configuration();
            conf.set("fs.defaultFS", fsUrl);

            // try-with-resources 自动关闭
            try (FileSystem fs = FileSystem.get(conf)) {
                List<Map<String, Object>> result = new ArrayList<>();
                Path hdfsPath = new Path(path);

                if (fs.isDirectory(hdfsPath)) {
                    String globPattern = pattern.isBlank() ? "*" : pattern;
                    RemoteIterator<LocatedFileStatus> files = fs.listFiles(hdfsPath, true);
                    while (files.hasNext() && result.size() < limit) {
                        LocatedFileStatus file = files.next();
                        if (matchPattern(file.getPath().getName(), globPattern)) {
                            try (InputStream is = fs.open(file.getPath())) {
                                result.addAll(readStream(is, format, limit - result.size()));
                            }
                        }
                    }
                } else {
                    try (InputStream is = fs.open(hdfsPath)) {
                        result = readStream(is, format, limit);
                    }
                }
                return result;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HDFS 读取失败: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> readStream(InputStream is, String format, int limit) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return switch (format.toLowerCase()) {
                case "csv" -> parseCsv(reader, limit);
                case "json" -> parseJson(reader, limit);
                case "ndjson" -> parseNdjson(reader, limit);
                default -> throw new IllegalArgumentException("不支持的 HDFS 文件格式: " + format);
            };
        }
    }

    private List<Map<String, Object>> parseCsv(BufferedReader reader, int limit) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        String headerLine = reader.readLine();
        if (headerLine == null) return result;

        String[] headers = parseCsvLine(headerLine);
        String line;
        while ((line = reader.readLine()) != null && result.size() < limit) {
            if (line.isBlank()) continue;
            String[] values = parseCsvLine(line);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.length; i++) {
                row.put(headers[i], i < values.length ? values[i] : null);
            }
            result.add(row);
        }
        return result;
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) { fields.add(current.toString().trim()); current = new StringBuilder(); }
            else current.append(c);
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    private List<Map<String, Object>> parseJson(BufferedReader reader, int limit) throws Exception {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        if (sb.isEmpty()) return List.of();
        List<Map<String, Object>> result = mapper.readValue(sb.toString(), new TypeReference<>() {});
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    private List<Map<String, Object>> parseNdjson(BufferedReader reader, int limit) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && result.size() < limit) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try { result.add(mapper.readValue(line, new TypeReference<>() {})); } catch (Exception ignored) {}
        }
        return result;
    }

    private boolean matchPattern(String fileName, String pattern) {
        if ("*".equals(pattern)) return true;
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }

    private int parseIntSafe(String value) {
        try { return Integer.parseInt(value); } catch (Exception e) { return 100000; }
    }
}
