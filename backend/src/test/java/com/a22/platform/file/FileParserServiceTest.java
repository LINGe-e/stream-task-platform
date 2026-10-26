package com.a22.platform.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileParserServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FileParserService parser = new FileParserService(mapper, new XmlJsonConverter(mapper), 10);

    @Test
    void parsesCsvIntoJsonCompatibleRecords() {
        String zhangSan = text(0x5f20, 0x4e09);
        String shanghai = text(0x4e0a, 0x6d77);
        String liSi = text(0x674e, 0x56db);
        String hangzhou = text(0x676d, 0x5dde);
        String csv = "id,name,city\n1," + zhangSan + "," + shanghai
                + "\n2," + liSi + "," + hangzhou + "\n";
        MockMultipartFile file = new MockMultipartFile("file", "customers.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        ParsedFile parsed = parser.parse(file);

        assertEquals("CSV", parsed.fileType());
        assertEquals(2, parsed.records().size());
        assertEquals(zhangSan, parsed.records().get(0).get("name"));
    }

    @Test
    void parsesGbkCsvIntoJsonCompatibleRecords() throws Exception {
        String zhangSan = text(0x5f20, 0x4e09);
        String cityName = text(0x676d, 0x5dde, 0x897f, 0x6e56, 0x98ce, 0x666f, 0x533a, 0x6b22, 0x8fce, 0x4f60);
        String csv = "id,name,city\n1," + zhangSan + "," + cityName + "\n";
        MockMultipartFile file = new MockMultipartFile("file", "customers.csv", "text/csv",
                csv.getBytes(Charset.forName("GBK")));

        ParsedFile parsed = parser.parse(file);

        assertEquals("CSV", parsed.fileType());
        assertEquals(1, parsed.records().size());
        assertEquals(zhangSan, parsed.records().get(0).get("name"));
        assertEquals(cityName, parsed.records().get(0).get("city"));
    }

    @Test
    void parsesJsonArrayIntoRecords() {
        MockMultipartFile file = new MockMultipartFile("file", "events.json", "application/json",
                "[{\"eventId\":\"E1\"},{\"eventId\":\"E2\"}]".getBytes(StandardCharsets.UTF_8));

        ParsedFile parsed = parser.parse(file);

        assertEquals("JSON", parsed.fileType());
        assertEquals(2, parsed.records().size());
        assertTrue(parsed.records().get(0).containsKey("eventId"));
    }

    @Test
    void parsesXmlRecordsIntoJsonCompatibleRows() {
        MockMultipartFile file = new MockMultipartFile("file", "events.xml", "application/xml",
                "<events><event><id>E1</id><amount>120.5</amount></event><event><id>E2</id><amount>88</amount></event></events>".getBytes(StandardCharsets.UTF_8));

        ParsedFile parsed = parser.parse(file);

        assertEquals("XML", parsed.fileType());
        assertEquals(2, parsed.records().size());
        assertEquals("E1", parsed.records().get(0).get("id"));
    }

    @Test
    void parsesLineDelimitedJsonFormats() {
        String content = "{\"eventId\":\"E1\"}\n{\"eventId\":\"E2\"}\n";
        for (String extension : new String[]{"kafka", "ndjson", "jsonl"}) {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "events." + extension,
                    "application/x-ndjson",
                    content.getBytes(StandardCharsets.UTF_8));

            ParsedFile parsed = parser.parse(file);

            assertEquals("kafka".equals(extension) ? "KAFKA" : "NDJSON", parsed.fileType());
            assertEquals(2, parsed.records().size());
            assertEquals("E1", parsed.records().get(0).get("eventId"));
        }
    }

    @Test
    void parsesXlsxIntoJsonCompatibleRows() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("customers");
            sheet.createRow(0).createCell(0).setCellValue("id");
            sheet.getRow(0).createCell(1).setCellValue("name");
            sheet.createRow(1).createCell(0).setCellValue("C001");
            sheet.getRow(1).createCell(1).setCellValue("Alice");
            workbook.write(output);

            ParsedFile parsed = parser.parse(new MockMultipartFile("file", "customers.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray()));

            assertEquals("XLSX", parsed.fileType());
            assertEquals(1, parsed.records().size());
            assertEquals("Alice", parsed.records().get(0).get("name"));
        }
    }

    @Test
    void parsesXlsIntoJsonCompatibleRows() throws Exception {
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("customers");
            sheet.createRow(0).createCell(0).setCellValue("id");
            sheet.getRow(0).createCell(1).setCellValue("name");
            sheet.createRow(1).createCell(0).setCellValue("C002");
            sheet.getRow(1).createCell(1).setCellValue("Bob");
            workbook.write(output);

            ParsedFile parsed = parser.parse(new MockMultipartFile("file", "customers.xls",
                    "application/vnd.ms-excel", output.toByteArray()));

            assertEquals("XLS", parsed.fileType());
            assertEquals(1, parsed.records().size());
            assertEquals("Bob", parsed.records().get(0).get("name"));
        }
    }

    private static String text(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }
}
