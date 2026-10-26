package com.a22.platform.file;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FileParserService {
    private final ObjectMapper objectMapper;
    private final XmlJsonConverter xmlJsonConverter;
    private final int maxRecords;

    public FileParserService(ObjectMapper objectMapper,
                             XmlJsonConverter xmlJsonConverter,
                             @Value("${app.upload.max-records:100000}") int maxRecords) {
        this.objectMapper = objectMapper;
        this.xmlJsonConverter = xmlJsonConverter;
        this.maxRecords = maxRecords;
    }

    ParsedFile parse(MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        try (InputStream input = file.getInputStream()) {
            return switch (extension) {
                case "json" -> new ParsedFile("JSON", parseJson(input));
                case "csv" -> new ParsedFile("CSV", parseCsv(input));
                case "xlsx", "xls" -> new ParsedFile(extension.toUpperCase(Locale.ROOT), parseExcel(input));
                case "xml" -> new ParsedFile("XML", parseXml(input));
                case "kafka" -> new ParsedFile("KAFKA", parseNdjson(input));
                case "ndjson", "jsonl" -> new ParsedFile("NDJSON", parseNdjson(input));
                default -> throw new InvalidFileException("仅支持 .json、.csv、.xlsx、.xls、.xml、.kafka、.ndjson、.jsonl 文件");
            };
        } catch (IOException exception) {
            throw new InvalidFileException("读取文件失败，请确认文件没有损坏", exception);
        }
    }

    private List<Map<String, Object>> parseXml(InputStream input) {
        List<Map<String, Object>> rows = xmlJsonConverter.recordsFromXml(input);
        ensureWithinLimit(rows.size());
        return rows;
    }

    private List<Map<String, Object>> parseJson(InputStream input) throws IOException {
        // 跳过BOM
        InputStream noBomInput = skipBom(input);
        JsonNode root = objectMapper.readTree(noBomInput);
        if (root == null) return List.of();
        if (root.isObject()) return List.of(objectMapper.convertValue(root, new TypeReference<>() { }));
        if (!root.isArray()) throw new InvalidFileException("JSON 顶层必须是对象或对象数组");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.isObject()) throw new InvalidFileException("JSON 数组中的每一项必须是对象");
            rows.add(objectMapper.convertValue(item, new TypeReference<>() { }));
            ensureWithinLimit(rows.size());
        }
        return rows;
    }

    /** 解析 Kafka 测试数据、NDJSON 和 JSONL（每行一个 JSON 对象） */
    private List<Map<String, Object>> parseNdjson(InputStream input) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        // 跳过BOM
        InputStream noBomInput = skipBom(input);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(noBomInput, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // 跳过行内的BOM字符
                if (line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
                Map<String, Object> row = objectMapper.readValue(line, new TypeReference<>() { });
                rows.add(row);
                ensureWithinLimit(rows.size());
            }
        }
        return rows;
    }
    
    private List<Map<String, Object>> parseCsv(InputStream input) throws IOException {
        // 读取全部字节用于编码检测
        byte[] allBytes = input.readAllBytes();
        String encoding = detectEncoding(allBytes);
        InputStream noBomInput = skipBom(new ByteArrayInputStream(allBytes));
        try (Reader reader = new BufferedReader(new InputStreamReader(noBomInput, encoding));
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build().parse(reader)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (org.apache.commons.csv.CSVRecord record : parser) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String header : parser.getHeaderMap().keySet()) row.put(header, sanitizeCsvValue(record.get(header)));
                rows.add(row);
                ensureWithinLimit(rows.size());
            }
            return rows;
        }
    }

    /** 检测 CSV 文件编码：BOM > 严格 UTF-8 > GBK 特征 > 默认 UTF-8 */
    private String detectEncoding(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return "UTF-8";
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return "UTF-16LE";
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return "UTF-16BE";
        }
        if (isValidUtf8(bytes)) {
            return "UTF-8";
        }
        // 检测 GBK 特征：连续高字节 0x81-0xFE 后跟 0x40-0xFE
        int gbkScore = 0;
        for (int i = 0; i < Math.min(bytes.length - 1, 4096); i++) {
            int b = bytes[i] & 0xFF;
            if (b >= 0x81 && b <= 0xFE) {
                int next = bytes[i + 1] & 0xFF;
                if (next >= 0x40 && next <= 0xFE && next != 0x7F) {
                    gbkScore++;
                    i++;
                }
            }
        }
        if (gbkScore > 10) {
            return "GBK";
        }
        return "UTF-8";
    }

    private boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private String sanitizeCsvValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.equalsIgnoreCase("NaN")
                || trimmed.equalsIgnoreCase("Inf")
                || trimmed.equalsIgnoreCase("Infinity")
                || trimmed.equalsIgnoreCase("-Inf")
                || trimmed.equalsIgnoreCase("-Infinity")
                || trimmed.equalsIgnoreCase("+Inf")
                || trimmed.equalsIgnoreCase("+Infinity")) {
            return null;
        }
        return value;
    }

    private List<Map<String, Object>> parseExcel(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) return List.of();
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> iterator = sheet.rowIterator();
            if (!iterator.hasNext()) return List.of();
            DataFormatter formatter = new DataFormatter();
            Row headerRow = iterator.next();
            List<String> headers = new ArrayList<>();
            for (int column = 0; column < headerRow.getLastCellNum(); column++) {
                String header = formatter.formatCellValue(headerRow.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)).trim();
                headers.add(header.isEmpty() ? "column_" + (column + 1) : header);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            while (iterator.hasNext()) {
                Row row = iterator.next();
                if (isBlank(row, formatter)) continue;
                Map<String, Object> result = new LinkedHashMap<>();
                for (int column = 0; column < headers.size(); column++) {
                    result.put(headers.get(column), sanitizeCsvValue(formatter.formatCellValue(row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK))));
                }
                rows.add(result);
                ensureWithinLimit(rows.size());
            }
            return rows;
        } catch (Exception exception) {
            if (exception instanceof InvalidFileException invalid) throw invalid;
            throw new InvalidFileException("Excel 文件解析失败", exception);
        }
    }

    private boolean isBlank(Row row, DataFormatter formatter) {
        for (int column = row.getFirstCellNum(); column >= 0 && column < row.getLastCellNum(); column++) {
            if (!formatter.formatCellValue(row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)).isBlank()) return false;
        }
        return true;
    }

    private void ensureWithinLimit(int size) {
        if (size > maxRecords) throw new InvalidFileException("单个文件最多导入 " + maxRecords + " 条记录");
    }

    private String extensionOf(String name) {
        if (name == null || !name.contains(".")) throw new InvalidFileException("文件必须带有扩展名");
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    
    /** 跳过UTF-8 BOM (EF BB BF) */
    private InputStream skipBom(InputStream input) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(input, 3);
        byte[] bom = new byte[3];
        int bytesRead = pushback.read(bom);
        if (bytesRead < 3 || bom[0] != (byte) 0xEF || bom[1] != (byte) 0xBB || bom[2] != (byte) 0xBF) {
            // 不是BOM，推回
            pushback.unread(bom, 0, bytesRead);
        }
        return pushback;
    }
}
