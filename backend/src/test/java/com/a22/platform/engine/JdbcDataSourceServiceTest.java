package com.a22.platform.engine;

import com.a22.platform.datasource.JdbcConnectionSettings;
import com.a22.platform.datasource.OutputDataSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcDataSourceServiceTest {

    @Mock
    private OutputDataSourceService outputDataSourceService;

    private JdbcDataSourceService service;

    @BeforeEach
    void setUp() {
        service = new JdbcDataSourceService(outputDataSourceService, new ObjectMapper());
        when(outputDataSourceService.connectionForExecution(1L)).thenReturn(new JdbcConnectionSettings(
                1L,
                "h2",
                "org.h2.Driver",
                "jdbc:h2:mem:a22_jdbc_sink;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "sa",
                "",
                "",
                ""
        ));
    }

    @Test
    void connectionIdWriteSupportsFieldMapping() throws Exception {
        execute("DROP TABLE IF EXISTS append_sink");
        execute("CREATE TABLE append_sink (order_id VARCHAR PRIMARY KEY, total DECIMAL(10,2))");

        int written = service.write(
                Map.of(
                        "connectionId", "1",
                        "table", "append_sink",
                        "fieldMapping", "{\"amount\":\"total\"}"
                ),
                List.of(Map.of("order_id", "T001", "amount", "128.50"))
        );

        assertEquals(1, written);
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT total FROM append_sink WHERE order_id='T001'")) {
            resultSet.next();
            assertEquals(0, resultSet.getBigDecimal("total").compareTo(new java.math.BigDecimal("128.50")));
        }
    }

    @Test
    void connectionIdUpsertUpdatesExistingPrimaryKey() throws Exception {
        execute("DROP TABLE IF EXISTS upsert_sink");
        execute("CREATE TABLE upsert_sink (order_id VARCHAR PRIMARY KEY, amount VARCHAR)");

        Map<String, Object> config = Map.of(
                "connectionId", "1",
                "table", "upsert_sink",
                "writeMode", "upsert",
                "keyFields", "[\"order_id\"]"
        );
        assertEquals(1, service.write(config, List.of(Map.of("order_id", "T001", "amount", "100"))));
        assertEquals(1, service.write(config, List.of(Map.of("order_id", "T001", "amount", "200"))));

        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) AS total, MAX(amount) AS amount FROM upsert_sink")) {
            resultSet.next();
            assertEquals(1, resultSet.getInt("total"));
            assertEquals("200", resultSet.getString("amount"));
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection(
                "jdbc:h2:mem:a22_jdbc_sink;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "sa",
                ""
        );
    }
}
