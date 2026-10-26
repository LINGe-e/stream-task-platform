package com.a22.platform.file;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record FileDetail(Long id, String originalName, String fileType, long fileSize, int recordCount, Instant createdAt, String uploaderUsername, JsonNode content) { }