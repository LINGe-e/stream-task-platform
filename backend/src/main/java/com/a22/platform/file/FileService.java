package com.a22.platform.file;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class FileService {
    private final StoredFileRepository repository;
    private final FileParserService parser;
    private final ObjectMapper objectMapper;

    private static boolean isAdmin(String role) {
        return role != null && (role.equals("SADMIN") || role.equals("ADMIN"));
    }

    private void ensureFileOwner(StoredFile file, Long operatorId, String operatorRole) {
        if (isAdmin(operatorRole)) return;
        if (file.getUploaderId() != null && !file.getUploaderId().equals(operatorId)) {
            throw new IllegalStateException("只能操作自己上传的文件");
        }
    }

    public FileService(StoredFileRepository repository, FileParserService parser, ObjectMapper objectMapper) {
        this.repository = repository;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FileSummary upload(MultipartFile upload, Long uploaderId, String uploaderUsername) {
        if (upload == null || upload.isEmpty()) throw new InvalidFileException("请选择一个非空文件");
        ParsedFile parsed = parser.parse(upload);
        try {
            StoredFile file = repository.save(new StoredFile(upload.getOriginalFilename(), parsed.fileType(), upload.getSize(),
                    parsed.records().size(), objectMapper.writeValueAsString(parsed.records()), uploaderId, uploaderUsername));
            return FileSummary.from(file);
        } catch (JsonProcessingException exception) {
            throw new InvalidFileException("无法转换文件内容为 JSON", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<FileSummary> list() {
        return repository.findAllSummariesOrderByCreatedAtDesc();
    }

    @Transactional
    public FileSummary rename(long id, String newName, Long operatorId, String operatorRole) {
        if (newName == null || newName.isBlank()) throw new InvalidFileException("文件名不能为空");
        StoredFile file = repository.findById(id).orElseThrow(() -> new FileNotFoundException(id));
        ensureFileOwner(file, operatorId, operatorRole);
        file.setOriginalName(newName.trim());
        return FileSummary.from(repository.save(file));
    }

    @Transactional
    public void delete(long id, Long operatorId, String operatorRole) {
        StoredFile file = repository.findById(id).orElseThrow(() -> new FileNotFoundException(id));
        ensureFileOwner(file, operatorId, operatorRole);
        repository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public FileDetail get(long id) {
        StoredFile file = repository.findById(id).orElseThrow(() -> new FileNotFoundException(id));
        try {
            JsonNode content = objectMapper.readTree(file.getContentJson());
            return new FileDetail(file.getId(), file.getOriginalName(), file.getFileType(), file.getFileSize(),
                    file.getRecordCount(), file.getCreatedAt(), file.getUploaderUsername(), content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的 JSON 内容无效", exception);
        }
    }
}
