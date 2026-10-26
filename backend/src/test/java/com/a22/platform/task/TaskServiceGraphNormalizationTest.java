package com.a22.platform.task;

import com.a22.platform.auth.AuthContext;
import com.a22.platform.auth.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskServiceGraphNormalizationTest {

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void storesUnwrappedCanvasJson() throws Exception {
        TaskRepository repository = mock(TaskRepository.class);
        TaskRunRepository runRepository = mock(TaskRunRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TaskService service = new TaskService(repository, runRepository, objectMapper);

        TaskEntity task = new TaskEntity("定时", "JSON", "MySQL", "[]", TaskStatus.DRAFT, 0, null, 1L, "owner");
        setId(task, 226L);
        when(repository.findById(226L)).thenReturn(Optional.of(task));
        when(runRepository.existsByTaskIdAndStatus(226L, "RUNNING")).thenReturn(false);
        AuthContext.set(new UserEntity("admin", "hash", "SADMIN", "管理员"));

        String graph = """
                {"nodes":[
                  {"id":"start","type":"native","position":{"x":80,"y":180},"data":{"nodeType":"start","kind":"start"}},
                  {"id":"source","type":"native","position":{"x":280,"y":180},"data":{"nodeType":"source","kind":"kafka"}},
                  {"id":"sink","type":"native","position":{"x":480,"y":180},"data":{"nodeType":"sink","kind":"mysql"}},
                  {"id":"end","type":"native","position":{"x":680,"y":180},"data":{"nodeType":"end","kind":"end"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"source"},
                  {"id":"e2","source":"source","target":"sink"},
                  {"id":"e3","source":"sink","target":"end"}
                ]}
                """;
        String nestedGraph = objectMapper.writeValueAsString(objectMapper.writeValueAsString(graph));

        service.updateGraph(226L, nestedGraph);

        String storedGraph = task.getGraphJson();
        assertFalse(storedGraph.startsWith("\""));
        assertTrue(storedGraph.startsWith("{"));
        assertEquals(objectMapper.readTree(graph), objectMapper.readTree(storedGraph));
    }

    private static void setId(TaskEntity entity, long id) throws Exception {
        Field field = TaskEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
