package com.a22.platform.flink;

import com.a22.platform.datasource.JdbcConnectionSettings;
import com.a22.platform.datasource.OutputDataSourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineConfigGeneratorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generatesJdbcSourceFromSavedConnection() throws Exception {
        OutputDataSourceService outputDataSourceService = mock(OutputDataSourceService.class);
        when(outputDataSourceService.connectionForExecution(42L)).thenReturn(new JdbcConnectionSettings(
                42L,
                "postgresql",
                "org.postgresql.Driver",
                "jdbc:postgresql://127.0.0.1:5432/analytics",
                "reader",
                "secret",
                "postgresql",
                "reporting"));
        PipelineConfigGenerator generator = new PipelineConfigGenerator(
                mapper,
                "",
                "",
                "",
                "localhost:9092",
                outputDataSourceService);
        ObjectNode graph = mapper.createObjectNode();
        ObjectNode sourceNode = mapper.createObjectNode();
        sourceNode.put("id", "source");
        ObjectNode data = mapper.createObjectNode();
        data.put("nodeType", "source");
        data.put("kind", "jdbc");
        data.put("config", mapper.createObjectNode()
                .put("connectionId", "42")
                .put("table", "orders")
                .put("limit", "500")
                .toString());
        sourceNode.set("data", data);
        graph.putArray("nodes").add(sourceNode);
        graph.putArray("edges");

        JsonNode generated = mapper.readTree(generator.generate(mapper.writeValueAsString(graph), null, null));
        JsonNode source = generated.path("source");

        assertEquals("jdbc", source.path("type").asText());
        assertEquals("jdbc:postgresql://127.0.0.1:5432/analytics", source.path("url").asText());
        assertEquals("secret", source.path("password").asText());
        assertEquals("org.postgresql.Driver", source.path("driver").asText());
        assertEquals("postgresql", source.path("dialect").asText());
        assertEquals("reporting", source.path("schemaName").asText());
        assertEquals("orders", source.path("table").asText());
        assertEquals(500, source.path("limit").asInt());
        assertEquals(true, source.path("preserveAllColumns").asBoolean());
    }
}
