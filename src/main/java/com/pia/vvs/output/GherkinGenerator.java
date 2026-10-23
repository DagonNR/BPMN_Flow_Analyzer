package com.pia.vvs.output;

import com.pia.vvs.analysis.ApplicationProcessRules;
import com.pia.vvs.analysis.ProcessPath;
import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.SequenceFlow;

import java.util.List;
import java.util.stream.Collectors;

public class GherkinGenerator {
    public String generate(BpmnProcess process, List<ProcessPath> paths) {
        new ApplicationProcessRules().validate(new ProcessGraph(process));
        String name = displayName(process.getName(), process.getId());
        StringBuilder feature = new StringBuilder("# language: es\nCaracterística: " + name + "\n");
        BpmnNode review = process.getNodes().get("_3");
        String reviewName = displayName(review.getName(), review.getId());
        for (int i = 0; i < paths.size(); i++) {
            ProcessPath path = paths.get(i);
            if (!path.isComplete()) {
                continue;
            }
            String end = path.getNodes().get(path.getNodes().size() - 1).getId();
            feature.append("\n  # Flujo: F").append(i + 1).append('\n');
            feature.append("  # Fin: ").append(end).append('\n');
            feature.append("  # SequenceFlow: ").append(path.getFlows().stream().map(SequenceFlow::getId)
                    .collect(Collectors.joining(", "))).append('\n');
            feature.append("  Escenario: ").append(outcome(end)).append(" con ")
                    .append(path.getReturnCount()).append(path.getReturnCount() == 1 ? " retorno\n" : " retornos\n");
            if (end.equals("_2")) {
                feature.append("    Dado que la validación inicial de la solicitud falla\n")
                        .append("    Cuando el sistema procesa la solicitud\n")
                        .append("    Entonces el proceso termina por rechazo en la validación inicial\n");
            } else {
                appendReview(feature, path, reviewName);
            }
        }
        return feature.toString();
    }

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
