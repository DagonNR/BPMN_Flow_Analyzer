package com.pia.vvs.output;

import com.pia.vvs.analysis.ApplicationProcessRules;
import com.pia.vvs.analysis.ProcessPath;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class GherkinGenerator {
    public String generate(BpmnProcess process, List<ProcessPath> paths) {
        return generate(process, paths, TaskClassification.automatic(process));
    }

    public String generate(BpmnProcess process, List<ProcessPath> paths, TaskClassification classification) {
        boolean application = ApplicationProcessRules.PROCESS_ID.equals(process.getId());
        if (application) {
            new ApplicationProcessRules().validate(new ProcessGraph(process));
        } else if (!classification.getUnclassifiedIds().isEmpty()) {
            throw new IllegalArgumentException("Tareas genéricas sin clasificar para Gherkin: " + classification.getUnclassifiedIds());
        }
        boolean applicationTemplate = application && classification.getHumanIds().equals(Set.of("_3"));
        String name = displayName(process.getName(), process.getId());
        StringBuilder feature = new StringBuilder("# language: es\nCaracterística: " + name + "\n");
        if (!applicationTemplate) {
            feature.append("  # Candidatos de caminos completos; condiciones y resultados requieren validación funcional.\n")
                    .append("  # La lista de actividades conserva ocurrencias, no impone un orden entre ramas concurrentes.\n");
        }
        Map<ScenarioKey, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < paths.size(); i++) {
            ProcessPath path = paths.get(i);
            if (!path.isComplete()) {
                continue;
            }
            ScenarioKey key = new ScenarioKey(path.getNodes().stream().filter(classification::isHuman)
                    .map(BpmnNode::getId).toList(), conditions(path), eventIds(path, NodeType.START_EVENT),
                    eventIds(path, NodeType.END_EVENT));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
        }
        for (List<Integer> origins : groups.values()) {
            ProcessPath path = paths.get(origins.get(0));
            feature.append("\n  # Flujo: ").append(origins.stream().map(i -> "F" + (i + 1))
                    .collect(Collectors.joining(", "))).append('\n');
            String end = path.getNodes().get(path.getNodes().size() - 1).getId();
            feature.append("  # Fin: ").append(displayName(end, "")).append('\n');
            for (int origin : origins) {
                ProcessPath source = paths.get(origin);
                feature.append("  # SequenceFlow").append(origin == origins.get(0) ? "" : " F" + (origin + 1))
                        .append(": ").append(source.getFlows().stream().map(SequenceFlow::getId)
                                .map(id -> displayName(id, "")).collect(Collectors.joining(", ")));
                feature.append('\n');
                if (!applicationTemplate) {
                    feature.append("  # F").append(origin + 1).append(" tareas no seleccionadas: ")
                            .append(source.getNodes().stream().filter(node -> node.getType().isTask() && !classification.isHuman(node))
                                    .map(BpmnNode::getId).map(id -> displayName(id, "")).collect(Collectors.joining(", "))).append('\n');
                    for (List<SequenceFlow> concurrent : source.getConcurrentBranches()) {
                        feature.append("  # F").append(origin + 1).append(" salidas concurrentes: ")
                                .append(concurrent.stream().map(SequenceFlow::getId).map(id -> displayName(id, ""))
                                        .collect(Collectors.joining(", "))).append('\n');
                    }
                }
            }
            if (applicationTemplate) {
                appendApplication(feature, process, path, end);
            } else {
                appendGeneric(feature, path, classification, origins.get(0) + 1);
            }
        }
        return feature.toString();
    }

    private void appendApplication(StringBuilder feature, BpmnProcess process, ProcessPath path, String end) {
        feature.append("  Escenario: ").append(outcome(end)).append(" con ")
                .append(path.getReturnCount()).append(path.getReturnCount() == 1 ? " retorno\n" : " retornos\n");
        if (end.equals("_2")) {
            feature.append("    Dado que la validación inicial de la solicitud falla\n")
                    .append("    Cuando el sistema procesa la solicitud\n")
                    .append("    Entonces el proceso termina por rechazo en la validación inicial\n");
        } else {
            BpmnNode review = process.getNodes().get("_3");
            appendReview(feature, path, displayName(review.getName(), review.getId()));
        }
    }

    private void appendGeneric(StringBuilder feature, ProcessPath path, TaskClassification classification, int number) {
        feature.append("  Escenario: Recorrido funcional F").append(number).append('\n');
        feature.append("    Dado que el proceso comienza en los eventos iniciales del recorrido\n");
        appendNodes(feature, path.getNodes().stream().filter(node -> node.getType() == NodeType.START_EVENT).toList());
        for (String condition : conditions(path)) {
            feature.append("    Y que se considera ").append(displayName(condition, "")).append('\n');
        }
        List<BpmnNode> humans = path.getNodes().stream().filter(classification::isHuman).toList();
        if (humans.isEmpty()) {
            feature.append("    Cuando el sistema procesa el recorrido sin acciones humanas seleccionadas\n");
        } else {
            feature.append("    Cuando se realizan estas actividades humanas respetando las dependencias del BPMN\n");
            appendNodes(feature, humans);
        }
        feature.append("    Entonces se alcanzan estos eventos finales del proceso\n");
        appendNodes(feature, path.getNodes().stream().filter(node -> node.getType() == NodeType.END_EVENT).toList());
    }

    private void appendNodes(StringBuilder feature, List<BpmnNode> nodes) {
        feature.append("      | Actividad o evento | ID |\n");
        for (BpmnNode node : nodes) {
            feature.append("      | ").append(tableCell(displayName(node.getName(), node.getId())))
                    .append(" | ").append(tableCell(displayName(node.getId(), ""))).append(" |\n");
        }
    }

    private String tableCell(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }

    private List<String> conditions(ProcessPath path) {
        List<String> conditions = new ArrayList<>();
        for (SequenceFlow flow : path.getFlows()) {
            if (!flow.getName().isBlank()) {
                conditions.add("la alternativa BPMN: " + flow.getName());
            }
            if (!flow.getCondition().isBlank()) {
                conditions.add("la condición BPMN (sin evaluar): " + flow.getCondition());
            }
        }
        return conditions;
    }

    private List<String> eventIds(ProcessPath path, NodeType type) {
        return path.getNodes().stream().filter(node -> node.getType() == type).map(BpmnNode::getId).toList();
    }

    // IDs y ocurrencias, no solo nombres ni conjuntos: conserva resultados y repeticiones.
    private record ScenarioKey(List<String> humans, List<String> conditions, List<String> starts, List<String> ends) { }

    private void appendReview(StringBuilder feature, ProcessPath path, String reviewName) {
        feature.append("    Dado que la solicitud supera la validación inicial\n")
                .append("    Y que se completan las verificaciones DMF y OIG\n")
                .append("    Y que la solicitud está pendiente de revisión manual\n");
        for (SequenceFlow flow : path.getFlows()) {
            switch (flow.getId()) {
                case "_17-_18" -> feature.append("    Cuando el revisor rechaza la solicitud en ")
                        .append(reviewName).append('\n')
                        .append("    Entonces el proceso termina por rechazo de la revisión manual\n");
                case "_17-_31" -> feature.append("    Cuando el revisor permite continuar la solicitud en ")
                        .append(reviewName).append('\n');
                case "_32-_33" -> feature.append("    Entonces la reevaluación automática no es satisfactoria\n")
                        .append("    Y la solicitud vuelve a revisión manual\n");
                case "_32-_21" -> feature.append("    Entonces la reevaluación automática es satisfactoria\n")
                        .append("    Y el proceso termina por aceptación\n");
                default -> { }
            }
        }
    }

    private String outcome(String end) {
        return switch (end) {
            case "_2" -> "Rechazo en la validación inicial";
            case "_19" -> "Rechazo en la revisión manual";
            case "_22" -> "Aceptación de la solicitud";
            default -> throw new IllegalArgumentException("Evento final no soportado: " + end);
        };
    }

    private String displayName(String value, String id) {
        String name = value.replaceAll("[\\p{javaWhitespace}\\p{Z}]+", " ").strip();
        return name.isEmpty() ? id : name;
    }
}
