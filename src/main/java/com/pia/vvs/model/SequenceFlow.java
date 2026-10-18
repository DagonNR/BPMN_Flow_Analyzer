package com.pia.vvs.model;

import java.util.Objects;

public class SequenceFlow {
    private final String id;
    private final String sourceRef;
    private final String targetRef;
    private final String name;
    private final String condition;

    public SequenceFlow(String id, String sourceRef, String targetRef,
                        String name, String condition) {
        if (id == null || id.isBlank() || sourceRef == null || sourceRef.isBlank()
                || targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("El flujo debe tener ID, sourceRef y targetRef.");
        }
        this.id = id;
        this.sourceRef = sourceRef;
        this.targetRef = targetRef;
        this.name = Objects.requireNonNullElse(name, "");
        this.condition = Objects.requireNonNullElse(condition, "");
    }

    public String getId() {
        return id;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getTargetRef() {
        return targetRef;
    }

    public String getName() {
        return name;
    }

    public String getCondition() {
        return condition;
    }
}
