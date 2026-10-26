package com.a22.platform.engine;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/exports")
public class ExportController {
    private final Path exportDir = Path.of("exports").toAbsolutePath().normalize();

    @GetMapping("/{fileName:.+}")
    public ResponseEntity<Resource> download(@PathVariable String fileName) {
        Path file = resolveExportFile(fileName);
        String contentType = probeContentType(file);
        FileSystemResource resource = new FileSystemResource(file);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    private Path resolveExportFile(String fileName) {
        String safeName = Paths.get(fileName == null ? "" : fileName).getFileName().toString();
        if (safeName.isBlank() || !safeName.equals(fileName) || safeName.contains("..")) {
            throw new ResponseStatusException(BAD_REQUEST, "导出文件名不合法");
        }
        Path file = exportDir.resolve(safeName).normalize();
        if (!file.startsWith(exportDir) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(NOT_FOUND, "导出文件不存在");
        }
        return file;
    }

    private String probeContentType(Path file) {
        try {
            String contentType = Files.probeContentType(file);
            if (contentType != null && !contentType.isBlank()) {
                return contentType;
            }
        } catch (IOException ignored) {
        }
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv")) {
            return "text/csv";
        }
        if (name.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
