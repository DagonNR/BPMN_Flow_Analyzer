package com.pia.vvs.model;

import java.util.Objects;

public class BpmnNode {
    private final String id;
    private final String name;
    private final NodeType type;

    public BpmnNode(String id, String name, NodeType type) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("El nodo debe tener un ID.");
        }
        this.id = id;
        this.name = Objects.requireNonNullElse(name, "");
        this.type = Objects.requireNonNull(type, "El nodo debe tener un tipo.");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public NodeType getType() {
        return type;
    }
}
