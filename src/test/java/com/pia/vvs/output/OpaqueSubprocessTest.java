package com.pia.vvs.output;

import com.pia.vvs.analysis.BranchFlowProjector;
import com.pia.vvs.analysis.PathAnalyzer;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import com.pia.vvs.parser.BpmnParser;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpaqueSubprocessTest {
    @TempDir Path directory;
    private final PathAnalyzer analyzer = new PathAnalyzer();

    private ProcessGraph fixture(String name) throws IOException {
        return new ProcessGraph(new BpmnParser().parse(Path.of("src/test/resources/subprocesos", name + ".bpmn"), "p"));
    }

    @Test
    void sequentialPathUsesOnlyTheOuterActivityAndItsExternalConnections() throws IOException {
        var graph = fixture("secuencial");
        var process = graph.getProcess();
        assertEquals(Set.of("s", "a", "sub", "c", "e"), process.getNodes().keySet());
        assertEquals("Revisar expediente", graph.getNode("sub").getName());
        assertEquals(NodeType.SUB_PROCESS, graph.getNode("sub").getType());
        assertFalse(graph.getNode("sub").getType().isTask());
        assertEquals(List.of("f1", "f2", "f3", "f4"), process.getSequenceFlows().stream().map(SequenceFlow::getId).toList());
        var paths = analyzer.analyze(graph, 2);
        assertEquals(1, paths.size());
        assertTrue(paths.get(0).isComplete());
        assertEquals(List.of("s", "a", "sub", "c", "e"), paths.get(0).getNodes().stream().map(BpmnNode::getId).toList());
        var branches = new BranchFlowProjector().project(graph, paths);
        var classification = TaskClassification.automatic(process);
        assertEquals(Set.of("a", "c"), classification.getHumanIds());
        assertTrue(classification.getUnclassifiedIds().isEmpty());
        String joint = new GherkinGenerator().generate(process, paths, classification);
        String projected = new DeMouraGherkinGenerator().generate(graph, branches, classification);
        for (String feature : List.of(joint, projected)) {
            assertTrue(feature.contains("Preparar"));
            assertTrue(feature.contains("Archivar"));
            assertFalse(feature.contains("Revisar expediente"));
            assertFalse(feature.contains("Entrevista interna"));
            assertTrue(new CucumberStepGenerator().generate(feature, "Prueba").contains("throw new PendingException"));
        }
        Path override = Files.writeString(directory.resolve("tasks.properties"), "processId=p\nsub=human");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TaskClassification.load(process, override))
                .getMessage().contains("subproceso opaco sub"));
    }

    @ParameterizedTest
    @CsvSource({"secuencial,X,X", "opcional,X- ,X-", "concurrente,X,X-"})
    void exportsTheExpectedMarksInBothCsvAndExcel(String model, String pathMarks, String branchMarks) throws IOException {
        var graph = fixture(model);
        var process = graph.getProcess();
        var paths = analyzer.analyze(graph, 2);
        assertTrue(paths.stream().allMatch(p -> p.isComplete()));
        var branches = new BranchFlowProjector().project(graph, paths);
        var table = new FlowTable();
        assertTrue(table.toText(process, paths).contains("Revisar expediente [sub]"));
        var names = switch (model) {
            case "secuencial" -> List.of("Preparar [a]", "Revisar expediente [sub]", "Archivar [c]");
            case "opcional" -> List.of("Revisar expediente [sub]");
            case "concurrente" -> List.of("Revisar expediente [sub]", "Preparar envío [otra]", "Cerrar [comun]");
            default -> throw new AssertionError(model);
        };
        Path excel = directory.resolve(model + ".xlsx");
        new ExcelExporter().write(excel, process, paths, branches);
        try (var input = Files.newInputStream(excel); var workbook = new XSSFWorkbook(input)) {
            for (String name : List.of("Caminos", "Ramas")) {
                boolean joint = name.equals("Caminos");
                int offset = joint ? 5 : 4;
                String marks = joint ? pathMarks : branchMarks;
                String csv = joint ? table.toCsv(process, paths) : table.branchesToCsv(process, branches);
                var lines = csv.lines().toList();
                var header = List.of(lines.get(0).split(","));
                assertEquals(names, header.subList(offset, header.size()));
                var sheet = workbook.getSheet(name);
                assertEquals(header.size(), sheet.getRow(0).getPhysicalNumberOfCells());
                for (int i = offset; i < header.size(); i++) {
                    assertEquals(header.get(i), sheet.getRow(0).getCell(i).getStringCellValue());
                }
                assertEquals(marks.length() + 1, lines.size());
                assertEquals(lines.size(), sheet.getPhysicalNumberOfRows());
                int column = header.indexOf("Revisar expediente [sub]");
                for (int i = 0; i < marks.length(); i++) {
                    String expected = String.valueOf(marks.charAt(i));
                    assertEquals(expected, lines.get(i + 1).split(",")[column]);
                    assertEquals(expected, sheet.getRow(i + 1).getCell(column).getStringCellValue());
                }
            }
        }
    }

    @Test
    void waitsForBothBranchesBeforeTheCommonActivity() throws IOException {
        var graph = fixture("concurrente");
        var paths = analyzer.analyze(graph, 2);
        assertEquals(1, paths.size());
        var ids = paths.get(0).getNodes().stream().map(BpmnNode::getId).toList();
        assertTrue(ids.containsAll(List.of("sub", "otra", "join", "comun")));
        assertTrue(ids.indexOf("sub") < ids.indexOf("join") && ids.indexOf("otra") < ids.indexOf("join"));
        assertTrue(ids.indexOf("join") < ids.indexOf("comun"));
        var branches = new BranchFlowProjector().project(graph, paths);
        assertEquals(List.of(List.of("sub", "comun"), List.of("otra", "comun")), branches.stream()
                .map(b -> b.getNodes().stream().filter(n -> n.getType().isStructuralActivity()).map(BpmnNode::getId).toList()).toList());
    }

    private String model(String attributes, String body) {
        return """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:ext="urn:vendor"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="urn:pruebas"><process id="p">
                  <startEvent id="s"/><subProcess id="sub" %s>%s</subProcess><endEvent id="e"/>
                  <sequenceFlow id="in" sourceRef="s" targetRef="sub"/><sequenceFlow id="out" sourceRef="sub" targetRef="e"/>
                </process></definitions>
                """.formatted(attributes, body);
    }

    private ProcessGraph read(String xml) throws IOException {
        Path file = Files.writeString(directory.resolve("prueba.bpmn"), xml);
        return new ProcessGraph(new BpmnParser().parse(file, "p"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "triggeredByEvent=\"true\"|triggeredByEvent", "triggeredByEvent=\"1\"|triggeredByEvent",
            "isForCompensation=\"1\"|isForCompensation", "startQuantity=\"2\"|startQuantity",
            "completionQuantity=\"2\"|completionQuantity", "xsi:type=\"tTransaction\"|xsi:type"})
    void refusesSpecialOuterAttributes(String attributes, String reason) {
        IOException error = assertThrows(IOException.class, () -> read(model(attributes, "")));
        assertTrue(error.getMessage().contains("Subproceso sub"));
        assertTrue(error.getMessage().contains(reason));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "<standardLoopCharacteristics/>|standardLoopCharacteristics",
            "<multiInstanceLoopCharacteristics/>|multiInstanceLoopCharacteristics",
            "<subProcess id=\"nested\" triggeredByEvent=\"true\"/>|triggeredByEvent",
            "<subProcess id=\"nested\"><endEvent id=\"stop\"><terminateEventDefinition/></endEvent></subProcess>|terminateEventDefinition",
            "<endEvent id=\"error\"><errorEventDefinition/></endEvent>|errorEventDefinition",
            "<endEvent id=\"ref\"><eventDefinitionRef>special</eventDefinitionRef></endEvent>|eventDefinitionRef",
            "<endEvent id=\"comp\"><compensateEventDefinition/></endEvent>|compensateEventDefinition",
            "<task id=\"handler\" isForCompensation=\"true\"/>|isForCompensation",
            "<boundaryEvent id=\"boundary\" attachedToRef=\"a\"/>|boundaryEvent",
            "<callActivity id=\"call\"/>|callActivity", "<transaction id=\"tx\"/>|transaction",
            "<adHocSubProcess id=\"ad\"/>|adHocSubProcess"})
    void refusesSpecialContentIncludingNestedScopes(String body, String reason) {
        IOException error = assertThrows(IOException.class, () -> read(model("", body)));
        assertTrue(error.getMessage().contains("Subproceso sub"));
        assertTrue(error.getMessage().contains(reason));
    }

    @Test
    void ignoresExtensionAndDocumentationContentsAndAcceptsOrdinaryNestedScopes() throws IOException {
        String body = """
                <documentation><ext:example><terminateEventDefinition/></ext:example></documentation>
                <extensionElements><boundaryEvent/></extensionElements><ext:transaction/>
                <subProcess id="nested" triggeredByEvent="0"><task id="internal"/></subProcess>
                """;
        var graph = read(model("triggeredByEvent=\"false\" isForCompensation=\"0\" startQuantity=\"1\"", body));
        assertEquals(Set.of("s", "sub", "e"), graph.getProcess().getNodes().keySet());
        assertTrue(analyzer.analyze(graph, 2).get(0).isComplete());
    }

    @Test
    void rejectsBoundaryAttachedToTheOuterSubprocessBeforeExploringPaths() throws IOException {
        var graph = read(model("", "").replace("</process>", "<boundaryEvent id=\"b\" attachedToRef=\"sub\"/></process>"));
        var error = assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(graph, 2));
        assertTrue(error.getMessage().contains("boundaryEvent b adjunto a sub"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<sequenceFlow id=\"extra\" sourceRef=\"sub\" targetRef=\"e\"/>",
            "<userTask id=\"t\"/><sequenceFlow id=\"extra\" sourceRef=\"t\" targetRef=\"sub\"/>"})
    void requiresASingleExternalEntryAndExit(String extra) throws IOException {
        var graph = read(model("", "").replace("</process>", extra + "</process>"));
        var error = assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(graph, 2));
        assertTrue(error.getMessage().contains("sub requiere una entrada y una salida exteriores"));
    }

    @Test
    void cannotUseAnInternalTaskAsAnExternalFlowEndpoint() {
        String xml = model("", "<userTask id=\"interna\"/>").replace("targetRef=\"sub\"", "targetRef=\"interna\"");
        var error = assertThrows(IOException.class, () -> read(xml));
        assertTrue(error.getMessage().contains("targetRef='interna'"));
    }
}
