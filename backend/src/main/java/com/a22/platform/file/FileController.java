package com.a22.platform.file;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.a22.platform.auth.AuthContext;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/files")
@CrossOrigin
public class FileController {
    private final FileService service;
    public FileController(FileService service) { this.service = service; }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public FileSummary upload(@RequestParam("file") MultipartFile file) {
        Long uploaderId = AuthContext.getUserId();
        String uploader = AuthContext.getUsername();
        return service.upload(file, uploaderId, uploader);
    }
    @GetMapping public List<FileSummary> list() { return service.list(); }
    @GetMapping("/{id}") public FileDetail get(@PathVariable long id) { return service.get(id); }

    @PatchMapping("/{id}/name")
    public FileSummary rename(@PathVariable long id, @RequestBody java.util.Map<String, String> body) {
        return service.rename(id, body.getOrDefault("originalName", ""), AuthContext.getUserId(), AuthContext.get() != null ? AuthContext.get().getRole() : null);
    }


    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { service.delete(id, AuthContext.getUserId(), AuthContext.get() != null ? AuthContext.get().getRole() : null); }
}
