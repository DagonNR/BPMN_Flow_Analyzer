package com.pia.vvs.output;

import com.pia.vvs.analysis.PathAnalyzer;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.parser.BpmnParser;
import io.cucumber.core.feature.FeatureWithLines;
import io.cucumber.core.options.RuntimeOptionsBuilder;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.datatable.DataTable;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.java.PendingException;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestStepFinished;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CucumberStepGeneratorTest {
    @TempDir
    Path directory;
    private final CucumberStepGenerator generator = new CucumberStepGenerator();

    @ParameterizedTest
    @CsvSource({"ApplicationProcess,gov.medicaid.process.application,7", "HardwareRetailer,_6-1,4"})
    void compilesModelsLinksEveryStepAndReportsPendingInsteadOfFunctionalSuccess(String model, String id, int scenarios) throws Exception {
        var process = new BpmnParser().parse(Path.of("bpmn", model + ".bpmn"), id);
        var classification = model.equals("HardwareRetailer")
                ? TaskClassification.load(process, Path.of("bpmn/HardwareRetailer.tasks.properties"))
                : TaskClassification.automatic(process);
        var paths = new PathAnalyzer().analyze(new ProcessGraph(process), 2);
        String feature = new GherkinGenerator().generate(process, paths, classification);
        verifyGeneratedCode(feature, model, scenarios);
    }

    @Test
    void reusesAndStepsTablesAndDocStringsAcrossSimilarScenarioNames() throws Exception {
        String feature = """
                # language: es
                Característica: Datos compartidos
                  Escenario: Revisión 1
                    Dado que existe una solicitud
                    Y que existe una solicitud
                    Cuando se revisan las tareas
                      | tarea | id |
                      | A     | 1  |
                    Y se revisan las tareas
                      | tarea | id |
                      | B     | 2  |
                    Entonces se obtiene el informe
                      \"\"\"
                      contenido
                      \"\"\"
                    Y se obtiene el informe
                      \"\"\"
                      otro contenido
                      \"\"\"
                  Escenario: Revisión 2
                    Dado que existe una solicitud
                    Cuando se revisan las tareas
                      | tarea | id |
                      | C     | 3  |
                    Entonces se obtiene el informe
                      \"\"\"
                      contenido final
                      \"\"\"
                """;
        verifyGeneratedCode(feature, "Datos", 2);
        String source = generator.generate(feature, "Datos");
        assertEquals(3, source.lines().filter(line -> line.contains("public void paso")).count());
        assertTrue(source.contains("DataTable table"));
        assertTrue(source.contains("String docString"));
    }

    @Test
    void compilesAndMatchesLiteralQuotesAccentsRegexMetacharactersAndUnicodeEscapeText() throws Exception {
        String text = "revisión \"ñ\" \\ ruta \\E {int} (opcional) [a-z]+ .* $ ^ / | 漢字 \\u000a";
        String feature = "# language: es\nCaracterística: Especial\n  Escenario: literal\n    Cuando " + text + "\n";
        verifyGeneratedCode(feature, "123 revisión-especial", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123", "class", "a-b", "a b", "ñá", "../../modelo"})
    void producesValidClassAndPackageNamesWithoutPathTraversal(String name) throws Exception {
        String feature = "# language: es\nCaracterística: Nombre\n  Escenario: Uno\n    Dado un paso\n";
        Path file = generator.destination(directory, name);
        assertTrue(file.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()));
        compile(feature, name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Dado texto\n    Cuando texto",
            "Cuando texto\n    Entonces texto",
            "Dado texto\n    Y texto\n      | dato |",
            "Cuando texto\n      | dato |\n    Y texto\n      \"\"\"\n      dato\n      \"\"\""
    })
    void rejectsSameTextWithDifferentRolesOrArgumentShapes(String steps) {
        String feature = "# language: es\nCaracterística: Ambigua\n  Escenario: Uno\n    " + steps + "\n";
        var error = assertThrows(IllegalArgumentException.class, () -> generator.generate(feature, "Modelo"));
        assertTrue(error.getMessage().contains("Paso ambiguo"), error.getMessage());
        assertTrue(error.getMessage().contains("Renombre"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Esto no es Gherkin", "", "# language: es\nCaracterística: X\n  Escenario: Y\n    Y sin tipo previo"})
    void rejectsInvalidGherkinOrStepsWithoutALogicalType(String feature) {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(feature, "Modelo"));
    }

    @Test
    void refusesCombinedTableAndDocStringRatherThanDroppingAnArgument() {
        String feature = "# language: es\nCaracterística: X\n  Escenario: Uno\n    Cuando ambos\n"
                + "      | dato |\n      \"\"\"\n      texto\n      \"\"\"\n";
        var error = assertThrows(IllegalArgumentException.class, () -> generator.generate(feature, "Modelo"));
        assertTrue(error.getMessage().contains("simultáneos"), error.getMessage());
    }

    @Test
    void preservesExistingImplementationsAndLeavesNoTemporaryFiles() throws Exception {
        Path file = directory.resolve("Steps.java");
        generator.write(file, "original");
        var timestamp = Files.getLastModifiedTime(file);
        generator.write(file, "original");
        assertEquals(timestamp, Files.getLastModifiedTime(file));
        Files.writeString(file, "implementación manual");
        assertThrows(java.io.IOException.class, () -> generator.write(file, "original"));
        assertEquals("implementación manual", Files.readString(file));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void reportsInvalidDestinationsWithoutReplacingFilesOrDirectories() throws Exception {
        Path parent = Files.writeString(directory.resolve("parent"), "conservar");
        assertThrows(java.io.IOException.class, () -> generator.write(parent.resolve("Steps.java"), "source"));
        assertEquals("conservar", Files.readString(parent));
        Path folder = Files.createDirectory(directory.resolve("Steps.java"));
        assertThrows(java.io.IOException.class, () -> generator.write(folder, "source"));
        assertTrue(Files.isDirectory(folder));
    }

    private void verifyGeneratedCode(String feature, String model, int scenarios) throws Exception {
        Path classes = compile(feature, model);
        Path featureFile = Files.writeString(directory.resolve("model.feature"), feature);
        String qualifiedName = generator.packageName(model) + "." + generator.className(model);
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> generated = Class.forName(qualifiedName, true, loader);
            List<PickleStep> steps = GherkinParser.builder().build().parse("model.feature", feature.getBytes(StandardCharsets.UTF_8))
                    .flatMap(envelope -> envelope.getPickle().stream()).flatMap(pickle -> pickle.getSteps().stream()).toList();
            Method[] methods = generated.getDeclaredMethods();
            assertEquals(steps.stream().map(PickleStep::getText).distinct().count(), methods.length);
            assertEquals(methods.length, Arrays.stream(methods).map(Method::getName).distinct().count());
            for (PickleStep step : steps) {
                List<Method> matching = new ArrayList<>();
                for (Method method : methods) {
                    var annotation = method.getDeclaredAnnotations()[0];
                    String expression = (String) annotation.annotationType().getMethod("value").invoke(annotation);
                    if (Pattern.compile(expression).matcher(step.getText()).matches()) matching.add(method);
                }
                assertEquals(1, matching.size(), step.getText());
                Method method = matching.get(0);
                assertEquals(step.getArgument().isPresent() ? 1 : 0, method.getParameterCount());
                if (step.getArgument().isPresent()) {
                    assertEquals(step.getArgument().orElseThrow().getDataTable().isPresent() ? DataTable.class : String.class,
                            method.getParameterTypes()[0]);
                }
            }
            Object instance = generated.getConstructor().newInstance();
            for (Method method : methods) {
                Object[] arguments = method.getParameterCount() == 0 ? new Object[0]
                        : new Object[]{method.getParameterTypes()[0] == DataTable.class
                        ? DataTable.create(List.of(List.of("dato"))) : "contenido"};
                var exception = assertThrows(InvocationTargetException.class, () -> method.invoke(instance, arguments));
                assertInstanceOf(PendingException.class, exception.getCause());
            }
            RunResult dryRun = runCucumber(loader, featureFile, qualifiedName, true);
            assertEquals(0, dryRun.exitCode());
            assertEquals(scenarios, dryRun.events().scenarios.size());
            assertEquals(steps.size(), dryRun.events().steps.size());
            assertEquals(Set.of(Status.PASSED), Set.copyOf(dryRun.events().steps)); // Solo comprobación de enlace.
            RunResult execution = runCucumber(loader, featureFile, qualifiedName, false);
            assertNotEquals(0, execution.exitCode());
            assertEquals(scenarios, execution.events().scenarios.size());
            assertEquals(Set.of(Status.PENDING), Set.copyOf(execution.events().scenarios));
            assertEquals(scenarios, execution.events().steps.stream().filter(status -> status == Status.PENDING).count());
            assertTrue(execution.events().steps.stream().allMatch(status -> status == Status.PENDING || status == Status.SKIPPED));
        }
    }

    private Path compile(String feature, String model) throws Exception {
        String source = generator.generate(feature, model);
        assertEquals(source, generator.generate(feature, model), "Generación determinista");
        Path file = generator.destination(directory, model);
        generator.write(file, source);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Las pruebas requieren un JDK con javac");
        var errors = new ByteArrayOutputStream();
        int result = compiler.run(null, errors, errors, "--release", "17", "-encoding", "UTF-8",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), file.toString());
        assertEquals(0, result, errors.toString(StandardCharsets.UTF_8));
        Path bytecode = classes.resolve(generator.packageName(model).replace('.', '/')).resolve(generator.className(model) + ".class");
        byte[] bytes = Files.readAllBytes(bytecode);
        assertEquals(61, ((bytes[6] & 255) << 8) | (bytes[7] & 255), "Bytecode Java 17");
        return classes;
    }

    private RunResult runCucumber(ClassLoader loader, Path feature, String className, boolean dryRun) {
        var options = new RuntimeOptionsBuilder().addGlueClass(className)
                .addFeature(FeatureWithLines.parse(feature.toString())).setDryRun(dryRun).setNoSummary().build();
        Results events = new Results();
        Runtime runtime = Runtime.builder().withClassLoader(() -> loader).withRuntimeOptions(options)
                .withAdditionalPlugins(events).build();
        runtime.run();
        return new RunResult(runtime.exitStatus(), events);
    }

    private record RunResult(byte exitCode, Results events) { }

    private static class Results implements ConcurrentEventListener {
        private final List<Status> scenarios = new ArrayList<>();
        private final List<Status> steps = new ArrayList<>();

        @Override
        public void setEventPublisher(EventPublisher publisher) {
            publisher.registerHandlerFor(TestCaseFinished.class, event -> scenarios.add(event.getResult().getStatus()));
            publisher.registerHandlerFor(TestStepFinished.class, event -> steps.add(event.getResult().getStatus()));
        }
    }
}
