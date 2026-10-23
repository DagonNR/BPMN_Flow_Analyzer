package com.pia.vvs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
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
        assertTrue(Files.isRegularFile(Path.of("target/generated-tests/ApplicationProcess.csv")));
        String feature = Files.readString(Path.of("target/generated-tests/ApplicationProcess.feature"));
        assertEquals(7, feature.lines().filter(line -> line.strip().startsWith("Escenario:")).count());
    }

    @Test
    void acceptsExplicitPathsAndAZeroReturnLimit() throws Exception {
        Path output = directory.resolve("salida con espacios");

        Execution result = run(APPLICATION, APPLICATION_ID, output.toString(), "0");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Caminos completos: 3"));
        assertEquals(5, Files.readString(output.resolve("ApplicationProcess.csv")).lines().count());
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
    void preservesHardwareGraphAnalysisButRejectsUnsupportedGeneration() throws Exception {
        Execution graph = run("bpmn/HardwareRetailer.bpmn", "_6-1");
        assertEquals(0, graph.exitCode(), graph.output());
        assertTrue(graph.output().contains("Nodos: 16"));
        assertTrue(graph.output().contains("generación Gherkin solo está disponible para ApplicationProcess"));

        Path output = directory.resolve("hardware");
        Execution generation = run("bpmn/HardwareRetailer.bpmn", "_6-1", output.toString());
        assertEquals(1, generation.exitCode(), generation.output());
        assertFalse(Files.exists(output));
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
                "-cp", Path.of("target/classes").toAbsolutePath().toString(),
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
