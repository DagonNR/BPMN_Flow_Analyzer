package com.pia.vvs;

import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import com.pia.vvs.parser.BpmnParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

public class Main {
    public static void main(String[] args) {
        Path file = Path.of("bpmn", "ApplicationProcess.bpmn");
        try {
            BpmnProcess process = new BpmnParser().parse(file, "gov.medicaid.process.application");
            printResults(file, process);
        } catch (IOException exception) {
            System.err.println("No se pudo leer el proceso BPMN: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void printResults(Path file, BpmnProcess process) {
        Collection<BpmnNode> nodes = process.getNodes().values();
        System.out.println("====================================");
        System.out.println("BPMN FLOW ANALYZER");
        System.out.println("====================================\n");
        System.out.println("Archivo: " + file.getFileName());
        System.out.println("Proceso: " + process.getName());
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
}
