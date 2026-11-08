package com.pia.vvs.output;

import com.pia.vvs.analysis.BranchFlowProjector;
import com.pia.vvs.analysis.PathAnalyzer;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.parser.BpmnParser;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Pickle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeMouraGherkinGeneratorTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"_6-", "renamed-"})
    void projectsFigureFourInOrderAndTracesEveryPostalAlternative(String prefix) throws Exception {
        Path xml = directory.resolve("modelo.bpmn");
        Path profile = directory.resolve("modelo.tasks.properties");
        Files.writeString(xml, Files.readString(Path.of("bpmn/HardwareRetailer.bpmn"), StandardCharsets.ISO_8859_1)
                .replace("_6-", prefix), StandardCharsets.ISO_8859_1);
        Files.writeString(profile, Files.readString(Path.of("bpmn/HardwareRetailer.tasks.properties")).replace("_6-", prefix));
        String feature = generate(xml, prefix + "1", profile);
        var scenarios = parse(feature);
        assertEquals(List.of(List.of("102", "277", "569"), List.of("102", "379", "569"), List.of("153", "569")),
                scenarios.stream().map(s -> humanIds(s).stream().map(id -> id.substring(prefix.length())).toList()).toList());
        assertTrue(scenarios.stream().flatMap(s -> s.getSteps().stream()).allMatch(s -> s.getArgument().isEmpty()));
        String postal = feature.substring(feature.indexOf("  # B3"), feature.indexOf("  Escenario: Proyección humana 1"));
        assertTrue(postal.contains("# B3 <- F2"));
        assertTrue(postal.contains("# B4 <- F3"));
        assertTrue(postal.contains("# B5 <- F4"));
        assertTrue(postal.contains("extra insurance required"));
        assertTrue(postal.contains("Always"));
        assertTrue(feature.contains("# B1 <- F1"));
        assertTrue(feature.contains("# B2 <- F1, F2, F3, F4"));
        assertTrue(feature.contains("sincronizarse"));
    }

    @Test
    void retainsReviewOccurrencesAndSeparatesOutcomesIncludingTheAutomaticRejection() throws Exception {
        String feature = generate(Path.of("bpmn/ApplicationProcess.bpmn"), "gov.medicaid.process.application", null);
        var scenarios = parse(feature);
        assertEquals(7, scenarios.size());
        for (int visits = 1; visits <= 3; visits++) {
            var reviews = java.util.Collections.nCopies(visits, "_3");
            var matching = scenarios.stream().filter(s -> humanIds(s).equals(reviews)).toList();
            assertEquals(2, matching.size());
            assertTrue(matching.stream().anyMatch(s -> end(s).endsWith("[_19]")));
            assertTrue(matching.stream().anyMatch(s -> end(s).endsWith("[_22]")));
        }
        var automatic = scenarios.stream().filter(s -> humanIds(s).isEmpty()).findFirst().orElseThrow();
        assertTrue(automatic.getSteps().get(0).getText().contains("carece de tareas humanas"));
        assertTrue(end(automatic).endsWith("[_2]"));
        assertFalse(feature.contains("<- F7\n")); // El truncado no participa.
    }

    @Test
    void keepsExplicitOrConditionsEvenWithIdenticalHumanTasksAndEnds() throws Exception {
        Path xml = Files.writeString(directory.resolve("condiciones.bpmn"), """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="p">
                  <startEvent id="s"/><inclusiveGateway id="or"/><inclusiveGateway id="join"/>
                  <manualTask id="h" name="Revisar"/><endEvent id="e"/>
                  <sequenceFlow id="f" sourceRef="s" targetRef="or"/>
                  <sequenceFlow id="a" sourceRef="or" targetRef="join"><conditionExpression>x</conditionExpression></sequenceFlow>
                  <sequenceFlow id="b" sourceRef="or" targetRef="join"><conditionExpression>y</conditionExpression></sequenceFlow>
                  <sequenceFlow id="c" sourceRef="join" targetRef="h"/><sequenceFlow id="d" sourceRef="h" targetRef="e"/>
                </process></definitions>
                """);
        String feature = generate(xml, "p", null);
        var scenarios = parse(feature);
        assertEquals(3, scenarios.size());
        assertTrue(scenarios.stream().allMatch(s -> humanIds(s).equals(List.of("h"))));
        assertTrue(feature.contains("a {x}"));
        assertTrue(feature.contains("b {y}"));
    }

    @Test
    void preservesDistinctEndIdsWhenHumanStepsAndDecisionLabelsMatch() throws Exception {
        Path xml = Files.writeString(directory.resolve("resultados.bpmn"), """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="p">
                  <startEvent id="s"/><userTask id="h"/><exclusiveGateway id="x"/>
                  <endEvent id="a" name="Cierre"/><endEvent id="b" name="Cierre"/>
                  <sequenceFlow id="sh" sourceRef="s" targetRef="h"/><sequenceFlow id="hx" sourceRef="h" targetRef="x"/>
                  <sequenceFlow id="xa" sourceRef="x" targetRef="a"/><sequenceFlow id="xb" sourceRef="x" targetRef="b"/>
                </process></definitions>
                """);
        var scenarios = parse(generate(xml, "p", null));
        assertEquals(2, scenarios.size());
        assertTrue(scenarios.stream().allMatch(s -> humanIds(s).equals(List.of("h"))));
        assertTrue(scenarios.stream().anyMatch(s -> end(s).endsWith("Cierre [a]")));
        assertTrue(scenarios.stream().anyMatch(s -> end(s).endsWith("Cierre [b]")));
    }

    private String generate(Path input, String id, Path profile) throws Exception {
        var process = new BpmnParser().parse(input, id);
        var graph = new ProcessGraph(process);
        var branches = new BranchFlowProjector().project(graph, new PathAnalyzer().analyze(graph, 2));
        var classification = profile == null ? TaskClassification.automatic(process) : TaskClassification.load(process, profile);
        return new DeMouraGherkinGenerator().generate(graph, branches, classification);
    }

    private List<Pickle> parse(String feature) {
        var messages = GherkinParser.builder().build().parse("ramas.feature", feature.getBytes(StandardCharsets.UTF_8)).toList();
        assertTrue(messages.stream().noneMatch(e -> e.getParseError().isPresent()));
        return messages.stream().flatMap(e -> e.getPickle().stream()).toList();
    }

    private List<String> humanIds(Pickle scenario) {
        return scenario.getSteps().stream().map(s -> s.getText()).filter(t -> t.contains("tarea humana "))
                .map(t -> t.substring(t.lastIndexOf('[') + 1, t.length() - 1)).toList();
    }

    private String end(Pickle scenario) {
        return scenario.getSteps().get(scenario.getSteps().size() - 1).getText();
    }
}
