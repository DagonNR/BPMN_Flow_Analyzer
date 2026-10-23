package com.pia.vvs.analysis;

import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import com.pia.vvs.parser.BpmnParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathAnalyzerTest {
    private final PathAnalyzer analyzer = new PathAnalyzer();

    @Test
    void followsASequenceFromStartToEnd() {
        ProcessGraph graph = graph("start:START_EVENT,task:TASK,end:END_EVENT", "start>task,task>end");

        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertEquals(1, paths.size());
        assertTrue(paths.get(0).isComplete());
        assertEquals(List.of("start", "task", "end"), nodeIds(paths.get(0)));
        assertEquals(List.of("start>task", "task>end"), flowIds(paths.get(0)));
        assertNull(paths.get(0).getBlockedFlow());
    }

    @Test
    void exploresExclusiveAlternativesAndMergesWithoutWaiting() {
        ProcessGraph graph = graph("start:START_EVENT,split:EXCLUSIVE_GATEWAY,a:TASK,b:TASK,"
                + "join:EXCLUSIVE_GATEWAY,end:END_EVENT", "start>split,split>a,split>b,a>join,b>join,join>end");

        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertEquals(List.of(List.of("start", "split", "a", "join", "end"),
                List.of("start", "split", "b", "join", "end")),
                paths.stream().map(this::nodeIds).toList());
        assertTrue(paths.stream().allMatch(ProcessPath::isComplete));
    }

    @ParameterizedTest
    @CsvSource({"0,1,1", "1,2,2", "2,3,3", "100,101,101"})
    void limitsReturnsRatherThanVisits(int limit, int completeCount, int truncatedVisits) {
        ProcessGraph graph = graph("start:START_EVENT,review:USER_TASK,choice:EXCLUSIVE_GATEWAY,end:END_EVENT",
                "start>review,review>choice,choice>end,choice>review");

        List<ProcessPath> paths = analyzer.analyze(graph, limit);

        assertEquals(completeCount, paths.stream().filter(ProcessPath::isComplete).count());
        ProcessPath truncated = paths.stream().filter(path -> !path.isComplete()).findFirst().orElseThrow();
        assertEquals("choice>review", truncated.getBlockedFlow().getId());
        assertEquals(limit, flowIds(truncated).stream().filter("choice>review"::equals).count());
        assertEquals(truncatedVisits, nodeIds(truncated).stream().filter("review"::equals).count());
        assertFalse(nodeIds(truncated).contains("end"));
    }

    @Test
    void waitsForEveryParallelBranchAndDoesNotEnumerateTheirPermutations() {
        ProcessGraph graph = graph("start:START_EVENT,split:PARALLEL_GATEWAY,a:TASK,join:PARALLEL_GATEWAY,"
                + "after:TASK,b:TASK,end:END_EVENT", "start>split,split>a,split>b,a>join,b>join,join>after,after>end");

        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertEquals(1, paths.size());
        assertEquals(List.of("start", "split", "a", "b", "join", "after", "end"), nodeIds(paths.get(0)));
        assertTrue(paths.get(0).isComplete());
    }

    @Test
    void reportsAnUnsatisfiedParallelJoin() {
        ProcessGraph graph = graph("start:START_EVENT,split:EXCLUSIVE_GATEWAY,a:TASK,b:TASK,"
                + "join:PARALLEL_GATEWAY,end:END_EVENT", "start>split,split>a,split>b,a>join,b>join,join>end");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze(graph, 2));

        assertTrue(error.getMessage().contains("sincronización"));
    }

    @ParameterizedTest
    @CsvSource({"0,3", "1,5", "2,7"})
    void findsApplicationOutcomesAtEachReturnLimit(int limit, int completeCount) throws IOException {
        List<ProcessPath> paths = analyzer.analyze(application(), limit);

        assertEquals(completeCount, paths.stream().filter(ProcessPath::isComplete).count());
        assertEquals(1, paths.stream().filter(path -> !path.isComplete()).count());
        for (ProcessPath path : paths) {
            List<String> ids = nodeIds(path);
            if (ids.contains("_3")) {
                assertTrue(ids.containsAll(List.of("_10", "_34")));
                assertTrue(ids.indexOf("_10") < ids.indexOf("_15"));
                assertTrue(ids.indexOf("_34") < ids.indexOf("_15"));
            }
            if (path.isComplete()) {
                String end = ids.get(ids.size() - 1);
                assertTrue(List.of("_2", "_19", "_22").contains(end));
                if (end.equals("_2")) {
                    assertFalse(ids.contains("_3"));
                    assertFalse(ids.contains("_10"));
                    assertFalse(ids.contains("_34"));
                }
            } else {
                assertEquals("_32-_33", path.getBlockedFlow().getId());
                assertEquals(limit, flowIds(path).stream().filter("_32-_33"::equals).count());
                assertEquals(limit + 1, ids.stream().filter("_3"::equals).count());
            }
        }
        for (int returns = 0; returns <= limit; returns++) {
            int expectedReturns = returns;
            List<ProcessPath> matching = paths.stream().filter(ProcessPath::isComplete)
                    .filter(path -> flowIds(path).stream().filter("_32-_33"::equals).count() == expectedReturns)
                    .filter(path -> nodeIds(path).contains("_3")).toList();
            assertEquals(List.of("_19", "_22"), matching.stream()
                    .map(path -> nodeIds(path).get(path.getNodes().size() - 1)).sorted().toList());
        }
    }

    @Test
    void keepsCountersLocalAndResultsImmutable() throws IOException {
        ProcessGraph graph = application();
        List<ProcessPath> first = analyzer.analyze(graph, 2);
        List<ProcessPath> second = analyzer.analyze(graph, 2);

        assertEquals(first.stream().map(this::flowIds).toList(), second.stream().map(this::flowIds).toList());
        assertThrows(UnsupportedOperationException.class, () -> first.get(0).getNodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.get(0).getFlows().clear());
        assertEquals(20, graph.getProcess().getSequenceFlows().size());
    }

    @Test
    void rejectsChangedApplicationConditionsAndConnections() throws IOException {
        BpmnProcess original = application().getProcess();
        List<SequenceFlow> changed = new ArrayList<>(original.getSequenceFlows());
        SequenceFlow flow = changed.stream().filter(item -> item.getId().equals("_7-_34")).findFirst().orElseThrow();
        changed.set(changed.indexOf(flow), new SequenceFlow(flow.getId(), flow.getSourceRef(), flow.getTargetRef(),
                flow.getName(), "return true;"));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(new ProcessGraph(new BpmnProcess(
                original.getId(), "", List.copyOf(original.getNodes().values()), changed)), 2));

        changed.set(changed.indexOf(changed.stream().filter(item -> item.getId().equals("_7-_34"))
                .findFirst().orElseThrow()), new SequenceFlow(flow.getId(), "_7", "_9", "success", flow.getCondition()));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(new ProcessGraph(new BpmnProcess(
                original.getId(), "", List.copyOf(original.getNodes().values()), changed)), 2));
    }

    @Test
    void rejectsMissingStartDeadEndsNegativeLimitsAndUnsupportedGateways() {
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze(graph("task:TASK", ""), 2));
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze(graph("start:START_EVENT,task:TASK", "start>task"), 2));
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze(graph("start:START_EVENT,end:END_EVENT", "start>end"), -1));
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze(graph("start:START_EVENT,end:END_EVENT", "start>end"), 101));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(graph(
                "start:START_EVENT,choice:INCLUSIVE_GATEWAY,a:TASK,b:TASK,end:END_EVENT",
                "start>choice,choice>a,choice>b,a>end,b>end"), 2));
    }

    private ProcessGraph application() throws IOException {
        return new ProcessGraph(new BpmnParser().parse(Path.of("bpmn", "ApplicationProcess.bpmn"),
                "gov.medicaid.process.application"));
    }

    private ProcessGraph graph(String nodeText, String flowText) {
        List<BpmnNode> nodes = new ArrayList<>();
        for (String entry : nodeText.split(",")) {
            String[] parts = entry.split(":");
            nodes.add(new BpmnNode(parts[0], parts[0], NodeType.valueOf(parts[1])));
        }
        List<SequenceFlow> flows = new ArrayList<>();
        if (!flowText.isEmpty()) {
            for (String entry : flowText.split(",")) {
                String[] ends = entry.split(">");
                flows.add(new SequenceFlow(entry, ends[0], ends[1], "", ""));
            }
        }
        return new ProcessGraph(new BpmnProcess("test", "", nodes, flows));
    }

    private List<String> nodeIds(ProcessPath path) {
        return path.getNodes().stream().map(BpmnNode::getId).toList();
    }

    private List<String> flowIds(ProcessPath path) {
        return path.getFlows().stream().map(SequenceFlow::getId).toList();
    }
}
