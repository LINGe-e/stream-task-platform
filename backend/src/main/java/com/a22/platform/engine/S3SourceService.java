package com.a22.platform.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S3/MinIO/OSS 数据源服务
 */
@Service
public class S3SourceService {
    private final ObjectMapper mapper;
    private static final int MAX_LIMIT = 1_000_000;

    public S3SourceService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<Map<String, Object>> read(Map<String, String> config) {
        String endpoint = config.get("endpoint");
        String accessKey = config.get("access-key");
        String secretKey = config.get("secret-key");
        String bucket = config.get("bucket");
        String key = config.get("key");
        String prefix = config.getOrDefault("prefix", "");
        String format = config.getOrDefault("format", "csv");
        int limit = Math.min(parseIntSafe(config.getOrDefault("limit", "100000")), MAX_LIMIT);
        String region = config.getOrDefault("region", "us-east-1");

        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 读取需要配置 bucket");
        }

        // try-with-resources 自动关闭客户端
        try (S3Client s3Client = createS3Client(endpoint, accessKey, secretKey, region)) {
            List<Map<String, Object>> result = new ArrayList<>();

            if (key != null && !key.isBlank()) {
                result = readObject(s3Client, bucket, key, format, limit);
            } else {
                String listPrefix = prefix.isBlank() ? null : prefix;
                ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(bucket).prefix(listPrefix).build();
                var listing = s3Client.listObjectsV2Paginator(listRequest);
                for (var page : listing) {
                    for (S3Object obj : page.contents()) {
                        if (result.size() >= limit) break;
                        if (matchFormat(obj.key(), format)) {
                            result.addAll(readObject(s3Client, bucket, obj.key(), format, limit - result.size()));
                        }
                    }
                    if (result.size() >= limit) break;
                }
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("S3 读取失败: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> readObject(S3Client s3Client, String bucket, String key, String format, int limit) throws Exception {
        GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
        try (InputStream is = s3Client.getObject(getRequest, ResponseTransformer.toInputStream());
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return switch (format.toLowerCase()) {
                case "csv" -> parseCsv(reader, limit);
                case "json" -> parseJson(reader, limit);
                case "ndjson" -> parseNdjson(reader, limit);
                default -> throw new IllegalArgumentException("不支持的 S3 文件格式: " + format);
            };
        }
    }

    private S3Client createS3Client(String endpoint, String accessKey, String secretKey, String region) {
        var builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
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
            for (int i = 0; i < headers.length; i++) row.put(headers[i], i < values.length ? values[i] : null);
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

    private boolean matchFormat(String key, String format) {
        String lower = key.toLowerCase();
        return switch (format.toLowerCase()) {
            case "csv" -> lower.endsWith(".csv");
            case "json" -> lower.endsWith(".json");
            case "ndjson" -> lower.endsWith(".ndjson") || lower.endsWith(".jsonl");
            default -> true;
        };
    }

    private int parseIntSafe(String value) {
        try { return Integer.parseInt(value); } catch (Exception e) { return 100000; }
    }
}
