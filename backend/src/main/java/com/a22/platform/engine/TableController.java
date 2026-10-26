package com.a22.platform.engine;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tables")
@CrossOrigin
public class TableController {
    private final DynamicTableService tableService;

    public TableController(DynamicTableService tableService) {
        this.tableService = tableService;
    }

    @GetMapping("/{tableName}")
    public List<Map<String, Object>> preview(
            @PathVariable String tableName,
            @RequestParam(defaultValue = "30") int limit) {
        return tableService.readData(tableName, limit);
    }
}