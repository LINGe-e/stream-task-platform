package com.a22.platform.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HDFS 数据汇服务
 */
@Service
public class HdfsSinkService {
    private final ObjectMapper mapper;
    private final String defaultFs;

    public HdfsSinkService(ObjectMapper mapper,
                           @Value("${app.hdfs.default-fs:hdfs://localhost:9000}") String defaultFs) {
        this.mapper = mapper;
        this.defaultFs = defaultFs;
    }

    public int write(Map<String, String> config, List<Map<String, Object>> data) {
        String path = config.get("path");
        String format = config.getOrDefault("format", "csv");
        boolean overwrite = Boolean.parseBoolean(config.getOrDefault("overwrite", "true"));
        String fsUrl = config.getOrDefault("fs", defaultFs);

        if (path == null || path.isBlank()) throw new IllegalArgumentException("HDFS 写入需要配置 path");
        if (data == null || data.isEmpty()) return 0;

        try {
            Configuration conf = new Configuration();
            conf.set("fs.defaultFS", fsUrl);

            // try-with-resources 自动关闭
            try (FileSystem fs = FileSystem.get(conf)) {
                Path hdfsPath = new Path(path);
                fs.mkdirs(hdfsPath.getParent());

                try (OutputStream os = fs.create(hdfsPath, overwrite);
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                    switch (format.toLowerCase()) {
                        case "csv" -> writeCsv(writer, data);
                        case "json" -> writeJson(writer, data);
                        case "ndjson" -> writeNdjson(writer, data);
                        default -> throw new IllegalArgumentException("不支持的 HDFS 文件格式: " + format);
                    }
                }
            }
            return data.size();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HDFS 写入失败: " + e.getMessage(), e);
        }
    }

    private void writeCsv(BufferedWriter writer, List<Map<String, Object>> data) throws Exception {
        if (data.isEmpty()) return;
        List<String> headers = data.get(0).keySet().stream().toList();
        writer.write(headers.stream().map(this::escapeCsv).collect(Collectors.joining(",")));
        writer.newLine();
        for (Map<String, Object> row : data) {
            writer.write(headers.stream().map(h -> escapeCsv(String.valueOf(row.getOrDefault(h, "")))).collect(Collectors.joining(",")));
            writer.newLine();
        }
    }

    private void writeJson(BufferedWriter writer, List<Map<String, Object>> data) throws Exception {
        writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }

    private void writeNdjson(BufferedWriter writer, List<Map<String, Object>> data) throws Exception {
        for (Map<String, Object> row : data) { writer.write(mapper.writeValueAsString(row)); writer.newLine(); }
    }

    private String escapeCsv(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) return "\"" + field.replace("\"", "\"\"") + "\"";
        return field;
    }
}
