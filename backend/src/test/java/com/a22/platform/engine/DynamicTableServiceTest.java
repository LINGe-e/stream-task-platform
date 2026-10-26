package com.a22.platform.engine;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicTableServiceTest {

    @Test
    void writesPreviewsAndExtendsDynamicTablesInH2() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dynamic_table_service;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");

        DynamicTableService service = new DynamicTableService(jdbc);
        assertEquals(1, service.writeData("customer_service_tickets", List.of(
                Map.of("ticket_id", "T001", "status", "OPEN")
        )));
        assertEquals(1, service.readData("customer_service_tickets", 30).size());

        Map<String, Object> secondRecord = new LinkedHashMap<>();
        secondRecord.put("ticket_id", "T002");
        secondRecord.put("status", "CLOSED");
        secondRecord.put("phone", "13800138000");
        assertEquals(1, service.writeData("customer_service_tickets", List.of(secondRecord)));

        List<Map<String, Object>> rows = service.readData("customer_service_tickets", 30);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(row -> String.valueOf(row.get("phone")).contains("13800138000")));
    }
}
