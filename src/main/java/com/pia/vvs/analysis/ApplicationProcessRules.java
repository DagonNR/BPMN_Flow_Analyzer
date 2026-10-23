package com.pia.vvs.analysis;

import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;

import java.util.List;
import java.util.Map;

public class ApplicationProcessRules {
    public static final String PROCESS_ID = "gov.medicaid.process.application";

    public void validate(ProcessGraph graph) {
        if (!graph.getProcess().getUnsupportedBehaviors().isEmpty()) {
            throw unsupported(String.join(", ", graph.getProcess().getUnsupportedBehaviors()));
        }
        Map<String, NodeType> expectedNodes = Map.ofEntries(
                Map.entry("_1", NodeType.START_EVENT), Map.entry("_2", NodeType.END_EVENT),
                Map.entry("_19", NodeType.END_EVENT), Map.entry("_22", NodeType.END_EVENT),
                Map.entry("_3", NodeType.USER_TASK), Map.entry("_35", NodeType.PARALLEL_GATEWAY),
                Map.entry("_7", NodeType.INCLUSIVE_GATEWAY), Map.entry("_17", NodeType.EXCLUSIVE_GATEWAY),
                Map.entry("_32", NodeType.EXCLUSIVE_GATEWAY), Map.entry("_33", NodeType.EXCLUSIVE_GATEWAY),
                Map.entry("_5", NodeType.TASK), Map.entry("_6", NodeType.TASK),
                Map.entry("_9", NodeType.TASK), Map.entry("_10", NodeType.TASK),
                Map.entry("_15", NodeType.TASK), Map.entry("_18", NodeType.TASK),
                Map.entry("_21", NodeType.TASK), Map.entry("_31", NodeType.TASK),
                Map.entry("_34", NodeType.TASK));
        List<String> expectedFlows = List.of("_9-_2", "_7-_34", "_33-_3", "_10-_35", "_34-_35",
                "_1-_5", "_5-_6", "_6-_7", "_7-_9", "_7-_10", "_35-_15", "_3-_17",
                "_17-_18", "_18-_19", "_32-_21", "_21-_22", "_17-_31", "_31-_32", "_15-_33", "_32-_33");

        if (!PROCESS_ID.equals(graph.getProcess().getId())
                || !graph.getProcess().getNodes().keySet().equals(expectedNodes.keySet())
                || graph.getProcess().getSequenceFlows().size() != expectedFlows.size()) {
            throw unsupported("estructura del proceso");
        }
        for (BpmnNode node : graph.getProcess().getNodes().values()) {
            if (node.getType() != expectedNodes.get(node.getId())) {
                throw unsupported("tipo de nodo " + node.getId());
            }
        }
        for (SequenceFlow flow : graph.getProcess().getSequenceFlows()) {
            String endpoints = flow.getSourceRef() + "-" + flow.getTargetRef();
            if (!expectedFlows.contains(flow.getId()) || !flow.getId().equals(endpoints)
                    || !expectedCondition(flow.getId()).equals(flow.getCondition().strip())) {
                throw unsupported("conexión o condición " + flow.getId());
            }
        }
    }

    public List<List<SequenceFlow>> getAlternatives(ProcessGraph graph, BpmnNode node) {
        List<SequenceFlow> outgoing = graph.getOutgoingFlows(node.getId());
        if (node.getType() == NodeType.INCLUSIVE_GATEWAY) {
            if (!PROCESS_ID.equals(graph.getProcess().getId()) || !node.getId().equals("_7")) {
                throw unsupported("compuerta inclusiva " + node.getId());
            }
            return List.of(outgoing.stream().filter(flow -> !flow.getId().equals("_7-_9")).toList(),
                    outgoing.stream().filter(flow -> flow.getId().equals("_7-_9")).toList());
        }
        if (node.getType() == NodeType.EXCLUSIVE_GATEWAY) {
            return outgoing.stream().map(List::of).toList();
        }
        return List.of(outgoing);
    }

    private String expectedCondition(String flowId) {
        String validation = "model.getProcessResults().getValidationResult().getStatus().getStatusCode()";
        String screening = "model.getProcessResults().getScreeningResults()"
                + ".getAutomaticScreeningStatus().getStatus().getStatusCode()";
        return switch (flowId) {
            case "_7-_34", "_7-_10" -> "return \"SUCCESS\".equals(" + validation + ");";
            case "_7-_9" -> "return \"FAILURE\".equals(" + validation + ");";
            case "_17-_18" -> "return \"REJECTED\".equals(model.getApplicationStatus().getStatus());";
            case "_17-_31" -> "return !\"REJECTED\".equals(model.getApplicationStatus().getStatus());";
            case "_32-_21" -> "return \"SUCCESS\".equals(" + screening + ");";
            case "_32-_33" -> "return !\"SUCCESS\".equals(" + screening + ");";
            default -> "";
        };
    }

    private IllegalArgumentException unsupported(String detail) {
        return new IllegalArgumentException("Generación no soportada: " + detail
                + ". Se requieren las reglas verificadas de ApplicationProcess.");
    }
}
