package com.pia.vvs;

import com.pia.vvs.output.CucumberStepGenerator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import io.cucumber.gherkin.GherkinParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    private static final String APPLICATION_ID = "gov.medicaid.process.application";
    private static final String APPLICATION = "bpmn/ApplicationProcess.bpmn";

    @TempDir
    Path directory;

    @Test
    void defaultInvocationGeneratesTheTableAndFeature() throws Exception {
        Execution result = run();

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Caminos completos: 7"));
        assertTrue(result.output().contains("Rutas truncadas: 1"));
        assertTrue(result.output().contains("Flujos por ramas (BRANCH_VIEW): 7"));
        assertEquals(8, Files.readString(Path.of("target/generated-tests/ApplicationProcess-ramas.csv")).lines().count());
        assertTrue(Files.isRegularFile(Path.of("target/generated-tests/ApplicationProcess.csv")));
        try (var stream = Files.newInputStream(Path.of("target/generated-tests/ApplicationProcess.xlsx"));
             var workbook = new XSSFWorkbook(stream)) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals(9, workbook.getSheet("Caminos").getPhysicalNumberOfRows());
            assertEquals(8, workbook.getSheet("Ramas").getPhysicalNumberOfRows());
        }
        String feature = Files.readString(Path.of("target/generated-tests/ApplicationProcess.feature"));
        assertEquals(7, feature.lines().filter(line -> line.strip().startsWith("Escenario:")).count());
        Path java = new CucumberStepGenerator().destination(Path.of("target/generated-tests"), "ApplicationProcess");
        assertTrue(Files.readString(java).contains("public class ApplicationProcessSteps"));
        assertTrue(result.output().contains("PendingException"));
    }

    @Test
    void acceptsExplicitPathsAndAZeroReturnLimit() throws Exception {
        Path output = directory.resolve("salida con espacios");

        Execution result = run(APPLICATION, APPLICATION_ID, output.toString(), "0");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Caminos completos: 3"));
        assertEquals(5, Files.readString(output.resolve("ApplicationProcess.csv")).lines().count());
        assertEquals(4, Files.readString(output.resolve("ApplicationProcess-ramas.csv")).lines().count());
        assertEquals(3, Files.readString(output.resolve("ApplicationProcess.feature")).lines()
                .filter(line -> line.strip().startsWith("Escenario:")).count());
    }

    @Test
    void acceptsTheExistingTwoArgumentInvocation() throws Exception {
        Execution result = run(APPLICATION, APPLICATION_ID);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Caminos completos: 7"));
    }

    @Test
    void preservesHardwareGraphAnalysisAndGeneratesItsTablesAndGherkin() throws Exception {
        Execution graph = run("bpmn/HardwareRetailer.bpmn", "_6-1");
        assertEquals(0, graph.exitCode(), graph.output());
        assertTrue(graph.output().contains("Nodos: 16"));
        assertTrue(graph.output().contains("Caminos completos: 4"));
        assertTrue(graph.output().contains("Rutas truncadas: 0"));
        assertTrue(graph.output().contains("Flujos por ramas (BRANCH_VIEW): 5"));
        assertTrue(graph.output().contains("COMPLETE no garantiza una ejecución viable"));
        assertTrue(graph.output().contains("Escenarios Gherkin generados: 4"));

        Path output = directory.resolve("hardware");
        Execution generation = run("bpmn/HardwareRetailer.bpmn", "_6-1", output.toString());
        assertEquals(0, generation.exitCode(), generation.output());
        assertEquals(5, Files.readString(output.resolve("HardwareRetailer.csv")).lines().count());
        assertEquals(6, Files.readString(output.resolve("HardwareRetailer-ramas.csv")).lines().count());
        assertFeature(output.resolve("HardwareRetailer.feature"), 4);
        try (var files = Files.list(output)) {
            assertEquals(List.of("HardwareRetailer-ramas.csv", "HardwareRetailer.csv", "HardwareRetailer.feature", "HardwareRetailer.xlsx", "steps"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void reportsMissingClassificationAndPreservesAnyPreviousFeature() throws Exception {
        Path input = Files.copy(Path.of("bpmn/HardwareRetailer.bpmn"), directory.resolve("model.bpmn"));
        Path output = Files.createDirectory(directory.resolve("output"));
        Path previous = Files.writeString(output.resolve("model.feature"), "resultado anterior");
        Path oldJava = new CucumberStepGenerator().destination(output, "model");
        Files.createDirectories(oldJava.getParent());
        Files.writeString(oldJava, "implementación anterior");
        Execution result = run(input.toString(), "_6-1", output.toString());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("No se generará ni actualizará Gherkin: tareas genéricas sin clasificar"));
        assertTrue(result.output().contains("model.tasks.properties"));
        assertEquals("resultado anterior", Files.readString(previous));
        assertEquals("implementación anterior", Files.readString(oldJava));
        assertTrue(result.output().contains("Tampoco se generará Java de pasos"));
        assertFalse(result.output().substring(result.output().indexOf("Archivos generados:")).contains("Steps.java"));
        assertTrue(Files.isRegularFile(output.resolve("model.xlsx")));
        assertTrue(Files.isRegularFile(output.resolve("model-ramas.csv")));
        assertFalse(result.output().contains("Escenarios Gherkin generados:"));
    }

    @Test
    void loadsClassificationBesideRenamedInputWithoutDependingOnTheHardwareFileName() throws Exception {
        Path input = Files.copy(Path.of("bpmn/HardwareRetailer.bpmn"), directory.resolve("otro nombre.bpmn"));
        Files.copy(Path.of("bpmn/HardwareRetailer.tasks.properties"), directory.resolve("otro nombre.tasks.properties"));
        Path output = directory.resolve("output");
        Execution result = run(input.toString(), "_6-1", output.toString());
        assertEquals(0, result.exitCode(), result.output());
        assertFeature(output.resolve("otro nombre.feature"), 4);
    }

    @Test
    void rejectsContradictoryClassificationBeforeWritingAnyOutput() throws Exception {
        Path input = Files.copy(Path.of(APPLICATION), directory.resolve("app.bpmn"));
        Files.writeString(directory.resolve("app.tasks.properties"), "processId=" + APPLICATION_ID + "\n_3=excluded");
        Path output = directory.resolve("output");
        Execution result = run(input.toString(), APPLICATION_ID, output.toString());
        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("Clasificación contradictoria"));
        assertFalse(Files.exists(output));
    }

    @Test
    void neverOverwritesHardwareInputNamedLikeItsFeature() throws Exception {
        Path input = Files.copy(Path.of("bpmn/HardwareRetailer.bpmn"), directory.resolve("model.feature"));
        byte[] original = Files.readAllBytes(input);
        Files.copy(Path.of("bpmn/HardwareRetailer.tasks.properties"), directory.resolve("model.tasks.properties"));
        Execution result = run(input.toString(), "_6-1", directory.toString());
        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("no puede sobrescribir el BPMN de entrada"));
        assertArrayEquals(original, Files.readAllBytes(input));
        assertFalse(Files.exists(directory.resolve("model.csv")));
    }

    private void assertFeature(Path file, int scenarios) throws Exception {
        try (var stream = GherkinParser.builder().build().parse(file)) {
            var envelopes = stream.toList();
            assertTrue(envelopes.stream().noneMatch(envelope -> envelope.getParseError().isPresent()));
            assertEquals(scenarios, envelopes.stream().filter(envelope -> envelope.getPickle().isPresent()).count());
        }
    }

    @Test
    void repeatedGenerationPreservesAnIdenticalJavaFile() throws Exception {
        Path output = directory.resolve("output");
        assertEquals(0, run(APPLICATION, APPLICATION_ID, output.toString()).exitCode());
        Path java = new CucumberStepGenerator().destination(output, "ApplicationProcess");
        byte[] original = Files.readAllBytes(java);
        var modified = Files.getLastModifiedTime(java);
        Execution second = run(APPLICATION, APPLICATION_ID, output.toString());
        assertEquals(0, second.exitCode(), second.output());
        assertArrayEquals(original, Files.readAllBytes(java));
        assertEquals(modified, Files.getLastModifiedTime(java));
    }

    @Test
    void protectsManualImplementationsBeforeUpdatingAnyOtherResult() throws Exception {
        Path output = directory.resolve("output");
        assertEquals(0, run(APPLICATION, APPLICATION_ID, output.toString(), "0").exitCode());
        Path java = new CucumberStepGenerator().destination(output, "ApplicationProcess");
        String implemented = Files.readString(java) + "\n// Trabajo manual que debe conservarse\n";
        Files.writeString(java, implemented);
        byte[] previousCsv = Files.readAllBytes(output.resolve("ApplicationProcess.csv"));
        byte[] previousFeature = Files.readAllBytes(output.resolve("ApplicationProcess.feature"));
        byte[] previousExcel = Files.readAllBytes(output.resolve("ApplicationProcess.xlsx"));
        Execution result = run(APPLICATION, APPLICATION_ID, output.toString(), "2");
        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("Se conserva el Java existente"));
        assertTrue(result.output().contains("otro directorio"));
        assertEquals(implemented, Files.readString(java));
        assertArrayEquals(previousCsv, Files.readAllBytes(output.resolve("ApplicationProcess.csv")));
        assertArrayEquals(previousFeature, Files.readAllBytes(output.resolve("ApplicationProcess.feature")));
        assertArrayEquals(previousExcel, Files.readAllBytes(output.resolve("ApplicationProcess.xlsx")));
    }

    @Test
    void reportsJavaWriteErrorsWithoutProducingTablesOrFeatures() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        Path blocker = Files.writeString(output.resolve("steps"), "conservar");
        Execution result = run(APPLICATION, APPLICATION_ID, output.toString());
        assertEquals(1, result.exitCode(), result.output());
        assertEquals("conservar", Files.readString(blocker));
        assertFalse(Files.exists(output.resolve("ApplicationProcess.csv")));
        assertFalse(Files.exists(output.resolve("ApplicationProcess.xlsx")));
        assertFalse(Files.exists(output.resolve("ApplicationProcess.feature")));
    }

    @Test
    void neverOverwritesAnInputAtTheGeneratedJavaDestination() throws Exception {
        Path input = new CucumberStepGenerator().destination(directory, "ApplicationProcess");
        Files.createDirectories(input.getParent());
        Files.copy(Path.of(APPLICATION), input);
        byte[] original = Files.readAllBytes(input);
        Execution result = run(input.toString(), APPLICATION_ID, directory.toString());
        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("no puede sobrescribir el BPMN de entrada"));
        assertArrayEquals(original, Files.readAllBytes(input));
        assertFalse(Files.exists(directory.resolve("ApplicationProcess.csv")));
    }

    @Test
    void validatesExcelDestinationBeforeWritingAnyResults() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        Files.createDirectory(output.resolve("ApplicationProcess.xlsx"));

        Execution result = run(APPLICATION, APPLICATION_ID, output.toString());

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("La salida no es un archivo regular"));
        assertFalse(Files.exists(output.resolve("ApplicationProcess.csv")));
        assertFalse(Files.exists(output.resolve("ApplicationProcess-ramas.csv")));
        assertFalse(Files.exists(output.resolve("ApplicationProcess.feature")));
    }

    @Test
    void neverOverwritesAnInputNamedLikeTheExcelOutput() throws Exception {
        String xml = Files.readString(Path.of(APPLICATION));
        Path input = Files.writeString(directory.resolve("ApplicationProcess.xlsx"), xml);

        Execution result = run(input.toString(), APPLICATION_ID, directory.toString());

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("no puede sobrescribir el BPMN de entrada"));
        assertEquals(xml, Files.readString(input));
        assertFalse(Files.exists(directory.resolve("ApplicationProcess.csv")));
    }

    @Test
    void validatesBranchDestinationBeforeWritingAnyResults() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        Files.createDirectory(output.resolve("ApplicationProcess-ramas.csv"));

        Execution result = run(APPLICATION, APPLICATION_ID, output.toString());

        assertEquals(1, result.exitCode(), result.output());
        assertFalse(Files.exists(output.resolve("ApplicationProcess.csv")));
        assertFalse(Files.exists(output.resolve("ApplicationProcess.feature")));
    }

    @Test
    void neverOverwritesAnInputNamedLikeTheBranchOutput() throws Exception {
        String xml = Files.readString(Path.of(APPLICATION));
        Path input = Files.writeString(directory.resolve("ApplicationProcess-ramas.csv"), xml);

        Execution result = run(input.toString(), APPLICATION_ID, directory.toString());

        assertEquals(1, result.exitCode(), result.output());
        assertEquals(xml, Files.readString(input));
        assertFalse(Files.exists(directory.resolve("ApplicationProcess.csv")));
    }

    @Test
    void unsupportedBranchViewStillGeneratesJointPathsAndReportsTheLimitation() throws Exception {
        Path input = Files.writeString(directory.resolve("unpaired.bpmn"), """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="test">
                    <startEvent id="start"/><parallelGateway id="split"/>
                    <endEvent id="a"/><endEvent id="b"/>
                    <sequenceFlow id="s" sourceRef="start" targetRef="split"/>
                    <sequenceFlow id="fa" sourceRef="split" targetRef="a"/>
                    <sequenceFlow id="fb" sourceRef="split" targetRef="b"/>
                  </process>
                </definitions>
                """);
        Path output = Files.createDirectory(directory.resolve("unpaired-output"));
        Path previousBranchFile = Files.writeString(output.resolve("unpaired-ramas.csv"), "resultado anterior");

        Execution result = run(input.toString(), "test", output.toString());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Caminos completos: 1"));
        assertTrue(result.output().contains("Vista por ramas no soportada"));
        assertTrue(result.output().contains("No se generará ni actualizará el CSV por ramas"));
        assertFalse(result.output().contains("Flujos por ramas (BRANCH_VIEW):"));
        assertEquals(2, Files.readString(output.resolve("unpaired.csv")).lines().count());
        assertEquals("resultado anterior", Files.readString(previousBranchFile));
        try (var stream = Files.newInputStream(output.resolve("unpaired.xlsx"));
             var workbook = new XSSFWorkbook(stream)) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals(2, workbook.getSheet("Caminos").getPhysicalNumberOfRows());
            var branches = workbook.getSheet("Ramas");
            assertEquals(2, branches.getPhysicalNumberOfRows());
            assertTrue(branches.getRow(1).getCell(0).getStringCellValue().startsWith("NO DISPONIBLE:"));
        }
        assertFalse(result.output().substring(result.output().indexOf("Archivos generados:")).contains("unpaired-ramas.csv"));
    }

    @Test
    void rejectsInvalidArgumentsWithReadableErrors() throws Exception {
        List<String[]> invalid = List.of(new String[]{APPLICATION}, new String[]{" ", APPLICATION_ID},
                new String[]{APPLICATION, APPLICATION_ID, " "},
                new String[]{APPLICATION, APPLICATION_ID, directory.toString(), "-1"},
                new String[]{APPLICATION, APPLICATION_ID, directory.toString(), "101"},
                new String[]{APPLICATION, APPLICATION_ID, directory.toString(), "no-es-numero"},
                new String[]{APPLICATION, APPLICATION_ID, directory.toString(), "2", "extra"});
        for (String[] args : invalid) {
            Execution result = run(args);
            assertEquals(1, result.exitCode(), result.output());
            assertFalse(result.output().contains("Exception in thread"));
        }
    }

    @Test
    void validatesTheModelBeforeCreatingOutputFiles() throws Exception {
        String xml = Files.readString(Path.of(APPLICATION)).replace("sourceRef=\"_7\" targetRef=\"_34\"",
                "sourceRef=\"_7\" targetRef=\"_9\"");
        Path changed = Files.writeString(directory.resolve("changed.bpmn"), xml);
        Path output = directory.resolve("changed-output");

        Execution result = run(changed.toString(), APPLICATION_ID, output.toString());

        assertEquals(1, result.exitCode(), result.output());
        assertFalse(Files.exists(output));
    }

    @Test
    void reportsWriteErrorsAndChecksBothDestinationsBeforeWriting() throws Exception {
        Path file = Files.writeString(directory.resolve("file"), "conservar");
        assertEquals(1, run(APPLICATION, APPLICATION_ID, file.toString()).exitCode());
        assertEquals("conservar", Files.readString(file));

        Path output = Files.createDirectory(directory.resolve("output"));
        Files.createDirectory(output.resolve("ApplicationProcess.feature"));
        Execution result = run(APPLICATION, APPLICATION_ID, output.toString());
        assertEquals(1, result.exitCode(), result.output());
        assertFalse(Files.exists(output.resolve("ApplicationProcess.csv")));
        assertFalse(result.output().contains("Archivos generados:"));
    }

    @Test
    void neverOverwritesTheInputEvenWhenItHasAnOutputFileName() throws Exception {
        String xml = Files.readString(Path.of(APPLICATION));
        Path input = Files.writeString(directory.resolve("ApplicationProcess.feature"), xml);

        Execution result = run(input.toString(), APPLICATION_ID, directory.toString());

        assertEquals(1, result.exitCode(), result.output());
        assertEquals(xml, Files.readString(input));
        assertFalse(Files.exists(directory.resolve("ApplicationProcess.csv")));
    }

    @Test
    void reportsMissingFilesAndMalformedXmlWithoutGeneratingResults() throws Exception {
        assertEquals(1, run(directory.resolve("missing.bpmn").toString(), APPLICATION_ID).exitCode());
        Path malformed = Files.writeString(directory.resolve("invalid.bpmn"), "<definitions>");
        Path output = directory.resolve("invalid-output");
        Execution result = run(malformed.toString(), APPLICATION_ID, output.toString());
        assertEquals(1, result.exitCode(), result.output());
        assertFalse(Files.exists(output));
    }

    private Execution run(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", Path.of("target/classes").toAbsolutePath() + File.pathSeparator
                        + Path.of("target/dependency").toAbsolutePath() + File.separator + "*",
                "com.pia.vvs.Main"));
        command.addAll(List.of(args));
        Path log = Files.createTempFile(directory, "execution-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "La ejecución no terminó dentro del límite.");
            return new Execution(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private record Execution(int exitCode, String output) { }
}
