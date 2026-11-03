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
import java.util.Set;
import java.util.stream.Collectors;

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
        assertEquals(List.of(List.of("split>a", "split>b")), paths.get(0).getConcurrentBranches().stream()
                .map(group -> group.stream().map(SequenceFlow::getId).toList()).toList());
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
        assertThrows(UnsupportedOperationException.class, () -> first.get(0).getConcurrentBranches().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.get(0).getConcurrentBranches().get(0).clear());
        assertEquals(Set.of("_7-_34", "_7-_10"), first.get(0).getConcurrentBranches().get(0).stream()
                .map(SequenceFlow::getId).collect(Collectors.toSet()));
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
                "start:START_EVENT,choice:EVENT_BASED_GATEWAY,a:TASK,b:TASK,end:END_EVENT",
                "start>choice,choice>a,choice>b,a>end,b>end"), 2));
    }

    @Test
    void exploresEveryNonemptyInclusiveCombinationAndWaitsForSelectedBranches() {
        // La convergencia aparece antes de las ramas para detectar un disparo prematuro.
        ProcessGraph graph = graph("start:START_EVENT,split:INCLUSIVE_GATEWAY,join:INCLUSIVE_GATEWAY,"
                + "end:END_EVENT,a:TASK,b:TASK,c:TASK",
                "start>split,split>a,split>b,split>c,a>join,b>join,c>join,join>end");

        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertEquals(7, paths.size());
        Set<Set<String>> taskSets = paths.stream().map(this::taskIds).collect(Collectors.toSet());
        assertEquals(Set.of(Set.of("a"), Set.of("b"), Set.of("c"), Set.of("a", "b"),
                Set.of("a", "c"), Set.of("b", "c"), Set.of("a", "b", "c")), taskSets);
        for (ProcessPath path : paths) {
            List<String> ids = nodeIds(path);
            assertTrue(path.isComplete());
            assertEquals(1, ids.stream().filter("join"::equals).count());
            assertEquals("end", ids.get(ids.size() - 1));
            taskIds(path).forEach(id -> assertTrue(ids.indexOf(id) < ids.indexOf("join")));
            assertEquals(taskIds(path).size() > 1 ? 1 : 0, path.getConcurrentBranches().size());
        }
    }

    @Test
    void supportsAnInclusiveGatewayWithOnlyOneOutgoingFlow() {
        List<ProcessPath> paths = analyzer.analyze(graph("start:START_EVENT,or:INCLUSIVE_GATEWAY,end:END_EVENT",
                "start>or,or>end"), 2);

        assertEquals(1, paths.size());
        assertTrue(paths.get(0).isComplete());
        assertTrue(paths.get(0).getConcurrentBranches().isEmpty());
    }

    @Test
    void inclusiveJoinDoesNotWaitForAnUnselectedExclusiveBranch() {
        List<ProcessPath> paths = analyzer.analyze(graph("start:START_EVENT,split:EXCLUSIVE_GATEWAY,"
                + "join:INCLUSIVE_GATEWAY,end:END_EVENT,a:TASK,b:TASK",
                "start>split,split>a,split>b,a>join,b>join,join>end"), 2);

        assertEquals(2, paths.size());
        assertTrue(paths.stream().allMatch(ProcessPath::isComplete));
        paths.forEach(path -> assertEquals(1, taskIds(path).size()));
    }

    @Test
    void handlesNestedInclusiveBranchesWithoutDuplicatingJoins() {
        ProcessGraph graph = graph("start:START_EVENT,split:INCLUSIVE_GATEWAY,outer:INCLUSIVE_GATEWAY,"
                + "end:END_EVENT,a:TASK,inner:INCLUSIVE_GATEWAY,join:INCLUSIVE_GATEWAY,b:TASK,c:TASK",
                "start>split,split>a,split>inner,a>outer,inner>b,inner>c,b>join,c>join,join>outer,outer>end");

        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertEquals(7, paths.size());
        assertEquals(7, paths.stream().map(this::taskIds).distinct().count());
        for (ProcessPath path : paths) {
            List<String> ids = nodeIds(path);
            assertTrue(path.isComplete());
            assertEquals(1, ids.stream().filter("outer"::equals).count());
            if (ids.contains("inner")) {
                assertEquals(1, ids.stream().filter("join"::equals).count());
                assertTrue(ids.indexOf("join") < ids.indexOf("outer"));
            }
        }
    }

    @Test
    void inclusiveJoinDoesNotWaitForUnrelatedParallelWork() {
        ProcessGraph graph = graph("start:START_EVENT,and:PARALLEL_GATEWAY,split:INCLUSIVE_GATEWAY,"
                + "a:TASK,join:INCLUSIVE_GATEWAY,local:TASK,b:TASK,other:TASK,"
                + "finish:PARALLEL_GATEWAY,end:END_EVENT",
                "start>and,and>split,and>other,split>a,split>b,a>join,b>join,join>local,"
                        + "local>finish,other>finish,finish>end");

        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertEquals(3, paths.size());
        for (ProcessPath path : paths) {
            List<String> ids = nodeIds(path);
            assertTrue(path.isComplete());
            assertTrue(ids.indexOf("join") < ids.indexOf("other"));
            assertTrue(ids.indexOf("local") < ids.indexOf("finish"));
            assertTrue(ids.indexOf("other") < ids.indexOf("finish"));
        }
    }

    @ParameterizedTest
    @CsvSource({"EXCLUSIVE_GATEWAY,2", "INCLUSIVE_GATEWAY,3"})
    void keepsUnevaluatedConditionsEvenWhenAStructuralCandidateCannotBeExecuted(String type, int count) {
        BpmnProcess original = graph("start:START_EVENT,split:" + type + ",a:TASK,b:TASK,"
                + "join:INCLUSIVE_GATEWAY,end:END_EVENT",
                "start>split,split>a,split>b,a>join,b>join,join>end").getProcess();
        List<SequenceFlow> flows = original.getSequenceFlows().stream().map(flow -> new SequenceFlow(
                flow.getId(), flow.getSourceRef(), flow.getTargetRef(), flow.getName(),
                flow.getId().equals("split>a") ? "false" : "unknownService.evaluate(model)")).toList();
        ProcessGraph graph = new ProcessGraph(new BpmnProcess("test", "",
                List.copyOf(original.getNodes().values()), flows));

        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertEquals(count, paths.size());
        assertTrue(paths.stream().allMatch(ProcessPath::isComplete));
        assertTrue(paths.stream().flatMap(path -> path.getFlows().stream())
                .anyMatch(flow -> flow.getCondition().equals("false")));
        paths.forEach(path -> path.getFlows().forEach(flow -> assertTrue(flows.contains(flow))));
        if (type.equals("EXCLUSIVE_GATEWAY")) {
            paths.forEach(path -> assertEquals(1, taskIds(path).size()));
        } else {
            assertTrue(paths.stream().anyMatch(path -> taskIds(path).equals(Set.of("a", "b"))));
        }
    }

    @Test
    void rejectsInclusiveConvergenceInsideACycleExplicitly() {
        ProcessGraph graph = graph("start:START_EVENT,join:INCLUSIVE_GATEWAY,choice:EXCLUSIVE_GATEWAY,"
                + "task:TASK,end:END_EVENT", "start>join,join>choice,choice>task,task>join,choice>end");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(graph, 2));

        assertTrue(error.getMessage().contains("Convergencia inclusiva dentro de un ciclo"));
        assertTrue(error.getMessage().contains("join"));
    }

    @Test
    void preservesCycleLimitsAfterInclusiveAlternatives() {
        ProcessGraph graph = graph("start:START_EVENT,or:INCLUSIVE_GATEWAY,a:TASK,b:TASK,"
                + "join:INCLUSIVE_GATEWAY,review:TASK,choice:EXCLUSIVE_GATEWAY,end:END_EVENT",
                "start>or,or>a,or>b,a>join,b>join,join>review,review>choice,choice>review,choice>end");

        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertEquals(9, paths.stream().filter(ProcessPath::isComplete).count());
        List<ProcessPath> truncated = paths.stream().filter(path -> !path.isComplete()).toList();
        assertEquals(3, truncated.size());
        for (ProcessPath path : truncated) {
            assertEquals(2, path.getReturnCount());
            assertEquals("choice>review", path.getBlockedFlow().getId());
            assertEquals(3, nodeIds(path).stream().filter("review"::equals).count());
        }
    }

    @Test
    void truncatesAnInclusiveActivationAtomicallyWithoutDuplicatePrefixes() {
        ProcessGraph graph = graph("start:START_EVENT,review:TASK,or:INCLUSIVE_GATEWAY,a:TASK,end:END_EVENT",
                "start>review,review>or,or>a,or>review,a>end");

        List<ProcessPath> paths = analyzer.analyze(graph, 0);

        assertEquals(2, paths.size());
        assertEquals(1, paths.stream().filter(ProcessPath::isComplete).count());
        ProcessPath truncated = paths.stream().filter(path -> !path.isComplete()).findFirst().orElseThrow();
        assertEquals("or>review", truncated.getBlockedFlow().getId());
        assertEquals(List.of("start>review", "review>or"), flowIds(truncated));
        assertTrue(truncated.getConcurrentBranches().isEmpty());
        assertEquals(0, truncated.getReturnCount());
    }

    @Test
    void analyzesHardwareWithBothParallelBranchesAndAllInclusiveCombinations() throws IOException {
        ProcessGraph graph = new ProcessGraph(new BpmnParser().parse(Path.of("bpmn", "HardwareRetailer.bpmn"), "_6-1"));

        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertEquals(4, paths.size());
        assertEquals(Set.of(Set.of("_6-102", "_6-226", "_6-277", "_6-153", "_6-569"),
                Set.of("_6-102", "_6-379", "_6-452", "_6-153", "_6-569"),
                Set.of("_6-102", "_6-379", "_6-328", "_6-153", "_6-569"),
                Set.of("_6-102", "_6-379", "_6-452", "_6-328", "_6-153", "_6-569")),
                paths.stream().map(this::taskIds).collect(Collectors.toSet()));
        for (ProcessPath path : paths) {
            List<String> ids = nodeIds(path);
            assertTrue(path.isComplete());
            assertEquals("_6-620", ids.get(ids.size() - 1));
            assertEquals(0, path.getReturnCount());
            assertEquals(ids.size(), ids.stream().distinct().count());
            assertTrue(ids.indexOf("_6-153") < ids.indexOf("_6-547"));
            assertTrue(ids.indexOf("_6-525") < ids.indexOf("_6-547"));
            assertTrue(ids.indexOf("_6-547") < ids.indexOf("_6-569"));
            for (String task : List.of("_6-452", "_6-328")) {
                if (ids.contains(task)) {
                    assertTrue(ids.indexOf(task) < ids.indexOf("_6-503"));
                }
            }
            assertEquals(Set.of("_6-635", "_6-667"), path.getConcurrentBranches().get(0).stream()
                    .map(SequenceFlow::getId).collect(Collectors.toSet()));
            for (SequenceFlow flow : path.getFlows()) {
                assertTrue(graph.getProcess().getSequenceFlows().contains(flow));
                assertTrue(ids.containsAll(List.of(flow.getSourceRef(), flow.getTargetRef())));
            }
        }
        // El algoritmo general no depende de los IDs del BPMN de referencia.
        List<BpmnNode> renamedNodes = graph.getProcess().getNodes().values().stream()
                .map(node -> new BpmnNode("renamed" + node.getId(), node.getName(), node.getType())).toList();
        List<SequenceFlow> renamedFlows = graph.getProcess().getSequenceFlows().stream()
                .map(flow -> new SequenceFlow("renamed" + flow.getId(), "renamed" + flow.getSourceRef(),
                        "renamed" + flow.getTargetRef(), flow.getName(), flow.getCondition())).toList();
        List<ProcessPath> renamed = analyzer.analyze(new ProcessGraph(new BpmnProcess("renamed", "",
                renamedNodes, renamedFlows)), 2);
        assertEquals(paths.stream().map(this::flowIds).toList(), renamed.stream().map(path ->
                flowIds(path).stream().map(id -> id.substring("renamed".length())).toList()).toList());
    }

    private Set<String> taskIds(ProcessPath path) {
        return path.getNodes().stream().filter(node -> node.getType().isTask())
                .map(BpmnNode::getId).collect(Collectors.toSet());
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
