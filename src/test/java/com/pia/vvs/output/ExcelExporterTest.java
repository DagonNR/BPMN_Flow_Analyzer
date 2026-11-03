package com.pia.vvs.output;

import com.pia.vvs.analysis.BranchFlowProjector;
import com.pia.vvs.analysis.PathAnalyzer;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.parser.BpmnParser;
import io.cucumber.gherkin.GherkinParser;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelExporterTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"a,a", "a b,a;b", "b a,a;b"})
    void preservesEveryEndAcrossCsvExcelAndGherkinRegardlessOfXmlOrder(String declarationOrder,
                                                                      String expectedEnds) throws Exception {
        // Mismo caso mínimo de la auditoría; una variante conserva un solo final.
        StringBuilder nodes = new StringBuilder();
        for (String id : declarationOrder.split(" ")) {
            nodes.append("<endEvent id=\"").append(id).append("\"/>");
        }
        String flows = "<sequenceFlow id=\"fa\" sourceRef=\"g\" targetRef=\"a\"/>"
                + (declarationOrder.contains("b") ? "<sequenceFlow id=\"fb\" sourceRef=\"g\" targetRef=\"b\"/>" : "");
        Path input = Files.writeString(directory.resolve("ends.bpmn"), """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="test">
                    <startEvent id="s"/><parallelGateway id="g"/>%s
                    <sequenceFlow id="f0" sourceRef="s" targetRef="g"/>%s
                  </process>
                </definitions>
                """.formatted(nodes, flows));
        var process = new BpmnParser().parse(input, "test");
        var paths = new PathAnalyzer().analyze(new ProcessGraph(process), 2);
        assertEquals(1, paths.size());
        assertTrue(paths.get(0).isComplete());
        String csv = new FlowTable().toCsv(process, paths);
        assertEquals(expectedEnds, parseCsv(csv).get(1).get(3));

        Path output = directory.resolve("ends.xlsx");
        new ExcelExporter().write(output, process, paths, null);
        try (var workbook = read(output)) {
            assertSheet(workbook.getSheet("Caminos"), csv, 2, 5);
            assertEquals(expectedEnds, workbook.getSheet("Caminos").getRow(1).getCell(3).getStringCellValue());
        }
        String feature = new GherkinGenerator().generate(process, paths);
        assertTrue(feature.contains("  # Fin: " + expectedEnds + "\n"));
        Path featureFile = Files.writeString(directory.resolve("ends.feature"), feature);
        var envelopes = GherkinParser.builder().build().parse(featureFile).toList();
        assertTrue(envelopes.stream().noneMatch(envelope -> envelope.getParseError().isPresent()));
        var scenarios = envelopes.stream().flatMap(envelope -> envelope.getPickle().stream()).toList();
        assertEquals(1, scenarios.size());
        var result = scenarios.get(0).getSteps().stream()
                .filter(step -> step.getText().equals("se alcanzan estos eventos finales del proceso"))
                .findFirst().orElseThrow().getArgument().orElseThrow().getDataTable().orElseThrow();
        assertEquals(List.of(expectedEnds.split(";")), result.getRows().stream().skip(1)
                .map(row -> row.getCells().get(1).getValue()).toList());
    }

    @ParameterizedTest
    @CsvSource({
            "ApplicationProcess, gov.medicaid.process.application, 0, 5, 4, 15, 14",
            "ApplicationProcess, gov.medicaid.process.application, 1, 7, 6, 15, 14",
            "ApplicationProcess, gov.medicaid.process.application, 2, 9, 8, 15, 14",
            "HardwareRetailer, _6-1, 2, 5, 6, 13, 12"
    })
    void reopensBothSheetsAndMatchesEveryCsvCell(String model, String id, int limit, int pathRows,
                                                int branchRows, int pathColumns, int branchColumns) throws Exception {
        BpmnProcess process = new BpmnParser().parse(Path.of("bpmn", model + ".bpmn"), id);
        var graph = new ProcessGraph(process);
        var paths = new PathAnalyzer().analyze(graph, limit);
        var branches = new BranchFlowProjector().project(graph, paths);
        Path file = directory.resolve(model + ".xlsx");

        new ExcelExporter().write(file, process, paths, branches);

        try (var workbook = read(file)) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals("Caminos", workbook.getSheetName(0));
            assertEquals("Ramas", workbook.getSheetName(1));
            var table = new FlowTable();
            assertSheet(workbook.getSheetAt(0), table.toCsv(process, paths), pathRows, pathColumns);
            assertSheet(workbook.getSheetAt(1), table.branchesToCsv(process, branches), branchRows, branchColumns);
            for (var sheet : workbook) {
                assertTrue(sheet.getPaneInformation().isFreezePane());
                assertEquals(1, sheet.getPaneInformation().getHorizontalSplitPosition());
                assertTrue(workbook.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold());
                assertTrue(sheet.getRow(0).getCell(0).getCellStyle().getWrapText());
                assertTrue(workbook.getSheet(sheet.getSheetName()).getCTWorksheet().isSetAutoFilter());
                for (int c = 0; c < sheet.getRow(0).getLastCellNum(); c++) {
                    assertTrue(sheet.getColumnWidth(c) >= 14 * 256 && sheet.getColumnWidth(c) <= 40 * 256);
                }
            }
        }
    }

    @Test
    void keepsTruncationMetadataAndOnlyCompleteOriginsInBranches() throws Exception {
        Path file = export(application(), 2);
        try (var workbook = read(file)) {
            Sheet paths = workbook.getSheet("Caminos");
            List<String> truncated = new ArrayList<>();
            int complete = 0;
            for (int r = 1; r <= paths.getLastRowNum(); r++) {
                var row = paths.getRow(r);
                if (row.getCell(1).getStringCellValue().equals("TRUNCATED")) {
                    truncated.add(row.getCell(0).getStringCellValue());
                    assertEquals(2, row.getCell(2).getNumericCellValue());
                    assertEquals("", row.getCell(3).getStringCellValue());
                    assertEquals("_32-_33", row.getCell(4).getStringCellValue());
                } else {
                    complete++;
                    assertEquals("COMPLETE", row.getCell(1).getStringCellValue());
                    assertFalse(row.getCell(3).getStringCellValue().isBlank());
                    assertEquals("", row.getCell(4).getStringCellValue());
                }
            }
            assertEquals(7, complete);
            assertEquals(1, truncated.size());
            Sheet branches = workbook.getSheet("Ramas");
            assertEquals(7, branches.getLastRowNum());
            for (int r = 1; r <= branches.getLastRowNum(); r++) {
                assertEquals("BRANCH_VIEW", branches.getRow(r).getCell(1).getStringCellValue());
                String origins = branches.getRow(r).getCell(2).getStringCellValue();
                assertFalse(origins.isBlank());
                assertTrue(List.of(origins.split(";")).stream().noneMatch(truncated::contains));
            }
        }
    }

    @Test
    void distinguishesRepeatedTaskNamesByIdOnBothSheets() throws Exception {
        try (var workbook = read(export(application(), 2))) {
            for (var sheet : workbook) {
                List<String> header = new ArrayList<>();
                sheet.getRow(0).forEach(cell -> header.add(cell.getStringCellValue()));
                assertTrue(header.containsAll(List.of("Reject Application [_9]", "Reject Application [_18]",
                        "Auto Screening [_15]", "Auto Screening [_31]")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Revisión, \"manual\"\nsegunda línea áéñ 漢字", "=1+1", "+SUM(A1)", "-1+2", " @SUM(A1)"})
    void preservesSpecialTextWithoutCreatingFormulas(String label) throws Exception {
        BpmnProcess process = renamedReview(label);
        var graph = new ProcessGraph(process);
        var paths = new PathAnalyzer().analyze(graph, 2);
        var branches = new BranchFlowProjector().project(graph, paths);
        try (var workbook = read(export(process, 2))) {
            assertSheet(workbook.getSheetAt(0), new FlowTable().toCsv(process, paths), 9, 15);
            assertSheet(workbook.getSheetAt(1), new FlowTable().branchesToCsv(process, branches), 8, 14);
            for (var sheet : workbook) {
                List<String> header = new ArrayList<>();
                sheet.getRow(0).forEach(cell -> header.add(cell.getStringCellValue()));
                assertTrue(header.contains(label + " [_3]"));
            }
        }
    }

    @Test
    void exportsEmptyResultsAsHeadersWithoutInventingBranches() throws Exception {
        Path file = directory.resolve("empty.xlsx");
        BpmnProcess process = application();
        new ExcelExporter().write(file, process, List.of(), List.of());
        try (var workbook = read(file)) {
            assertSheet(workbook.getSheetAt(0), new FlowTable().toCsv(process, List.of()), 1, 15);
            assertSheet(workbook.getSheetAt(1), new FlowTable().branchesToCsv(process, List.of()), 1, 14);
        }
    }

    @Test
    void reportsAnUnwritableParentWithoutLeavingAnXlsx() throws Exception {
        Path parent = Files.writeString(directory.resolve("parent"), "conservar");
        IOException error = assertThrows(IOException.class,
                () -> new ExcelExporter().write(parent.resolve("report.xlsx"), application(), List.of(), List.of()));
        assertTrue(error.getMessage().contains("No se pudo exportar Excel"));
        assertEquals("conservar", Files.readString(parent));
        assertDirectoryContainsOnly("parent");
    }

    @Test
    void refusesToReplaceADirectoryWithAWorkbook() throws Exception {
        Path destination = Files.createDirectory(directory.resolve("report.xlsx"));
        assertThrows(IOException.class,
                () -> new ExcelExporter().write(destination, application(), List.of(), List.of()));
        assertTrue(Files.isDirectory(destination));
        assertDirectoryContainsOnly("report.xlsx");
    }

    @Test
    void preservesPreviousWorkbookIfTextExceedsTheExcelCellLimit() throws Exception {
        Path file = export(application(), 2);
        byte[] previous = Files.readAllBytes(file);
        BpmnProcess oversized = renamedReview("x".repeat(32768));
        IOException error = assertThrows(IOException.class,
                () -> new ExcelExporter().write(file, oversized, List.of(), List.of()));
        assertTrue(error.getMessage().contains("No se pudo exportar Excel"));
        assertArrayEquals(previous, Files.readAllBytes(file));
        try (var workbook = read(file)) {
            assertEquals(9, workbook.getSheet("Caminos").getPhysicalNumberOfRows());
        }
        assertDirectoryContainsOnly("report.xlsx");
    }

    @Test
    void replacesAnExistingWorkbookWithTheNewResultsAndClosesResources() throws Exception {
        Path file = export(application(), 2);
        export(application(), 0);
        try (var workbook = read(file)) {
            assertEquals(5, workbook.getSheet("Caminos").getPhysicalNumberOfRows());
            assertEquals(4, workbook.getSheet("Ramas").getPhysicalNumberOfRows());
        }
        assertDirectoryContainsOnly("report.xlsx");
        Files.delete(file); // También detecta recursos abiertos que bloqueen el archivo en Windows.
    }

    private Path export(BpmnProcess process, int limit) throws IOException {
        var graph = new ProcessGraph(process);
        var paths = new PathAnalyzer().analyze(graph, limit);
        var branches = new BranchFlowProjector().project(graph, paths);
        Path file = directory.resolve("report.xlsx");
        new ExcelExporter().write(file, process, paths, branches);
        return file;
    }

    private BpmnProcess application() throws IOException {
        return new BpmnParser().parse(Path.of("bpmn/ApplicationProcess.bpmn"), "gov.medicaid.process.application");
    }

    private BpmnProcess renamedReview(String label) throws IOException {
        BpmnProcess original = application();
        List<BpmnNode> nodes = original.getNodes().values().stream().map(node -> new BpmnNode(node.getId(),
                node.getId().equals("_3") ? label : node.getName(), node.getType())).toList();
        return new BpmnProcess(original.getId(), original.getName(), nodes, original.getSequenceFlows());
    }

    private XSSFWorkbook read(Path file) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            return new XSSFWorkbook(stream);
        }
    }

    private void assertSheet(Sheet sheet, String csv, int rows, int columns) {
        List<List<String>> expected = parseCsv(csv);
        assertEquals(rows, sheet.getPhysicalNumberOfRows());
        assertEquals(rows, expected.size());
        for (int r = 0; r < rows; r++) {
            assertEquals(columns, sheet.getRow(r).getPhysicalNumberOfCells());
            assertEquals(columns, expected.get(r).size());
            for (int c = 0; c < columns; c++) {
                var cell = sheet.getRow(r).getCell(c);
                boolean returns = sheet.getSheetName().equals("Caminos") && r > 0 && c == 2;
                assertEquals(returns ? CellType.NUMERIC : CellType.STRING, cell.getCellType(), "Nunca debe ser una fórmula");
                String value = new DataFormatter().formatCellValue(cell);
                String protectedValue = value;
                String trimmed = value.stripLeading();
                if (trimmed.length() > 1 && "=+-@".indexOf(trimmed.charAt(0)) >= 0) {
                    protectedValue = "'" + value; // Solo el CSV necesita el prefijo de seguridad.
                }
                assertEquals(expected.get(r).get(c), protectedValue, sheet.getSheetName() + " fila " + r + " columna " + c);
            }
        }
    }

    /** Decodifica comillas, comas y saltos de línea; no depende de las filas internas de FlowTable. */
    private List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && (ch == ',' || ch == '\n')) {
                row.add(value.toString());
                value.setLength(0);
                if (ch == '\n') {
                    rows.add(row);
                    row = new ArrayList<>();
                }
            } else {
                value.append(ch);
            }
        }
        assertFalse(quoted, "CSV con comillas sin cerrar");
        return rows;
    }

    private void assertDirectoryContainsOnly(String name) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(List.of(name), files.map(path -> path.getFileName().toString()).toList());
        }
    }
}
