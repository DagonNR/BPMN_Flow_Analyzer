package com.pia.vvs.output;

import com.pia.vvs.analysis.PathAnalyzer;
import com.pia.vvs.analysis.BranchFlow;
import com.pia.vvs.analysis.BranchFlowProjector;
import com.pia.vvs.analysis.ProcessPath;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.parser.BpmnParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowTableTest {
    @Test
    void keepsTenTaskColumnsAndDistinguishesRepeatedNamesById() throws IOException {
        BpmnProcess process = application();
        String csv = new FlowTable().toCsv(process, paths(process));
        String[] lines = csv.split("\n");

        assertEquals(9, lines.length);
        assertEquals(15, lines[0].split(",", -1).length);
        assertTrue(lines[0].contains("Reject Application [_9]"));
        assertTrue(lines[0].contains("Reject Application [_18]"));
        assertTrue(lines[0].contains("Auto Screening [_15]"));
        assertTrue(lines[0].contains("Auto Screening [_31]"));
    }

    @Test
    void marksInitialRejectionWithoutManualReviewAndSeparatesTruncation() throws IOException {
        BpmnProcess process = application();
        String[] lines = new FlowTable().toCsv(process, paths(process)).split("\n");
        List<String> header = List.of(lines[0].split(",", -1));
        int review = header.indexOf("Manual Review [_3]");
        int dmf = header.indexOf("Check DMF [_34]");
        int oig = header.indexOf("Check Excluded Provider List in OIG [_10]");
        int initialRejections = 0;
        int truncated = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            if (cells[3].equals("_2")) {
                initialRejections++;
                assertEquals("-", cells[review]);
                assertEquals("-", cells[dmf]);
                assertEquals("-", cells[oig]);
            } else {
                assertEquals("X", cells[review]);
                assertEquals("X", cells[dmf]);
                assertEquals("X", cells[oig]);
            }
            if (cells[1].equals("TRUNCATED")) {
                truncated++;
                assertEquals("2", cells[2]);
                assertEquals("", cells[3]);
                assertEquals("_32-_33", cells[4]);
            }
        }
        assertEquals(1, initialRejections);
        assertEquals(1, truncated);
    }

    @Test
    void escapesCsvLabelsAndKeepsUnicode() throws IOException {
        BpmnProcess original = application();
        List<BpmnNode> nodes = new ArrayList<>(original.getNodes().values());
        BpmnNode review = original.getNodes().get("_3");
        nodes.set(nodes.indexOf(review), new BpmnNode("_3", "Revisión, \"manual\"\nsegunda línea", review.getType()));
        BpmnProcess process = new BpmnProcess(original.getId(), original.getName(), nodes, original.getSequenceFlows());

        String csv = new FlowTable().toCsv(process, paths(process));

        assertTrue(csv.contains("\"Revisión, \"\"manual\"\"\nsegunda línea [_3]\""));
        assertTrue(new FlowTable().toText(process, paths(process)).contains("Revisión, \"manual\" segunda línea [_3]"));
    }

    @Test
    void keepsTaskNamesFromBecomingSpreadsheetFormulas() throws IOException {
        BpmnProcess original = application();
        List<BpmnNode> nodes = new ArrayList<>(original.getNodes().values());
        BpmnNode review = original.getNodes().get("_3");
        nodes.set(nodes.indexOf(review), new BpmnNode("_3", "=1+1", review.getType()));
        BpmnProcess process = new BpmnProcess(original.getId(), "", nodes, original.getSequenceFlows());

        assertTrue(new FlowTable().toCsv(process, paths(process)).contains("'=1+1 [_3]"));
    }

    private BpmnProcess application() throws IOException {
        return new BpmnParser().parse(Path.of("bpmn", "ApplicationProcess.bpmn"),
                "gov.medicaid.process.application");
    }

    @Test
    void hardwareRowsPreserveEveryTaskAndMatchTheirJointPaths() throws IOException {
        BpmnProcess process = new BpmnParser().parse(Path.of("bpmn", "HardwareRetailer.bpmn"), "_6-1");
        List<ProcessPath> paths = paths(process);
        List<BpmnNode> tasks = process.getNodes().values().stream().filter(node -> node.getType().isTask()).toList();

        String[] rows = new FlowTable().toCsv(process, paths).split("\n");

        assertEquals(8, tasks.size());
        assertEquals(5, rows.length);
        assertEquals(13, rows[0].split(",", -1).length);
        for (int i = 0; i < paths.size(); i++) {
            String[] cells = rows[i + 1].split(",", -1);
            assertEquals("F" + (i + 1), cells[0]);
            assertEquals("COMPLETE", cells[1]);
            assertEquals("_6-620", cells[3]);
            assertEquals("", cells[4]);
            for (int column = 0; column < tasks.size(); column++) {
                assertTrue(rows[0].contains("[" + tasks.get(column).getId() + "]"));
                assertEquals(paths.get(i).getNodes().contains(tasks.get(column)) ? "X" : "-", cells[column + 5]);
            }
        }
    }

    private List<ProcessPath> paths(BpmnProcess process) {
        return new PathAnalyzer().analyze(new ProcessGraph(process), 2);
    }

    @Test
    void branchCsvLabelsProjectionsAndKeepsTaskColumnsAndSourcePaths() throws IOException {
        BpmnProcess process = new BpmnParser().parse(Path.of("bpmn/HardwareRetailer.bpmn"), "_6-1");
        List<BranchFlow> branches = new BranchFlowProjector().project(new ProcessGraph(process), paths(process));
        List<BpmnNode> tasks = process.getNodes().values().stream().filter(node -> node.getType().isTask()).toList();

        String[] lines = new FlowTable().branchesToCsv(process, branches).split("\n");

        assertEquals(6, lines.length);
        assertEquals(12, lines[0].split(",", -1).length);
        assertTrue(lines[0].startsWith("Flujo rama,Representación,Caminos origen,Salidas AND elegidas,"));
        for (int i = 0; i < branches.size(); i++) {
            String[] cells = lines[i + 1].split(",", -1);
            assertEquals("B" + (i + 1), cells[0]);
            assertEquals("BRANCH_VIEW", cells[1]);
            assertFalse(cells[2].isBlank());
            assertFalse(cells[3].isBlank());
            for (int column = 0; column < tasks.size(); column++) {
                assertEquals(branches.get(i).getNodes().contains(tasks.get(column)) ? "X" : "-", cells[column + 4]);
            }
        }
        assertTrue(List.of(lines).stream().anyMatch(line -> line.contains(",F1;F2;F3;F4,")));
    }

    @Test
    void branchCsvUsesTheSameEscapingAndFormulaProtectionAsTheJointCsv() throws IOException {
        BpmnProcess original = application();
        List<BpmnNode> nodes = original.getNodes().values().stream().map(node -> new BpmnNode(node.getId(),
                node.getId().equals("_3") ? "=Revisión, \"manual\"\nrepetida" : node.getName(), node.getType())).toList();
        BpmnProcess process = new BpmnProcess(original.getId(), original.getName(), nodes, original.getSequenceFlows());
        List<BranchFlow> branches = new BranchFlowProjector().project(new ProcessGraph(process), paths(process));

        String csv = new FlowTable().branchesToCsv(process, branches);

        assertTrue(csv.contains("\"'=Revisión, \"\"manual\"\"\nrepetida [_3]\""));
    }
}
