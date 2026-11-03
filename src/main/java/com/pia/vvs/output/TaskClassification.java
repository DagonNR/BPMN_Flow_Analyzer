package com.pia.vvs.output;

import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Selección funcional por tipo BPMN, complementada por IDs de tareas genéricas. */
public class TaskClassification {
    private final Set<String> humanIds;
    private final List<String> unclassifiedIds;

    private TaskClassification(BpmnProcess process, Properties overrides) {
        Set<String> selected = new HashSet<>();
        for (String id : overrides.stringPropertyNames()) {
            if (id.equals("processId")) {
                continue;
            }
            BpmnNode node = process.getNodes().get(id);
            String value = overrides.getProperty(id).strip();
            if (node == null || !node.getType().isTask()) {
                throw new IllegalArgumentException("La clasificación contiene un ID de tarea inexistente: " + id);
            }
            if (!value.equals("human") && !value.equals("excluded")) {
                throw new IllegalArgumentException("Clasificación inválida para " + id + ": use human o excluded.");
            }
            boolean explicit = isExplicitHuman(node);
            if ((explicit && value.equals("excluded"))
                    || (value.equals("human") && !explicit && node.getType() != NodeType.TASK)) {
                throw new IllegalArgumentException("Clasificación contradictoria con el tipo BPMN: " + id);
            }
            if (value.equals("human")) {
                selected.add(id);
            }
        }
        process.getNodes().values().stream().filter(TaskClassification::isExplicitHuman)
                .map(BpmnNode::getId).forEach(selected::add);
        humanIds = Set.copyOf(selected);
        unclassifiedIds = process.getNodes().values().stream()
                .filter(node -> node.getType() == NodeType.TASK && !overrides.containsKey(node.getId()))
                .map(BpmnNode::getId).toList();
    }

    public static TaskClassification automatic(BpmnProcess process) {
        return new TaskClassification(process, new Properties());
    }

    /** Properties UTF-8: processId obligatorio y una clasificación por ID, sin duplicados. */
    public static TaskClassification load(BpmnProcess process, Path file) throws IOException {
        Properties properties = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("Clave de clasificación duplicada: " + key);
                }
                return super.put(key, value);
            }
        };
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        if (!process.getId().equals(properties.getProperty("processId"))) {
            throw new IllegalArgumentException("El processId de la clasificación no coincide con " + process.getId());
        }
        return new TaskClassification(process, properties);
    }

    private static boolean isExplicitHuman(BpmnNode node) {
        return node.getType() == NodeType.USER_TASK || node.getType() == NodeType.MANUAL_TASK;
    }

    public boolean isHuman(BpmnNode node) { return humanIds.contains(node.getId()); }
    public Set<String> getHumanIds() { return humanIds; }
    public List<String> getUnclassifiedIds() { return unclassifiedIds; }
}
