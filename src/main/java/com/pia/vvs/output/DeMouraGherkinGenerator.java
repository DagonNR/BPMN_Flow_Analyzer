package com.pia.vvs.output;

import com.pia.vvs.analysis.BranchFlow;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.pia.vvs.output.GherkinGenerator.displayName;

/** Vista de las actividades humanas presentes en las filas por ramas de la tabla de flujos. */
public class DeMouraGherkinGenerator {
    private record Sequence(List<String> humans, List<String> ends, List<String> decisions) { }

    public String generate(ProcessGraph graph, List<BranchFlow> branches, TaskClassification classification) {
        // Publicar todas las alternativas de una rama AND antes de pasar a la siguiente.
        Map<List<String>, Map<Sequence, List<Integer>>> groups = new LinkedHashMap<>();
        for (int i = 0; i < branches.size(); i++) {
            BranchFlow branch = branches.get(i);
            var parallel = branch.getSelectedParallelFlows().stream().map(SequenceFlow::getId).toList();
            var humans = branch.getNodes().stream().filter(classification::isHuman).map(BpmnNode::getId).toList();
            var ends = branch.getNodes().stream().filter(n -> n.getType() == NodeType.END_EVENT)
                    .map(BpmnNode::getId).sorted().toList();
            var decisions = branch.getFlows().stream().filter(f -> distinguishesAlternative(graph, f))
                    .map(SequenceFlow::getId).toList();
            groups.computeIfAbsent(parallel, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(new Sequence(humans, ends, decisions), ignored -> new ArrayList<>()).add(i);
        }
        var process = graph.getProcess();
        StringBuilder feature = new StringBuilder("# language: es\nCaracterística: Tareas humanas por rama de ")
                .append(displayName(process.getName(), displayName(process.getId(), ""))).append('\n')
                .append("  # BRANCH_VIEW: cada escenario es un fragmento. Sus tareas pueden necesitar sincronizarse con otras ramas.\n")
                .append("  # Los finales pertenecen al camino conjunto de origen.\n");
        int number = 0;
        for (var alternatives : groups.values()) {
            for (var group : alternatives.entrySet()) {
                feature.append('\n');
                for (int index : group.getValue()) {
                    BranchFlow branch = branches.get(index);
                    feature.append("  # B").append(index + 1).append(" <- ")
                            .append(branch.getSourcePathNumbers().stream().map(f -> "F" + f)
                                    .collect(Collectors.joining(", "))).append('\n');
                    feature.append("  # Flujos: ").append(branch.getFlows().stream()
                            .map(this::describeFlow).collect(Collectors.joining(", "))).append('\n');
                }
                feature.append("  Escenario: Proyección humana ").append(number++).append('\n');
                Sequence sequence = group.getKey();
                if (sequence.humans().isEmpty()) {
                    feature.append("    Dado que la proyección carece de tareas humanas seleccionadas\n");
                }
                for (int i = 0; i < sequence.humans().size(); i++) {
                    feature.append(i == 0 ? "    Dado que la primera tarea humana de la rama es "
                            : "    Cuando se identifica en la rama la tarea humana ");
                    feature.append(label(graph.getNode(sequence.humans().get(i)))).append('\n');
                }
                feature.append("    Entonces el camino de origen registra estos finales: ")
                        .append(sequence.ends().stream().map(graph::getNode).map(this::label)
                                .collect(Collectors.joining(", "))).append('\n');
            }
        }
        return feature.toString();
    }

    private String label(BpmnNode node) {
        String id = displayName(node.getId(), "");
        return displayName(node.getName(), id) + " [" + id + "]";
    }

    private boolean distinguishesAlternative(ProcessGraph graph, SequenceFlow flow) {
        // Una etiqueta en una bifurcación OR describe una selección. Su expresión, si existe, se conserva como distinción.
        if (!flow.getCondition().isBlank()) return true;
        if (flow.getName().isBlank()) return false;
        return graph.getSourceNode(flow).getType() != NodeType.INCLUSIVE_GATEWAY
                || graph.getOutgoingFlows(flow.getSourceRef()).size() == 1;
    }

    private String describeFlow(SequenceFlow flow) {
        String text = displayName(flow.getId(), "");
        if (!flow.getName().isBlank()) text += " (" + displayName(flow.getName(), "") + ")";
        if (!flow.getCondition().isBlank()) text += " {" + displayName(flow.getCondition(), "") + "}";
        return text;
    }
}
