package com.pia.vvs.model;

public enum NodeType {
    TASK("task"),
    USER_TASK("userTask"),
    SERVICE_TASK("serviceTask"),
    SCRIPT_TASK("scriptTask"),
    MANUAL_TASK("manualTask"),
    BUSINESS_RULE_TASK("businessRuleTask"),
    SEND_TASK("sendTask"),
    RECEIVE_TASK("receiveTask"),
    START_EVENT("startEvent"),
    END_EVENT("endEvent"),
    INTERMEDIATE_CATCH_EVENT("intermediateCatchEvent"),
    INTERMEDIATE_THROW_EVENT("intermediateThrowEvent"),
    BOUNDARY_EVENT("boundaryEvent"),
    EXCLUSIVE_GATEWAY("exclusiveGateway"),
    PARALLEL_GATEWAY("parallelGateway"),
    INCLUSIVE_GATEWAY("inclusiveGateway"),
    EVENT_BASED_GATEWAY("eventBasedGateway"),
    COMPLEX_GATEWAY("complexGateway"),
    SUB_PROCESS("subProcess"),
    CALL_ACTIVITY("callActivity");

    private final String xmlName;

    NodeType(String xmlName) {
        this.xmlName = xmlName;
    }

    /** Devuelve null cuando la etiqueta no representa un tipo soportado. */
    public static NodeType fromXmlName(String xmlName) {
        for (NodeType type : values()) {
            if (type.xmlName.equals(xmlName)) {
                return type;
            }
        }
        return null;
    }

    public boolean isTask() {
        return switch (this) {
            case TASK, USER_TASK, SERVICE_TASK, SCRIPT_TASK, MANUAL_TASK,
                    BUSINESS_RULE_TASK, SEND_TASK, RECEIVE_TASK -> true;
            default -> false;
        };
    }

    public boolean isGateway() {
        return switch (this) {
            case EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY, INCLUSIVE_GATEWAY,
                    EVENT_BASED_GATEWAY, COMPLEX_GATEWAY -> true;
            default -> false;
        };
    }
}
