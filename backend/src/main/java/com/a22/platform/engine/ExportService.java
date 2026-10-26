package com.a22.platform.engine;

import com.a22.platform.file.StoredFile;
import com.a22.platform.file.StoredFileRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ExportService {
    private final StoredFileRepository fileRepository;

    public ExportService(StoredFileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    public ExportFile exportCsv(String tableName, List<Map<String, Object>> data) {
        StringBuilder sb = new StringBuilder();
        if (!data.isEmpty()) {
            sb.append(String.join(",", data.get(0).keySet().stream().map(this::csvEscape).toArray(String[]::new))).append("\n");
            for (Map<String, Object> row : data) {
                sb.append(String.join(",", row.values().stream().map(value -> csvEscape(String.valueOf(value))).toArray(String[]::new))).append("\n");
            }
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fileName = normalizeBaseName(tableName) + ".csv";
        persistExport(fileName, bytes);
        return new ExportFile(fileName, "text/csv", bytes);
    }

    public ExportFile exportExcel(String tableName, List<Map<String, Object>> data) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("data");
            List<String> headers = collectHeaders(data);
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                headerRow.createCell(i).setCellValue(headers.get(i));
            }
            for (int rowIndex = 0; rowIndex < data.size(); rowIndex++) {
                Row sheetRow = sheet.createRow(rowIndex + 1);
                Map<String, Object> record = data.get(rowIndex);
                for (int col = 0; col < headers.size(); col++) {
                    Cell cell = sheetRow.createCell(col);
                    Object value = record.get(headers.get(col));
                    writeCell(cell, value);
                }
            }
            for (int i = 0; i < Math.min(headers.size(), 30); i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(output);
            String fileName = normalizeBaseName(tableName) + ".xlsx";
            byte[] bytes = output.toByteArray();
            persistExport(fileName, bytes);
            return new ExportFile(fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("生成 Excel 导出文件失败", exception);
        }
    }

    private void persistExport(String fileName, byte[] bytes) {
        try {
            Path dir = Path.of("exports").toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName).normalize(), bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("保存导出文件失败：" + fileName, exception);
        }
    }

    private List<String> collectHeaders(List<Map<String, Object>> data) {
        LinkedHashSet<String> headers = new LinkedHashSet<>();
        for (Map<String, Object> row : data) {
            headers.addAll(row.keySet());
        }
        return new ArrayList<>(headers);
    }

    private void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private String normalizeBaseName(String name) {
        String value = name == null || name.isBlank() ? "export" : name.trim();
        value = value.replaceAll("(?i)\\.(csv|xlsx|xls)$", "");
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String csvEscape(String value) {
        String text = value == null || "null".equals(value) ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
