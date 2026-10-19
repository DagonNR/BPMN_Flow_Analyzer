package com.pia.vvs;

import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import com.pia.vvs.parser.BpmnParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        if ((args.length != 0 && args.length != 2)
                || (args.length == 2 && (args[0].isBlank() || args[1].isBlank()))) {
            System.err.println("Indica ambos argumentos (ruta e ID del proceso) o ninguno; no pueden estar vacíos.");
            System.err.println("Uso: java -cp target/classes com.pia.vvs.Main [archivo.bpmn procesoId]");
            System.exit(1);
        }
        try {
            Path file = args.length == 0 ? Path.of("bpmn", "ApplicationProcess.bpmn") : Path.of(args[0]);
            String processId = args.length == 0 ? "gov.medicaid.process.application" : args[1];
            BpmnProcess process = new BpmnParser().parse(file, processId);
            ProcessGraph graph = new ProcessGraph(process);
            printResults(file, process);
            printGraph(graph);
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("No se pudo analizar el BPMN: " + exception.getMessage());
            System.exit(1);
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
