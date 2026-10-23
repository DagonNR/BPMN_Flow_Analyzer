package com.pia.vvs.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class BpmnProcess {
    private final String id;
    private final String name;
    private final Map<String, BpmnNode> nodes;
    private final List<SequenceFlow> sequenceFlows;
    private final List<String> unsupportedBehaviors;

    public BpmnProcess(String id, String name, List<BpmnNode> nodes,
                       List<SequenceFlow> sequenceFlows) {
        this(id, name, nodes, sequenceFlows, List.of());
    }

    public BpmnProcess(String id, String name, List<BpmnNode> nodes,
                       List<SequenceFlow> sequenceFlows, List<String> unsupportedBehaviors) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("El proceso debe tener un ID.");
        }
        this.id = id;
        this.name = Objects.requireNonNullElse(name, "");
        Map<String, BpmnNode> nodesById = new LinkedHashMap<>();
        for (BpmnNode node : nodes) {
            if (nodesById.putIfAbsent(node.getId(), node) != null) {
                throw new IllegalArgumentException("ID de nodo duplicado: " + node.getId());
            }
        }
        this.nodes = Collections.unmodifiableMap(nodesById);
        this.sequenceFlows = List.copyOf(sequenceFlows);
        this.unsupportedBehaviors = List.copyOf(unsupportedBehaviors);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Map<String, BpmnNode> getNodes() {
        return nodes;
    }

    public List<SequenceFlow> getSequenceFlows() {
        return sequenceFlows;
    }

    public List<String> getUnsupportedBehaviors() {
        return unsupportedBehaviors;
    }
}
