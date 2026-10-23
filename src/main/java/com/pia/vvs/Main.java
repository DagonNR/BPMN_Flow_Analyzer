package com.pia.vvs;

import com.pia.vvs.analysis.ApplicationProcessRules;
import com.pia.vvs.analysis.PathAnalyzer;
import com.pia.vvs.analysis.ProcessPath;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import com.pia.vvs.parser.BpmnParser;
import com.pia.vvs.output.FlowTable;
import com.pia.vvs.output.GherkinGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        try {
            validateArguments(args);
            Path file = args.length == 0 ? Path.of("bpmn", "ApplicationProcess.bpmn") : Path.of(args[0]);
            String processId = args.length == 0 ? "gov.medicaid.process.application" : args[1];
            int maxReturns = args.length == 4 ? Integer.parseInt(args[3]) : 2;
            if (maxReturns < 0 || maxReturns > 100) {
                throw new IllegalArgumentException("El límite de retornos debe estar entre 0 y 100.");
            }
            BpmnProcess process = new BpmnParser().parse(file, processId);
            ProcessGraph graph = new ProcessGraph(process);
            printResults(file, process);
            printGraph(graph);
            if (ApplicationProcessRules.PROCESS_ID.equals(processId)) {
                Path output = args.length >= 3 ? Path.of(args[2]) : Path.of("target", "generated-tests");
                generateTests(file, output, graph, maxReturns);
            } else if (args.length >= 3) {
                throw new IllegalArgumentException("La generación Gherkin solo está disponible para ApplicationProcess.");
            } else {
                System.out.println("La generación Gherkin solo está disponible para ApplicationProcess.");
            }
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("No se pudo analizar el BPMN: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void validateArguments(String[] args) {
        if (args.length == 1 || args.length > 4) {
            throw new IllegalArgumentException("Uso: java -cp target/classes com.pia.vvs.Main "
                    + "[archivo.bpmn procesoId [directorioSalida [maxRetornos]]]");
        }
        for (String argument : args) {
            if (argument.isBlank()) {
                throw new IllegalArgumentException("Los argumentos no pueden estar vacíos.");
            }
        }
    }

    private static void generateTests(Path input, Path output, ProcessGraph graph, int maxReturns) throws IOException {
        List<ProcessPath> paths = new PathAnalyzer().analyze(graph, maxReturns);
        FlowTable table = new FlowTable();
        String csv = table.toCsv(graph.getProcess(), paths);
        String feature = new GherkinGenerator().generate(graph.getProcess(), paths);
        Path csvFile = output.resolve("ApplicationProcess.csv");
        Path featureFile = output.resolve("ApplicationProcess.feature");
        validateDestination(input, csvFile);
        validateDestination(input, featureFile);
        Files.createDirectories(output);
        writeResult(csvFile, csv);
        writeResult(featureFile, feature);
        System.out.println("\nTABLA DE FLUJOS\n" + table.toText(graph.getProcess(), paths));
        long complete = paths.stream().filter(ProcessPath::isComplete).count();
        System.out.println("Caminos completos: " + complete);
        System.out.println("Rutas truncadas: " + (paths.size() - complete));
        System.out.println("Límite de retornos por camino: " + maxReturns);
        System.out.println("Escenarios Gherkin generados: " + complete);
        System.out.println("Los escenarios requieren definiciones de pasos para ejecutarse con Cucumber.");
        System.out.println("Archivos generados:\n" + csvFile + "\n" + featureFile);
    }

    private static void validateDestination(Path input, Path destination) throws IOException {
        if (input.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())
                || (Files.exists(destination) && Files.isSameFile(input, destination))) {
            throw new IOException("La salida no puede sobrescribir el BPMN de entrada: " + destination);
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination)) {
            throw new IOException("La salida no es un archivo regular: " + destination);
        }
    }

    private static void writeResult(Path destination, String content) throws IOException {
        Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), "result-", ".tmp");
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void printResults(Path file, BpmnProcess process) {
        Collection<BpmnNode> nodes = process.getNodes().values();
        System.out.println("====================================");
        System.out.println("BPMN FLOW ANALYZER");
        System.out.println("====================================\n");
        System.out.println("Archivo: " + file.getFileName());
        System.out.println("Proceso: " + (process.getName().isBlank() ? process.getId() : process.getName()));
        System.out.println("ID del proceso: " + process.getId());
        System.out.println();
        System.out.println("Tareas encontradas: "
                + nodes.stream().filter(node -> node.getType().isTask()).count());
        System.out.println("Gateways encontrados: "
                + nodes.stream().filter(node -> node.getType().isGateway()).count());
        System.out.println("Eventos iniciales: " + countNodes(nodes, NodeType.START_EVENT));
        System.out.println("Eventos finales: " + countNodes(nodes, NodeType.END_EVENT));
        System.out.println("Sequence Flows: " + process.getSequenceFlows().size());
        System.out.println("\nElementos identificados correctamente.\n");

        System.out.println("NODOS (ID | nombre | tipo)");
        for (BpmnNode node : nodes) {
            System.out.printf("%s | %s | %s%n", node.getId(), node.getName(), node.getType());
        }
        System.out.println("\nCONEXIONES (ID | sourceRef -> targetRef)");
        for (SequenceFlow flow : process.getSequenceFlows()) {
            System.out.printf("%s | %s -> %s%n", flow.getId(), flow.getSourceRef(), flow.getTargetRef());
            if (!flow.getName().isEmpty()) {
                System.out.println("  Nombre: " + flow.getName());
            }
            if (!flow.getCondition().isEmpty()) {
                System.out.println("  Condición: " + flow.getCondition());
            }
        }
        System.out.println("====================================");
    }

    private static long countNodes(Collection<BpmnNode> nodes, NodeType type) {
        return nodes.stream().filter(node -> node.getType() == type).count();
    }

    private static void printGraph(ProcessGraph graph) {
        System.out.println("\n====================================");
        System.out.println("PROCESS GRAPH");
        System.out.println("====================================\n");
        System.out.println("Nodos: " + graph.getProcess().getNodes().size());
        System.out.println("Conexiones: " + graph.getProcess().getSequenceFlows().size());
        System.out.println("Eventos iniciales: " + graph.getStartEvents().size());
        System.out.println("Eventos finales: " + graph.getEndEvents().size());

        for (BpmnNode node : graph.getProcess().getNodes().values()) {
            List<SequenceFlow> incoming = graph.getIncomingFlows(node.getId());
            List<SequenceFlow> outgoing = graph.getOutgoingFlows(node.getId());
            System.out.println("\nNodo: " + node.getId());
            System.out.println("Entradas: " + incoming.size() + " "
                    + incoming.stream().map(SequenceFlow::getId).toList());
            System.out.println("Salidas: " + outgoing.size() + " "
                    + outgoing.stream().map(SequenceFlow::getId).toList());
        }
        System.out.println("\nGrafo construido correctamente.");
        System.out.println("====================================");
    }
}
