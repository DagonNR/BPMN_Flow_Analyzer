package com.pia.vvs.output;

import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.parser.BpmnParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskClassificationTest {
    @TempDir
    Path directory;

    @Test
    void readsExplicitHumanTypesWithoutPromotingGenericOrAutomaticTasks() throws Exception {
        BpmnProcess process = model();
        TaskClassification classification = TaskClassification.automatic(process);
        assertEquals(NodeType.USER_TASK, process.getNodes().get("user").getType());
        assertEquals(NodeType.MANUAL_TASK, process.getNodes().get("manual").getType());
        assertEquals(NodeType.TASK, process.getNodes().get("generic").getType());
        assertEquals(Set.of("user", "manual"), classification.getHumanIds());
        assertEquals(Set.of("generic", "other"), Set.copyOf(classification.getUnclassifiedIds()));
        assertFalse(classification.isHuman(process.getNodes().get("service")));
        assertThrows(UnsupportedOperationException.class, () -> classification.getHumanIds().add("service"));
    }

    @Test
    void combinesAutomaticAndExternalClassificationByIdEvenWithRepeatedNames() throws Exception {
        BpmnProcess process = model();
        var classification = TaskClassification.load(process, config("processId=test\ngeneric=human\nother=excluded\n"));
        assertEquals(process.getNodes().get("generic").getName(), process.getNodes().get("other").getName());
        assertEquals(Set.of("user", "manual", "generic"), classification.getHumanIds());
        assertTrue(classification.getUnclassifiedIds().isEmpty());
        assertFalse(classification.isHuman(process.getNodes().get("other")));
    }

    @Test
    void leavesUnspecifiedGenericTasksUnclassified() throws Exception {
        var classification = TaskClassification.load(model(), config("processId=test\ngeneric=human"));
        assertEquals(java.util.List.of("other"), classification.getUnclassifiedIds());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "processId=test\nmissing=human", "processId=test\nstart=human",
            "processId=test\ngeneric=automatic", "processId=test\nservice=human",
            "processId=test\nuser=excluded", "processId=test\nmanual=excluded",
            "processId=other\ngeneric=human", "generic=human",
            "processId=test\ngeneric=human\ngeneric=excluded",
            "processId=test\ngeneric=human\ngeneric=human",
            "processId=test\nprocessId=test", "processId=test\nRevisar=human"
    })
    void rejectsUnknownIdsInvalidValuesContradictionsAndDuplicateKeys(String content) throws Exception {
        BpmnProcess process = model();
        Path file = config(content);
        assertThrows(IllegalArgumentException.class, () -> TaskClassification.load(process, file));
    }

    @Test
    void hardwareProfileSelectsExactlyTheFiveActivitiesShownInFigureFour() throws Exception {
        BpmnProcess process = new BpmnParser().parse(Path.of("bpmn/HardwareRetailer.bpmn"), "_6-1");
        var classification = TaskClassification.load(process, Path.of("bpmn/HardwareRetailer.tasks.properties"));
        assertEquals(Set.of("_6-102", "_6-277", "_6-379", "_6-153", "_6-569"), classification.getHumanIds());
        assertTrue(classification.getUnclassifiedIds().isEmpty());
        assertEquals(8, process.getNodes().values().stream().filter(node -> node.getType() == NodeType.TASK).count());
    }

    private BpmnProcess model() throws Exception {
        Path file = Files.writeString(directory.resolve("model.bpmn"), """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="test">
                    <startEvent id="start"/><userTask id="user"/><manualTask id="manual"/>
                    <task id="generic" name="Revisar"/><task id="other" name="Revisar"/>
                    <serviceTask id="service"/>
                  </process>
                </definitions>
                """);
        return new BpmnParser().parse(file, "test");
    }

    private Path config(String text) throws Exception {
        return Files.writeString(directory.resolve("tasks.properties"), text);
    }
}
