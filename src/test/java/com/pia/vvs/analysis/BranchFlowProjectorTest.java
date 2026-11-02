package com.pia.vvs.analysis;

import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import com.pia.vvs.output.FlowTable;
import com.pia.vvs.output.GherkinGenerator;
import com.pia.vvs.parser.BpmnParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BranchFlowProjectorTest {
    private final PathAnalyzer analyzer = new PathAnalyzer();
    private final BranchFlowProjector projector = new BranchFlowProjector();

    @Test
    void projectsASequenceWithoutCallingItAnIndependentExecution() {
        ProcessGraph graph = graph("start:START_EVENT,task:TASK,end:END_EVENT", "start>task,task>end");
        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        List<BranchFlow> rows = projector.project(graph, paths);

        assertEquals(1, rows.size());
        assertEquals(paths.get(0).getNodes(), rows.get(0).getNodes());
        assertEquals(paths.get(0).getFlows(), rows.get(0).getFlows());
        assertEquals(List.of(1), rows.get(0).getSourcePathNumbers());
        assertTrue(rows.get(0).getSelectedParallelFlows().isEmpty());
        String csv = new FlowTable().branchesToCsv(graph.getProcess(), rows);
        assertTrue(csv.contains("BRANCH_VIEW"));
        assertFalse(csv.contains("COMPLETE"));
    }

    @Test
    void separatesParallelBranchesAndRetainsCommonPrefixAndContinuation() {
        ProcessGraph graph = graph("start:START_EVENT,before:TASK,split:PARALLEL_GATEWAY,"
                + "join:PARALLEL_GATEWAY,after:TASK,end:END_EVENT,a:TASK,b:TASK",
                "start>before,before>split,split>a,split>b,a>join,b>join,join>after,after>end");
        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        List<BranchFlow> rows = projector.project(graph, paths);

        assertEquals(1, paths.size());
        assertTrue(paths.get(0).isComplete());
        assertEquals(Set.of(Set.of("before", "a", "after"), Set.of("before", "b", "after")), taskSets(rows));
        for (BranchFlow row : rows) {
            assertEquals(List.of(1), row.getSourcePathNumbers());
            assertEquals(1, row.getSelectedParallelFlows().size());
            List<String> ids = row.getNodes().stream().map(BpmnNode::getId).toList();
            assertTrue(ids.indexOf("join") < ids.indexOf("after"));
            assertEquals(1, row.getFlows().stream().filter(flow -> flow.getTargetRef().equals("join")).count());
            assertTrue(row.getFlows().stream().allMatch(graph.getProcess().getSequenceFlows()::contains));
        }
    }

    @Test
    void keepsExclusiveAlternativesButAggregatesTheUnchangedParallelBranch() {
        ProcessGraph graph = graph("start:START_EVENT,split:PARALLEL_GATEWAY,xor:EXCLUSIVE_GATEWAY,"
                + "a:TASK,b:TASK,merge:EXCLUSIVE_GATEWAY,other:TASK,join:PARALLEL_GATEWAY,end:END_EVENT",
                "start>split,split>xor,split>other,xor>a,xor>b,a>merge,b>merge,merge>join,other>join,join>end");
        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        List<BranchFlow> rows = projector.project(graph, paths);

        assertEquals(2, paths.size());
        assertEquals(3, rows.size());
        assertEquals(Set.of(Set.of("a"), Set.of("b"), Set.of("other")), taskSets(rows));
        assertEquals(List.of(1, 2), rows.stream().filter(row -> taskIds(row).equals(Set.of("other")))
                .findFirst().orElseThrow().getSourcePathNumbers());
    }

    @Test
    void retainsInclusiveCombinationsInsteadOfSplittingThemAsExclusiveOrParallelChoices() {
        ProcessGraph graph = graph("start:START_EVENT,split:INCLUSIVE_GATEWAY,a:TASK,b:TASK,"
                + "join:INCLUSIVE_GATEWAY,end:END_EVENT", "start>split,split>a,split>b,a>join,b>join,join>end");
        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        List<BranchFlow> rows = projector.project(graph, paths);

        assertEquals(3, rows.size());
        assertEquals(Set.of(Set.of("a"), Set.of("b"), Set.of("a", "b")), taskSets(rows));
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(paths.get(i).getNodes(), rows.get(i).getNodes());
            assertTrue(rows.get(i).getSelectedParallelFlows().isEmpty());
        }
    }

    @Test
    void expandsOnlyNestedParallelBranchesThatRemainInTheProjection() {
        ProcessGraph graph = graph("start:START_EVENT,outer:PARALLEL_GATEWAY,pre:TASK,inner:PARALLEL_GATEWAY,"
                + "a:TASK,b:TASK,innerJoin:PARALLEL_GATEWAY,post:TASK,c:TASK,join:PARALLEL_GATEWAY,end:END_EVENT",
                "start>outer,outer>pre,outer>c,pre>inner,inner>a,inner>b,a>innerJoin,b>innerJoin,"
                        + "innerJoin>post,post>join,c>join,join>end");

        List<BranchFlow> rows = project(graph);

        assertEquals(3, rows.size());
        assertEquals(Set.of(Set.of("pre", "a", "post"), Set.of("pre", "b", "post"), Set.of("c")), taskSets(rows));
        assertEquals(1, rows.stream().filter(row -> taskIds(row).equals(Set.of("c")))
                .findFirst().orElseThrow().getSelectedParallelFlows().size());
    }

    @Test
    void combinesChoicesOfSuccessiveParallelRegions() {
        ProcessGraph graph = graph("start:START_EVENT,first:PARALLEL_GATEWAY,a:TASK,b:TASK,"
                + "join:PARALLEL_GATEWAY,second:PARALLEL_GATEWAY,c:TASK,d:TASK,finish:PARALLEL_GATEWAY,end:END_EVENT",
                "start>first,first>a,first>b,a>join,b>join,join>second,second>c,second>d,c>finish,d>finish,finish>end");

        List<BranchFlow> rows = project(graph);

        assertEquals(4, rows.size());
        assertEquals(Set.of(Set.of("a", "c"), Set.of("a", "d"), Set.of("b", "c"), Set.of("b", "d")), taskSets(rows));
        rows.forEach(row -> assertEquals(2, row.getSelectedParallelFlows().size()));
    }

    @Test
    void supportsAGatewayThatJoinsAndSplitsAndABranchWithoutTasks() {
        ProcessGraph graph = graph("start:START_EVENT,first:PARALLEL_GATEWAY,a:TASK,"
                + "mixed:PARALLEL_GATEWAY,b:TASK,c:TASK,finish:PARALLEL_GATEWAY,end:END_EVENT",
                "start>first,first>a,first>mixed,a>mixed,mixed>b,mixed>c,b>finish,c>finish,finish>end");

        List<BranchFlow> rows = project(graph);

        assertEquals(4, rows.size());
        assertEquals(Set.of(Set.of("a", "b"), Set.of("a", "c"), Set.of("b"), Set.of("c")), taskSets(rows));
        for (BranchFlow row : rows) {
            List<String> ids = row.getFlows().stream().map(SequenceFlow::getId).toList();
            assertNotEquals(ids.contains("first>a"), ids.contains("first>mixed"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void preservesLoopOccurrencesWithinABranchAndExcludesTruncatedSources(int limit) {
        ProcessGraph graph = graph("start:START_EVENT,split:PARALLEL_GATEWAY,review:TASK,choice:EXCLUSIVE_GATEWAY,"
                + "other:TASK,join:PARALLEL_GATEWAY,end:END_EVENT",
                "start>split,split>review,split>other,review>choice,choice>join,choice>review,other>join,join>end");
        List<ProcessPath> paths = analyzer.analyze(graph, limit);

        List<BranchFlow> rows = projector.project(graph, paths);

        assertEquals(limit + 1, paths.stream().filter(ProcessPath::isComplete).count());
        assertEquals(limit + 2, rows.size());
        assertEquals(1, paths.stream().filter(path -> !path.isComplete()).count());
        List<BranchFlow> reviewRows = rows.stream().filter(row -> taskIds(row).contains("review")).toList();
        assertEquals(limit + 1, reviewRows.size()); // Mismas X, distintas ocurrencias y flujos de retorno.
        for (int returns = 0; returns <= limit; returns++) {
            int expected = returns;
            assertTrue(reviewRows.stream().anyMatch(row -> row.getFlows().stream()
                    .filter(flow -> flow.getId().equals("choice>review")).count() == expected));
        }
        rows.forEach(row -> row.getSourcePathNumbers().forEach(number -> assertTrue(paths.get(number - 1).isComplete())));
    }

    @Test
    void rejectsRepeatedParallelSplitsWithoutChangingTheJointPaths() {
        ProcessGraph graph = graph("start:START_EVENT,before:TASK,split:PARALLEL_GATEWAY,a:TASK,b:TASK,"
                + "join:PARALLEL_GATEWAY,choice:EXCLUSIVE_GATEWAY,end:END_EVENT",
                "start>before,before>split,split>a,split>b,a>join,b>join,join>choice,choice>before,choice>end");
        List<ProcessPath> paths = analyzer.analyze(graph, 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> projector.project(graph, paths));

        assertTrue(error.getMessage().contains("varias veces"));
        assertEquals(2, paths.stream().filter(ProcessPath::isComplete).count());
        assertEquals(1, paths.stream().filter(path -> !path.isComplete()).count());
    }

    @Test
    void rejectsParallelRegionsWithoutACommonJoin() {
        ProcessGraph graph = graph("start:START_EVENT,split:PARALLEL_GATEWAY,a:TASK,b:TASK,"
                + "endA:END_EVENT,endB:END_EVENT", "start>split,split>a,split>b,a>endA,b>endB");
        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> projector.project(graph, paths));

        assertTrue(error.getMessage().contains("convergencia AND común"));
        assertTrue(paths.get(0).isComplete());
    }

    @Test
    void rejectsOverlappingBranchesInsteadOfAssigningSharedTasksArbitrarily() {
        ProcessGraph graph = graph("start:START_EVENT,split:PARALLEL_GATEWAY,a:TASK,b:TASK,"
                + "merge:EXCLUSIVE_GATEWAY,inner:PARALLEL_GATEWAY,c:TASK,d:TASK,join:PARALLEL_GATEWAY,end:END_EVENT",
                "start>split,split>a,split>b,a>merge,b>merge,merge>inner,inner>c,inner>d,c>join,d>join,join>end");
        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        assertThrows(IllegalArgumentException.class, () -> projector.project(graph, paths));
        assertTrue(paths.get(0).isComplete());
    }

    @Test
    void hardwareMatchesAllFiveRowsOfTableIIByTaskIds() throws IOException {
        ProcessGraph graph = hardware();
        List<ProcessPath> paths = analyzer.analyze(graph, 2);

        List<BranchFlow> rows = projector.project(graph, paths);

        assertEquals(4, paths.size());
        assertTrue(paths.stream().allMatch(ProcessPath::isComplete));
        assertEquals(5, rows.size());
        assertEquals(Set.of(
                Set.of("_6-102", "_6-226", "_6-277", "_6-569"),
                Set.of("_6-102", "_6-379", "_6-452", "_6-569"),
                Set.of("_6-102", "_6-379", "_6-328", "_6-569"),
                Set.of("_6-153", "_6-569"),
                Set.of("_6-102", "_6-379", "_6-452", "_6-328", "_6-569")), taskSets(rows));
        BranchFlow packaging = rows.stream().filter(row -> taskIds(row).contains("_6-153")).findFirst().orElseThrow();
        assertEquals(List.of(1, 2, 3, 4), packaging.getSourcePathNumbers());
        for (int i = 0; i < paths.size(); i++) {
            int source = i + 1;
            Set<String> union = rows.stream().filter(row -> row.getSourcePathNumbers().contains(source))
                    .flatMap(row -> taskIds(row).stream()).collect(Collectors.toSet());
            assertEquals(paths.get(i).getNodes().stream().filter(node -> node.getType().isTask())
                    .map(BpmnNode::getId).collect(Collectors.toSet()), union);
        }
        rows.forEach(row -> row.getFlows().forEach(flow -> assertTrue(graph.getProcess().getSequenceFlows().contains(flow))));
    }

    @Test
    void hardwareProjectionDoesNotDependOnIdsNamesOrNodeDeclarationOrder() throws IOException {
        ProcessGraph original = hardware();
        List<BpmnNode> nodes = new ArrayList<>(original.getProcess().getNodes().values().stream()
                .map(node -> new BpmnNode("renamed" + node.getId(), "same name", node.getType())).toList());
        Collections.reverse(nodes);
        List<SequenceFlow> flows = original.getProcess().getSequenceFlows().stream()
                .map(flow -> new SequenceFlow("renamed" + flow.getId(), "renamed" + flow.getSourceRef(),
                        "renamed" + flow.getTargetRef(), "", flow.getCondition())).toList();

        List<BranchFlow> renamed = project(new ProcessGraph(new BpmnProcess("other-process", "", nodes, flows)));

        assertEquals(5, renamed.size());
        Set<Set<String>> normalized = renamed.stream().map(row -> taskIds(row).stream()
                .map(id -> id.substring("renamed".length())).collect(Collectors.toSet())).collect(Collectors.toSet());
        assertEquals(taskSets(project(original)), normalized);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void applicationKeepsItsInclusiveBranchesCyclesCsvAndGherkin(int limit) throws IOException {
        ProcessGraph graph = new ProcessGraph(new BpmnParser().parse(Path.of("bpmn/ApplicationProcess.bpmn"),
                ApplicationProcessRules.PROCESS_ID));
        List<ProcessPath> paths = analyzer.analyze(graph, limit);
        String originalCsv = new FlowTable().toCsv(graph.getProcess(), paths);
        String originalFeature = new GherkinGenerator().generate(graph.getProcess(), paths);

        List<BranchFlow> rows = projector.project(graph, paths);

        assertEquals(3 + 2 * limit, rows.size());
        assertEquals(4 + 2 * limit, paths.size());
        assertEquals(1, paths.stream().filter(path -> !path.isComplete()).count());
        for (BranchFlow row : rows) {
            ProcessPath source = paths.get(row.getSourcePathNumbers().get(0) - 1);
            assertTrue(source.isComplete());
            assertEquals(source.getNodes(), row.getNodes());
            assertEquals(source.getFlows(), row.getFlows());
            assertTrue(row.getSelectedParallelFlows().isEmpty()); // AND convergente, pero sin bifurcación AND.
            if (taskIds(row).contains("_34")) {
                assertTrue(taskIds(row).contains("_10"));
            }
        }
        assertEquals(originalCsv, new FlowTable().toCsv(graph.getProcess(), paths));
        assertEquals(originalFeature, new GherkinGenerator().generate(graph.getProcess(), paths));
    }

    @Test
    void doesNotCollapseDifferentConnectionsWithIdenticalTaskColumns() {
        BpmnProcess base = graph("start:START_EVENT,split:PARALLEL_GATEWAY,join:PARALLEL_GATEWAY,"
                + "after:TASK,end:END_EVENT", "start>split,split>join,join>after,after>end").getProcess();
        List<SequenceFlow> flows = new ArrayList<>(base.getSequenceFlows());
        flows.add(new SequenceFlow("second-direct-branch", "split", "join", "", ""));
        ProcessGraph graph = new ProcessGraph(new BpmnProcess("test", "", List.copyOf(base.getNodes().values()), flows));

        List<BranchFlow> rows = project(graph);

        assertEquals(2, rows.size());
        assertEquals(Set.of(Set.of("after")), taskSets(rows));
        assertNotEquals(rows.get(0).getFlows(), rows.get(1).getFlows());
        assertNotEquals(rows.get(0).getSelectedParallelFlows(), rows.get(1).getSelectedParallelFlows());
    }

    @Test
    void returnsImmutableViewsAndAnEmptyListWhenThereAreNoCompletePaths() {
        ProcessGraph graph = graph("start:START_EVENT,task:TASK", "start>task,task>task");
        assertTrue(projector.project(graph, analyzer.analyze(graph, 0)).isEmpty());
        ProcessGraph simple = graph("start:START_EVENT,split:PARALLEL_GATEWAY,a:TASK,b:TASK,"
                + "join:PARALLEL_GATEWAY,end:END_EVENT", "start>split,split>a,split>b,a>join,b>join,join>end");
        List<BranchFlow> rows = project(simple);
        assertThrows(UnsupportedOperationException.class, () -> rows.clear());
        assertThrows(UnsupportedOperationException.class, () -> rows.get(0).getNodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> rows.get(0).getFlows().clear());
        assertThrows(UnsupportedOperationException.class, () -> rows.get(0).getSelectedParallelFlows().clear());
        assertThrows(UnsupportedOperationException.class, () -> rows.get(0).getSourcePathNumbers().clear());
    }

    private ProcessGraph hardware() throws IOException {
        return new ProcessGraph(new BpmnParser().parse(Path.of("bpmn/HardwareRetailer.bpmn"), "_6-1"));
    }

    private List<BranchFlow> project(ProcessGraph graph) {
        return projector.project(graph, analyzer.analyze(graph, 2));
    }

    private Set<String> taskIds(BranchFlow row) {
        return row.getNodes().stream().filter(node -> node.getType().isTask()).map(BpmnNode::getId).collect(Collectors.toSet());
    }

    private Set<Set<String>> taskSets(List<BranchFlow> rows) {
        return rows.stream().map(this::taskIds).collect(Collectors.toSet());
    }

    private ProcessGraph graph(String nodeText, String flowText) {
        List<BpmnNode> nodes = new ArrayList<>();
        for (String entry : nodeText.split(",")) {
            String[] parts = entry.split(":");
            nodes.add(new BpmnNode(parts[0], parts[0], NodeType.valueOf(parts[1])));
        }
        List<SequenceFlow> flows = new ArrayList<>();
        for (String entry : flowText.split(",")) {
            String[] ends = entry.split(">");
            flows.add(new SequenceFlow(entry, ends[0], ends[1], "", ""));
        }
        return new ProcessGraph(new BpmnProcess("test", "", nodes, flows));
    }
}
