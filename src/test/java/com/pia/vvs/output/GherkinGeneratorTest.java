package com.pia.vvs.output;

import com.pia.vvs.analysis.PathAnalyzer;
import com.pia.vvs.analysis.BranchFlowProjector;
import com.pia.vvs.analysis.ProcessPath;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import com.pia.vvs.parser.BpmnParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Scenario;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GherkinGeneratorTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void formallyParsesApplicationAndPreservesEveryHumanReviewOccurrence(int limit) throws Exception {
        BpmnProcess process = application();
        var scenarios = parse(new GherkinGenerator().generate(process, paths(process, limit)), 3 + 2 * limit);
        assertEquals(2 * (limit + 1), scenarios.stream().filter(s -> s.getSteps().stream()
                .anyMatch(step -> step.getText().contains("el revisor"))).count());
        assertEquals((limit + 1) * (limit + 2), scenarios.stream().flatMap(s -> s.getSteps().stream())
                .filter(step -> step.getText().contains("el revisor")).count());
    }

    @Test
    void hardwareUsesCompletePathsAndKeepsBothParallelBranchesAndOrAlternatives() throws Exception {
        BpmnProcess process = new BpmnParser().parse(Path.of("bpmn/HardwareRetailer.bpmn"), "_6-1");
        var classification = TaskClassification.load(process, Path.of("bpmn/HardwareRetailer.tasks.properties"));
        var paths = paths(process, 2);
        String feature = new GherkinGenerator().generate(process, paths, classification);
        var scenarios = parse(feature, 4);
        for (int i = 0; i < scenarios.size(); i++) {
            List<String> actual = humanIds(scenarios.get(i));
            assertEquals(paths.get(i).getNodes().stream().filter(classification::isHuman).map(BpmnNode::getId).toList(), actual);
            assertTrue(actual.containsAll(List.of("_6-102", "_6-153", "_6-569")));
            assertTrue(feature.contains("# Flujo: F" + (i + 1)));
            assertFalse(actual.stream().anyMatch(Set.of("_6-226", "_6-452", "_6-328")::contains));
        }
        assertTrue(feature.contains("salidas concurrentes: _6-635, _6-667"));
        assertTrue(feature.contains("la alternativa BPMN: Special Carrier"));
        assertTrue(feature.contains("la alternativa BPMN: Normal Post"));
        assertTrue(feature.contains("la alternativa BPMN: extra insurance required"));
        assertTrue(feature.contains("la alternativa BPMN: Always"));
        // La proyección humana de las cinco filas de la Tabla II explica las tres variantes de la Figura 4.
        var projections = new BranchFlowProjector().project(new ProcessGraph(process), paths).stream()
                .map(branch -> branch.getNodes().stream().filter(classification::isHuman)
                        .map(BpmnNode::getId).collect(Collectors.toSet())).collect(Collectors.toSet());
        assertEquals(Set.of(Set.of("_6-102", "_6-277", "_6-569"), Set.of("_6-102", "_6-379", "_6-569"),
                Set.of("_6-153", "_6-569")), projections);
    }

    @Test
    void mergesDifferentAutomaticRoutesWithTheSameHumanActionsConditionsAndEnd() throws Exception {
        BpmnProcess process = graph("s:START_EVENT,h:USER_TASK,x:EXCLUSIVE_GATEWAY,a:SERVICE_TASK,b:SCRIPT_TASK,e:END_EVENT",
                "s>h,h>x,x>a,x>b,a>e,b>e");
        String feature = new GherkinGenerator().generate(process, paths(process, 0));
        assertEquals(List.of("h"), humanIds(parse(feature, 1).get(0)));
        assertTrue(feature.contains("# Flujo: F1, F2"));
        assertTrue(feature.contains("# SequenceFlow F2:"));
        assertTrue(feature.contains("a>e"));
        assertTrue(feature.contains("b>e"));
    }

    @Test
    void preservesDifferentConditionsEvenWhenHumanActionsAndEndMatch() throws Exception {
        BpmnProcess original = graph("s:START_EVENT,h:USER_TASK,x:EXCLUSIVE_GATEWAY,a:SERVICE_TASK,b:SCRIPT_TASK,e:END_EVENT",
                "s>h,h>x,x>a,x>b,a>e,b>e");
        List<SequenceFlow> flows = original.getSequenceFlows().stream().map(flow -> new SequenceFlow(flow.getId(),
                flow.getSourceRef(), flow.getTargetRef(), "", flow.getId().equals("x>a") ? "amount > 10"
                : flow.getId().equals("x>b") ? "amount <= 10" : "")).toList();
        BpmnProcess process = new BpmnProcess("test", "", new ArrayList<>(original.getNodes().values()), flows);
        String feature = new GherkinGenerator().generate(process, paths(process, 0));
        assertEquals(2, parse(feature, 2).size());
        assertTrue(feature.contains("amount > 10"));
        assertTrue(feature.contains("amount <= 10"));
    }

    @Test
    void preservesDifferentEndIdsAndHumanIdsEvenWhenNamesMatch() throws Exception {
        BpmnProcess original = graph("s:START_EVENT,x:EXCLUSIVE_GATEWAY,a:USER_TASK,b:USER_TASK,e:END_EVENT,f:END_EVENT",
                "s>x,x>a,x>b,a>e,b>f");
        BpmnProcess process = new BpmnProcess("test", "", original.getNodes().values().stream()
                .map(node -> new BpmnNode(node.getId(), "Mismo nombre", node.getType())).toList(), original.getSequenceFlows());
        var scenarios = parse(new GherkinGenerator().generate(process, paths(process, 0)), 2);
        assertEquals(Set.of(List.of("a"), List.of("b")), scenarios.stream().map(this::humanIds).collect(Collectors.toSet()));
        BpmnProcess commonAction = graph("s:START_EVENT,h:USER_TASK,x:EXCLUSIVE_GATEWAY,e:END_EVENT,f:END_EVENT",
                "s>h,h>x,x>e,x>f");
        parse(new GherkinGenerator().generate(commonAction, paths(commonAction, 0)), 2);
    }

    @Test
    void keepsHumanCyclesAndExcludesTruncationInGenericModels() throws Exception {
        BpmnProcess process = graph("s:START_EVENT,h:MANUAL_TASK,x:EXCLUSIVE_GATEWAY,e:END_EVENT", "s>h,h>x,x>h,x>e");
        var paths = paths(process, 2);
        assertEquals(1, paths.stream().filter(path -> !path.isComplete()).count());
        String feature = new GherkinGenerator().generate(process, paths);
        var scenarios = parse(feature, 3);
        assertEquals(Set.of(1, 2, 3), scenarios.stream().map(s -> humanIds(s).size()).collect(Collectors.toSet()));
        for (int i = 0; i < paths.size(); i++) {
            if (!paths.get(i).isComplete()) assertFalse(feature.contains("# Flujo: F" + (i + 1) + "\n"));
        }
    }

    @Test
    void treatsFullyAutomaticRoutesAsSystemProcessingWithoutInventingUserActions() throws Exception {
        BpmnProcess process = graph("s:START_EVENT,a:SERVICE_TASK,e:END_EVENT", "s>a,a>e");
        var scenario = parse(new GherkinGenerator().generate(process, paths(process, 0)), 1).get(0);
        assertTrue(scenario.getSteps().stream().anyMatch(step -> step.getText().equals(
                "el sistema procesa el recorrido sin acciones humanas seleccionadas")));
        assertTrue(humanIds(scenario).isEmpty());
    }

    @Test
    void refusesToGuessHumanTasksFromGenericNames() throws Exception {
        BpmnProcess process = graph("s:START_EVENT,h:TASK,e:END_EVENT", "s>h,h>e");
        assertThrows(IllegalArgumentException.class, () -> new GherkinGenerator().generate(process, paths(process, 0)));
        var file = Files.writeString(directory.resolve("tasks.properties"), "processId=test\nh=human");
        var classification = TaskClassification.load(process, file);
        assertEquals(List.of("h"), humanIds(parse(new GherkinGenerator().generate(process, paths(process, 0), classification), 1).get(0)));
    }

    @Test
    void escapesGherkinTablesAndNormalizesLabelsWithoutInjectingScenarios() throws Exception {
        BpmnProcess original = graph("s:START_EVENT,h:USER_TASK,e:END_EVENT", "s>h,h>e");
        String label = "Revisión | \\ \"ñ\"\nEscenario: falso";
        BpmnProcess process = new BpmnProcess("test", "Modelo\nEscenario: falso", original.getNodes().values().stream()
                .map(node -> new BpmnNode(node.getId(), node.getId().equals("h") ? label : node.getName(), node.getType()))
                .toList(), original.getSequenceFlows());
        var scenario = parse(new GherkinGenerator().generate(process, paths(process, 0)), 1).get(0);
        var humanStep = scenario.getSteps().stream().filter(step -> step.getText().startsWith("se realizan estas actividades")).findFirst().orElseThrow();
        assertEquals(label.replace('\n', ' '), humanStep.getDataTable().orElseThrow().getRows().get(1).getCells().get(0).getValue());
    }

    @Test
    void additionalApplicationClassificationIsUsedInsteadOfSilentlyIgnored() throws Exception {
        BpmnProcess process = application();
        var file = Files.writeString(directory.resolve("tasks.properties"),
                "processId=gov.medicaid.process.application\n_5=human");
        var classification = TaskClassification.load(process, file);
        var scenarios = parse(new GherkinGenerator().generate(process, paths(process, 2), classification), 7);
        assertTrue(scenarios.stream().allMatch(scenario -> humanIds(scenario).contains("_5")));
        assertEquals(6, scenarios.stream().filter(scenario -> humanIds(scenario).contains("_3")).count());
    }

    @Test
    void mergesDuplicateApplicationPathsWithoutChangingTheReviewTemplate() throws Exception {
        BpmnProcess process = application();
        ProcessPath path = paths(process, 0).get(0);
        String feature = new GherkinGenerator().generate(process, List.of(path, path));
        parse(feature, 1);
        assertTrue(feature.contains("# Flujo: F1, F2"));
        assertTrue(feature.contains("Cuando el revisor rechaza la solicitud en Manual Review"));
    }

    private List<Scenario> parse(String feature, int expectedCount) {
        var envelopes = GherkinParser.builder().build().parse("generated.feature", feature.getBytes(StandardCharsets.UTF_8)).toList();
        assertTrue(envelopes.stream().noneMatch(envelope -> envelope.getParseError().isPresent()), envelopes.toString());
        assertEquals(expectedCount, envelopes.stream().filter(envelope -> envelope.getPickle().isPresent()).count());
        var document = envelopes.stream().flatMap(envelope -> envelope.getGherkinDocument().stream()).findFirst().orElseThrow();
        assertEquals("es", document.getFeature().orElseThrow().getLanguage());
        var scenarios = document.getFeature().orElseThrow().getChildren().stream().flatMap(child -> child.getScenario().stream()).toList();
        assertEquals(expectedCount, scenarios.size());
        for (var scenario : scenarios) {
            assertTrue(scenario.getSteps().size() >= 3);
            assertTrue(scenario.getSteps().stream().noneMatch(step -> step.getText().isBlank()));
        }
        return scenarios;
    }

    private List<String> humanIds(Scenario scenario) {
        return scenario.getSteps().stream().filter(step -> step.getText().startsWith("se realizan estas actividades"))
                .flatMap(step -> step.getDataTable().orElseThrow().getRows().stream().skip(1))
                .map(row -> row.getCells().get(1).getValue()).toList();
    }

    private BpmnProcess graph(String nodeText, String flowText) {
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
        return new BpmnProcess("test", "", nodes, flows);
    }

    @Test
    void writesSevenCompleteScenariosInSpanishAndExcludesTruncation() throws IOException {
        BpmnProcess process = application();

        String feature = new GherkinGenerator().generate(process, paths(process, 2));

        assertTrue(feature.startsWith("# language: es\nCaracterística:"));
        assertEquals(7, feature.lines().filter(line -> line.strip().startsWith("Escenario:")).count());
        assertFalse(feature.contains("TRUNCATED"));
        assertFalse(feature.lines().anyMatch(line -> List.of("Dado", "Cuando", "Entonces", "Y").contains(line.strip())));
        assertTrue(feature.contains("# Fin: _2"));
        assertTrue(feature.contains("# Fin: _19"));
        assertTrue(feature.contains("# Fin: _22"));
        assertTrue(feature.contains("con 1 retorno\n"));
    }

    @Test
    void initialRejectionDescribesAutomaticProcessingWithoutInventingAReviewer() throws IOException {
        BpmnProcess process = application();
        ProcessPath initial = paths(process, 0).stream().filter(path -> path.getNodes().stream()
                .anyMatch(node -> node.getId().equals("_2"))).findFirst().orElseThrow();

        String feature = new GherkinGenerator().generate(process, List.of(initial));

        assertTrue(feature.contains("Dado que la validación inicial de la solicitud falla"));
        assertTrue(feature.contains("Cuando el sistema procesa la solicitud"));
        assertTrue(feature.contains("Entonces el proceso termina por rechazo en la validación inicial"));
        assertFalse(feature.contains("Manual Review"));
        assertFalse(feature.contains("revisor"));
    }

    @Test
    void manualRejectionHasTheVerifiedPreconditionsActionAndOutcome() throws IOException {
        BpmnProcess process = application();
        ProcessPath rejected = paths(process, 0).stream().filter(path -> path.getNodes().stream()
                .anyMatch(node -> node.getId().equals("_19"))).findFirst().orElseThrow();

        String feature = new GherkinGenerator().generate(process, List.of(rejected));

        assertTrue(feature.contains("Dado que la solicitud supera la validación inicial"));
        assertTrue(feature.contains("Y que se completan las verificaciones DMF y OIG"));
        assertTrue(feature.contains("Cuando el revisor rechaza la solicitud en Manual Review"));
        assertTrue(feature.contains("Entonces el proceso termina por rechazo de la revisión manual"));
        assertFalse(feature.contains("termina por aceptación"));
    }

    @Test
    void repeatsDecisionsInOrderAndRequiresScreeningBeforeFinalAcceptance() throws IOException {
        BpmnProcess process = application();
        ProcessPath accepted = paths(process, 2).stream().filter(ProcessPath::isComplete)
                .filter(path -> path.getReturnCount() == 2)
                .filter(path -> path.getNodes().stream().anyMatch(node -> node.getId().equals("_22")))
                .findFirst().orElseThrow();

        String feature = new GherkinGenerator().generate(process, List.of(accepted));

        assertEquals(3, feature.lines().filter(line -> line.contains("Cuando el revisor permite continuar")).count());
        assertEquals(2, feature.lines().filter(line -> line.contains("Entonces la reevaluación automática no es satisfactoria")).count());
        assertEquals(1, feature.lines().filter(line -> line.contains("Entonces la reevaluación automática es satisfactoria")).count());
        assertTrue(feature.lastIndexOf("Cuando el revisor permite continuar")
                < feature.indexOf("Entonces la reevaluación automática es satisfactoria"));
        assertTrue(feature.indexOf("Entonces la reevaluación automática es satisfactoria")
                < feature.indexOf("Y el proceso termina por aceptación"));
        assertTrue(feature.contains("con 2 retornos"));
        assertTrue(feature.contains("_32-_33"));
    }

    @Test
    void normalizesNamesToPreventExtraScenariosAndUsesIdsWhenNamesAreEmpty() throws IOException {
        BpmnProcess original = application();
        List<BpmnNode> nodes = new ArrayList<>(original.getNodes().values());
        BpmnNode review = original.getNodes().get("_3");
        nodes.set(nodes.indexOf(review), new BpmnNode("_3", "Revisión\nEscenario: inyectado", NodeType.USER_TASK));
        BpmnProcess process = new BpmnProcess(original.getId(), "Proceso\nEscenario: falso", nodes, original.getSequenceFlows());

        String feature = new GherkinGenerator().generate(process, paths(process, 0));

        assertEquals(3, feature.lines().filter(line -> line.strip().startsWith("Escenario:")).count());
        assertTrue(feature.contains("en Revisión Escenario: inyectado"));
        nodes.set(nodes.indexOf(nodes.stream().filter(node -> node.getId().equals("_3")).findFirst().orElseThrow()),
                new BpmnNode("_3", "", NodeType.USER_TASK));
        process = new BpmnProcess(original.getId(), "", nodes, original.getSequenceFlows());
        assertTrue(new GherkinGenerator().generate(process, paths(process, 0)).contains("en _3"));
    }

    @Test
    void usesIdsWhenNamesContainOnlyNonBreakingSpaces() throws IOException {
        BpmnProcess original = application();
        List<BpmnNode> nodes = new ArrayList<>(original.getNodes().values());
        BpmnNode review = original.getNodes().get("_3");
        nodes.set(nodes.indexOf(review), new BpmnNode("_3", "\u00a0", NodeType.USER_TASK));
        BpmnProcess process = new BpmnProcess(original.getId(), "\u00a0", nodes, original.getSequenceFlows());

        String feature = new GherkinGenerator().generate(process, paths(process, 0));

        assertTrue(feature.startsWith("# language: es\nCaracterística: gov.medicaid.process.application\n"));
        assertTrue(feature.contains("en _3\n"));
    }

    private BpmnProcess application() throws IOException {
        return new BpmnParser().parse(Path.of("bpmn", "ApplicationProcess.bpmn"),
                "gov.medicaid.process.application");
    }

    private List<ProcessPath> paths(BpmnProcess process, int limit) {
        return new PathAnalyzer().analyze(new ProcessGraph(process), limit);
    }
}
