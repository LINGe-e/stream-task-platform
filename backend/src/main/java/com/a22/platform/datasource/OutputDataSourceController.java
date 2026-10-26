package com.a22.platform.datasource;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/output-data-sources")
@CrossOrigin
public class OutputDataSourceController {

    private final OutputDataSourceService service;

    public OutputDataSourceController(OutputDataSourceService service) {
        this.service = service;
    }

    @GetMapping("/drivers")
    public List<JdbcDriverInfo> drivers() {
        return service.drivers();
    }

    @GetMapping
    public List<OutputDataSourceView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OutputDataSourceView create(@Valid @RequestBody OutputDataSourceRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public OutputDataSourceView update(@PathVariable Long id, @Valid @RequestBody OutputDataSourceRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/test")
    public ConnectionTestResult test(@Valid @RequestBody OutputDataSourceTestRequest request) {
        return service.test(request);
    }

    @GetMapping("/{id}/tables")
    public List<TableInfo> tables(@PathVariable Long id) {
        return service.tables(id);
    }

    @GetMapping("/{id}/tables/{table}/columns")
    public List<ColumnInfo> columns(@PathVariable Long id, @PathVariable String table) {
        return service.columns(id, table);
    }
}
