package com.pia.vvs.graph;

import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ProcessGraph {
    private final BpmnProcess process;
    private final Map<String, List<SequenceFlow>> outgoingFlows = new HashMap<>();
    private final Map<String, List<SequenceFlow>> incomingFlows = new HashMap<>();

    public ProcessGraph(BpmnProcess process) {
        this.process = Objects.requireNonNull(process, "El proceso BPMN es obligatorio.");
        for (String nodeId : process.getNodes().keySet()) {
            outgoingFlows.put(nodeId, new ArrayList<>());
            incomingFlows.put(nodeId, new ArrayList<>());
        }

        Set<String> flowIds = new HashSet<>();
        for (SequenceFlow flow : process.getSequenceFlows()) {
            if (!flowIds.add(flow.getId())) {
                throw new IllegalArgumentException("ID de SequenceFlow duplicado: " + flow.getId());
            }
            BpmnNode source = getSourceNode(flow);
            BpmnNode target = getTargetNode(flow);
            outgoingFlows.get(source.getId()).add(flow);
            incomingFlows.get(target.getId()).add(flow);
        }

        // Las consultas conservan el orden del proceso y no permiten alterar el grafo.
        outgoingFlows.replaceAll((nodeId, flows) -> List.copyOf(flows));
        incomingFlows.replaceAll((nodeId, flows) -> List.copyOf(flows));
    }

    public BpmnProcess getProcess() {
        return process;
    }

    /** Rechaza IDs nulos, vacíos o inexistentes con IllegalArgumentException. */
    public BpmnNode getNode(String nodeId) {
        BpmnNode node = process.getNodes().get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("No existe un nodo con ID: " + nodeId);
        }
        return node;
    }

    public List<SequenceFlow> getOutgoingFlows(String nodeId) {
        getNode(nodeId);
        return outgoingFlows.get(nodeId);
    }

    public List<SequenceFlow> getIncomingFlows(String nodeId) {
        getNode(nodeId);
        return incomingFlows.get(nodeId);
    }

    public BpmnNode getSourceNode(SequenceFlow flow) {
        Objects.requireNonNull(flow, "El SequenceFlow es obligatorio.");
        return getNode(flow.getSourceRef());
    }

    public BpmnNode getTargetNode(SequenceFlow flow) {
        Objects.requireNonNull(flow, "El SequenceFlow es obligatorio.");
        return getNode(flow.getTargetRef());
    }

    public List<BpmnNode> getStartEvents() {
        return process.getNodes().values().stream()
                .filter(node -> node.getType() == NodeType.START_EVENT).toList();
    }

    public List<BpmnNode> getEndEvents() {
        return process.getNodes().values().stream()
                .filter(node -> node.getType() == NodeType.END_EVENT).toList();
    }
}
