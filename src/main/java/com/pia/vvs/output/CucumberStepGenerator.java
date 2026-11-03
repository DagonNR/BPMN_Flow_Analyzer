package com.pia.vvs.output;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Transforma Gherkin en esqueletos Java; no conoce el grafo ni ejecuta acciones. */
public class CucumberStepGenerator {
    public String className(String modelName) {
        String name = Normalizer.normalize(modelName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^a-zA-Z0-9_]", "_");
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            name = "Model" + name;
        }
        return name + "Steps";
    }

    public String packageName(String modelName) {
        return "com.pia.vvs.generated." + className(modelName).toLowerCase(Locale.ROOT);
    }

    public Path destination(Path output, String modelName) {
        return output.resolve("steps").resolve(packageName(modelName).replace('.', '/'))
                .resolve(className(modelName) + ".java");
    }

    public String generate(String feature, String modelName) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        var envelopes = GherkinParser.builder().build()
                .parse(modelName + ".feature", feature.getBytes(StandardCharsets.UTF_8)).toList();
        for (var envelope : envelopes) {
            if (envelope.getParseError().isPresent()) {
                throw new IllegalArgumentException("Gherkin inválido: " + envelope.getParseError().orElseThrow().getMessage());
            }
        }
        if (envelopes.stream().flatMap(envelope -> envelope.getGherkinDocument().stream())
                .noneMatch(document -> document.getFeature().isPresent())) {
            throw new IllegalArgumentException("El texto Gherkin no contiene una característica.");
        }
        // Los pickles del parser ya resuelven Y según su tipo anterior y expanden los escenarios.
        for (var envelope : envelopes) {
            if (envelope.getPickle().isEmpty()) continue;
            for (PickleStep step : envelope.getPickle().orElseThrow().getSteps()) {
                Definition definition = definition(step);
                Definition previous = definitions.putIfAbsent(step.getText(), definition);
                if (previous != null && !previous.equals(definition)) {
                    throw new IllegalArgumentException("Paso ambiguo: '" + step.getText()
                            + "' tiene tipos lógicos o argumentos distintos. Renombre el paso para distinguir su intención.");
                }
            }
        }
        StringBuilder java = new StringBuilder("package " + packageName(modelName) + ";\n\n")
                .append("import io.cucumber.java.PendingException;\n")
                .append("import io.cucumber.java.es.Dado;\nimport io.cucumber.java.es.Cuando;\nimport io.cucumber.java.es.Entonces;\n");
        if (definitions.values().stream().anyMatch(definition -> definition.parameter().equals("DataTable table"))) {
            java.append("import io.cucumber.datatable.DataTable;\n");
        }
        java.append("\n// Esqueleto generado. Copiar fuera de target antes de implementar.\n")
                .append("// Los pasos pendientes no constituyen pruebas funcionales aprobadas.\n")
                .append("public class ").append(className(modelName)).append(" {\n");
        int number = 0;
        for (var entry : definitions.entrySet()) {
            Definition definition = entry.getValue();
            java.append("\n    @").append(definition.annotation()).append("(\"")
                    .append(javaString(literalExpression(entry.getKey())))
                    .append("\")\n    public void paso").append(++number).append('(')
                    .append(definition.parameter()).append(") {\n")
                    .append("        throw new PendingException(\"Implementar este paso en la aplicación real.\");\n    }\n");
        }
        return java.append("}\n").toString();
    }

    private Definition definition(PickleStep step) {
        String annotation = switch (step.getType().orElse(PickleStepType.UNKNOWN)) {
            case CONTEXT -> "Dado";
            case ACTION -> "Cuando";
            case OUTCOME -> "Entonces";
            case UNKNOWN -> throw new IllegalArgumentException("Paso sin tipo lógico: " + step.getText()
                    + ". Use Dado, Cuando o Entonces antes de Y.");
        };
        String parameter = "";
        if (step.getArgument().isPresent()) {
            var argument = step.getArgument().orElseThrow();
            if (argument.getDataTable().isPresent() && argument.getDocString().isPresent()) {
                throw new IllegalArgumentException("Paso con tabla y DocString simultáneos no soportado: " + step.getText());
            }
            parameter = argument.getDataTable().isPresent() ? "DataTable table" : "String docString";
        }
        return new Definition(annotation, parameter);
    }

    private String literalExpression(String text) {
        StringBuilder expression = new StringBuilder("^");
        // Escapes individuales: el analizador de grupos de Cucumber no interpreta todos los bloques Q/E de Java.
        for (char ch : text.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(ch) >= 0) expression.append('\\');
            expression.append(ch);
        }
        return expression.append('$').toString();
    }

    private String javaString(String text) {
        StringBuilder escaped = new StringBuilder();
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(Character.isISOControl(ch) ? String.format("\\%03o", (int) ch) : ch);
            }
        }
        return escaped.toString();
    }

    /** Nunca reemplazar una clase existente distinta, aunque todavía parezca un esqueleto. */
    public void validateExisting(Path destination, String source) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(destination) || !Files.readString(destination).equals(source))) {
            throw new IOException("Se conserva el Java existente: " + destination
                    + ". Difiere del generado o no es un archivo regular. Use otro directorio de salida y revise los cambios manualmente.");
        }
    }

    public void write(Path destination, String source) throws IOException {
        validateExisting(destination, source);
        if (Files.exists(destination)) return;
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "steps-", ".tmp");
        try {
            Files.writeString(temporary, source);
            Files.move(temporary, destination); // Sin REPLACE_EXISTING: tampoco sobrescribe si aparece durante la escritura.
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record Definition(String annotation, String parameter) { }
}
