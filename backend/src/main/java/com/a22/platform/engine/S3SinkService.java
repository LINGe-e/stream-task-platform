package com.a22.platform.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S3/MinIO/OSS 数据汇服务
 */
@Service
public class S3SinkService {
    private final ObjectMapper mapper;

    public S3SinkService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public int write(Map<String, String> config, List<Map<String, Object>> data) {
        String endpoint = config.get("endpoint");
        String accessKey = config.get("access-key");
        String secretKey = config.get("secret-key");
        String bucket = config.get("bucket");
        String key = config.get("key");
        String format = config.getOrDefault("format", "csv");
        String region = config.getOrDefault("region", "us-east-1");

        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("S3 写入需要配置 bucket");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("S3 写入需要配置 key");
        if (data == null || data.isEmpty()) return 0;

        try (S3Client s3Client = createS3Client(endpoint, accessKey, secretKey, region)) {
            byte[] content = switch (format.toLowerCase()) {
                case "csv" -> buildCsv(data);
                case "json" -> buildJson(data);
                case "ndjson" -> buildNdjson(data);
                default -> throw new IllegalArgumentException("不支持的 S3 文件格式: " + format);
            };

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket).key(key).contentType(getContentType(format)).build();
            s3Client.putObject(putRequest, RequestBody.fromBytes(content));
            return data.size();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("S3 写入失败: " + e.getMessage(), e);
        }
    }

    private S3Client createS3Client(String endpoint, String accessKey, String secretKey, String region) {
        var builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    private byte[] buildCsv(List<Map<String, Object>> data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
        if (data.isEmpty()) return new byte[0];
        List<String> headers = data.get(0).keySet().stream().toList();
        writer.write(headers.stream().map(this::escapeCsv).collect(Collectors.joining(",")));
        writer.write("\n");
        for (Map<String, Object> row : data) {
            writer.write(headers.stream().map(h -> escapeCsv(String.valueOf(row.getOrDefault(h, "")))).collect(Collectors.joining(",")));
            writer.write("\n");
        }
        writer.flush();
        return baos.toByteArray();
    }

    private byte[] buildJson(List<Map<String, Object>> data) throws Exception {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
    }

    private byte[] buildNdjson(List<Map<String, Object>> data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
        for (Map<String, Object> row : data) { writer.write(mapper.writeValueAsString(row)); writer.write("\n"); }
        writer.flush();
        return baos.toByteArray();
    }

    private String escapeCsv(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) return "\"" + field.replace("\"", "\"\"") + "\"";
        return field;
    }

    private String getContentType(String format) {
        return switch (format.toLowerCase()) {
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "ndjson" -> "application/x-ndjson";
            default -> "application/octet-stream";
        };
    }
}
