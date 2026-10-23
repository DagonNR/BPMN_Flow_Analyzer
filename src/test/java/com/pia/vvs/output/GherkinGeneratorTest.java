package com.pia.vvs.output;

import com.pia.vvs.analysis.PathAnalyzer;
import com.pia.vvs.analysis.ProcessPath;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.parser.BpmnParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GherkinGeneratorTest {
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
