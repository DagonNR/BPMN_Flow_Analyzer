package com.pia.vvs.graph;

import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import com.pia.vvs.parser.BpmnParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProcessGraphTest {
    @Test
    void buildsGraphWithoutChangingOriginalProcess() throws IOException {
        BpmnProcess process = readApplication();
        Map<String, BpmnNode> originalNodes = Map.copyOf(process.getNodes());
        List<SequenceFlow> originalFlows = List.copyOf(process.getSequenceFlows());

        ProcessGraph graph = new ProcessGraph(process);

        assertSame(process, graph.getProcess());
        assertEquals(19, graph.getProcess().getNodes().size());
        assertEquals(20, graph.getProcess().getSequenceFlows().size());
        assertEquals(originalNodes, process.getNodes());
        assertEquals(originalFlows, process.getSequenceFlows());
        assertEquals(20, process.getNodes().keySet().stream()
                .mapToInt(id -> graph.getIncomingFlows(id).size()).sum());
        assertEquals(20, process.getNodes().keySet().stream()
                .mapToInt(id -> graph.getOutgoingFlows(id).size()).sum());
        for (SequenceFlow flow : originalFlows) {
            assertTrue(graph.getOutgoingFlows(flow.getSourceRef()).contains(flow));
            assertTrue(graph.getIncomingFlows(flow.getTargetRef()).contains(flow));
        }
    }

    @ParameterizedTest
    @MethodSource("expectedConnections")
    void readsExpectedConnections(String nodeId, List<String> incoming, List<String> outgoing)
            throws IOException {
        ProcessGraph graph = new ProcessGraph(readApplication());

        assertEquals(incoming, graph.getIncomingFlows(nodeId).stream().map(SequenceFlow::getId).toList());
        assertEquals(outgoing, graph.getOutgoingFlows(nodeId).stream().map(SequenceFlow::getId).toList());
    }

    static Stream<Arguments> expectedConnections() {
        return Stream.of(
                Arguments.of("_1", List.of(), List.of("_1-_5")),
                Arguments.of("_5", List.of("_1-_5"), List.of("_5-_6")),
                Arguments.of("_7", List.of("_6-_7"), List.of("_7-_34", "_7-_9", "_7-_10")),
                Arguments.of("_35", List.of("_10-_35", "_34-_35"), List.of("_35-_15")),
                Arguments.of("_33", List.of("_15-_33", "_32-_33"), List.of("_33-_3"))
        );
    }

    @Test
    void resolvesSourceAndTargetAndPreservesCompleteFlow() throws IOException {
        BpmnProcess process = readApplication();
        ProcessGraph graph = new ProcessGraph(process);
        SequenceFlow flow = graph.getOutgoingFlows("_7").get(0);

        assertEquals("_7-_34", flow.getId());
        assertSame(process.getNodes().get("_7"), graph.getSourceNode(flow));
        assertSame(process.getNodes().get("_34"), graph.getTargetNode(flow));
        assertSame(flow, graph.getIncomingFlows("_34").get(0));
        assertSame(process.getSequenceFlows().stream()
                .filter(item -> item.getId().equals("_7-_34")).findFirst().orElseThrow(), flow);
        assertEquals("success", flow.getName());
        assertEquals("return \"SUCCESS\".equals(model.getProcessResults()"
                + ".getValidationResult().getStatus().getStatusCode());", flow.getCondition());
    }

    @Test
    void identifiesStartAndEndEventsAndNodesWithoutConnections() throws IOException {
        ProcessGraph graph = new ProcessGraph(readApplication());

        assertEquals(List.of("_1"), graph.getStartEvents().stream().map(BpmnNode::getId).toList());
        assertEquals(List.of("_2", "_19", "_22"),
                graph.getEndEvents().stream().map(BpmnNode::getId).toList());
        assertSame(graph.getNode("_1"), graph.getStartEvents().get(0));
        assertTrue(graph.getIncomingFlows("_1").isEmpty());
        for (BpmnNode end : graph.getEndEvents()) {
            assertTrue(graph.getOutgoingFlows(end.getId()).isEmpty());
        }
    }

    @Test
    void initializesIsolatedNodeWithoutTreatingItAsAnEvent() {
        BpmnNode isolated = new BpmnNode("alone", "Tarea aislada", NodeType.TASK);
        ProcessGraph graph = new ProcessGraph(new BpmnProcess("test", "", List.of(isolated), List.of()));

        assertSame(isolated, graph.getNode("alone"));
        assertTrue(graph.getIncomingFlows("alone").isEmpty());
        assertTrue(graph.getOutgoingFlows("alone").isEmpty());
        assertTrue(graph.getStartEvents().isEmpty());
        assertTrue(graph.getEndEvents().isEmpty());
        SequenceFlow loop = new SequenceFlow("loop", "alone", "alone", "", "");
        assertThrows(UnsupportedOperationException.class, () -> graph.getIncomingFlows("alone").add(loop));
        assertThrows(UnsupportedOperationException.class, () -> graph.getOutgoingFlows("alone").add(loop));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsMissingSourceOrTarget(boolean invalidSource) {
        BpmnNode node = new BpmnNode("a", "", NodeType.TASK);
        SequenceFlow flow = new SequenceFlow("flow", invalidSource ? "missing" : "a",
                invalidSource ? "a" : "missing", "", "");
        BpmnProcess process = new BpmnProcess("test", "", List.of(node), List.of(flow));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ProcessGraph(process));

        assertTrue(error.getMessage().contains("missing"));
    }

    @Test
    void rejectsDuplicateFlowIds() {
        BpmnNode node = new BpmnNode("a", "", NodeType.TASK);
        SequenceFlow first = new SequenceFlow("duplicate", "a", "a", "primero", "x > 0");
        SequenceFlow second = new SequenceFlow("duplicate", "a", "a", "segundo", "x <= 0");
        BpmnProcess process = new BpmnProcess("test", "", List.of(node), List.of(first, second));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ProcessGraph(process));

        assertTrue(error.getMessage().contains("ID de SequenceFlow duplicado: duplicate"));
    }

    @Test
    void rejectsNullProcessAndNullFlowArguments() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> new ProcessGraph(null));
        assertEquals("El proceso BPMN es obligatorio.", error.getMessage());

        ProcessGraph graph = new ProcessGraph(new BpmnProcess("test", "", List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> graph.getSourceNode(null));
        assertThrows(NullPointerException.class, () -> graph.getTargetNode(null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "missing"})
    void rejectsQueriesForInvalidNodeIds(String nodeId) throws IOException {
        ProcessGraph graph = new ProcessGraph(readApplication());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> graph.getNode(nodeId));
        assertTrue(error.getMessage().contains("No existe un nodo con ID:"));
        assertThrows(IllegalArgumentException.class, () -> graph.getIncomingFlows(nodeId));
        assertThrows(IllegalArgumentException.class, () -> graph.getOutgoingFlows(nodeId));
    }

    @Test
    void rejectsEndpointQueriesForNodesOutsideTheProcess() throws IOException {
        ProcessGraph graph = new ProcessGraph(readApplication());
        SequenceFlow flow = new SequenceFlow("external", "missingSource", "missingTarget", "", "");

        assertThrows(IllegalArgumentException.class, () -> graph.getSourceNode(flow));
        assertThrows(IllegalArgumentException.class, () -> graph.getTargetNode(flow));
    }

    @Test
    void protectsReturnedListsAgainstExternalChanges() throws IOException {
        ProcessGraph graph = new ProcessGraph(readApplication());
        SequenceFlow flow = graph.getOutgoingFlows("_5").get(0);

        assertThrows(UnsupportedOperationException.class, () -> graph.getOutgoingFlows("_5").clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.getIncomingFlows("_6").add(flow));
        assertThrows(UnsupportedOperationException.class, () -> graph.getStartEvents().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.getEndEvents().clear());
        assertEquals(List.of(flow), graph.getOutgoingFlows("_5"));
        assertEquals(List.of(flow), graph.getIncomingFlows("_6"));
        assertEquals(20, graph.getProcess().getSequenceFlows().size());
    }

    @Test
    void supportsEmptyProcess() {
        ProcessGraph graph = new ProcessGraph(new BpmnProcess("empty", "", List.of(), List.of()));

        assertTrue(graph.getProcess().getNodes().isEmpty());
        assertTrue(graph.getProcess().getSequenceFlows().isEmpty());
        assertTrue(graph.getStartEvents().isEmpty());
        assertTrue(graph.getEndEvents().isEmpty());
    }

    @Test
    void keepsDistinctFlowsAndNodesWithTheSameNameIncludingSelfLoops() {
        BpmnNode first = new BpmnNode("a", "Mismo nombre", NodeType.TASK);
        BpmnNode second = new BpmnNode("b", "Mismo nombre", NodeType.TASK);
        SequenceFlow one = new SequenceFlow("one", "a", "b", "", "x > 0");
        SequenceFlow two = new SequenceFlow("two", "a", "b", "", "x <= 0");
        SequenceFlow loop = new SequenceFlow("loop", "b", "b", "", "");
        ProcessGraph graph = new ProcessGraph(new BpmnProcess("test", "",
                List.of(first, second), List.of(one, two, loop)));

        assertEquals(List.of(one, two), graph.getOutgoingFlows("a"));
        assertEquals(List.of(one, two, loop), graph.getIncomingFlows("b"));
        assertEquals(List.of(loop), graph.getOutgoingFlows("b"));
        assertSame(first, graph.getSourceNode(one));
        assertSame(second, graph.getTargetNode(one));
    }

    private BpmnProcess readApplication() throws IOException {
        return new BpmnParser().parse(Path.of("bpmn", "ApplicationProcess.bpmn"),
                "gov.medicaid.process.application");
    }
}
