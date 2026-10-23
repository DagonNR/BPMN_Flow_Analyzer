package com.pia.vvs.output;

import com.pia.vvs.analysis.ProcessPath;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FlowTable {
    public String toCsv(BpmnProcess process, List<ProcessPath> paths) {
        StringBuilder text = new StringBuilder();
        for (List<String> row : rows(process, paths)) {
            text.append(row.stream().map(this::csvCell).collect(Collectors.joining(","))).append('\n');
        }
        return text.toString();
    }

    public String toText(BpmnProcess process, List<ProcessPath> paths) {
        StringBuilder text = new StringBuilder();
        for (List<String> row : rows(process, paths)) {
            text.append(row.stream().map(value -> value.replaceAll("[\\p{javaWhitespace}\\p{Z}]+", " "))
                    .collect(Collectors.joining(" | "))).append('\n');
        }
        return text.toString();
    }

    private List<List<String>> rows(BpmnProcess process, List<ProcessPath> paths) {
        List<BpmnNode> tasks = process.getNodes().values().stream().filter(node -> node.getType().isTask()).toList();
        List<List<String>> rows = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of("Flujo", "Estado", "Retornos", "Evento final", "Flujo bloqueado"));
        for (BpmnNode task : tasks) {
            header.add(task.getName() + " [" + task.getId() + "]");
        }
        rows.add(header);
        for (int i = 0; i < paths.size(); i++) {
            ProcessPath path = paths.get(i);
            Set<String> visited = new HashSet<>();
            for (BpmnNode node : path.getNodes()) {
                visited.add(node.getId());
            }
            String end = path.isComplete() ? path.getNodes().get(path.getNodes().size() - 1).getId() : "";
            String blocked = path.isComplete() ? "" : path.getBlockedFlow().getId();
            List<String> row = new ArrayList<>(List.of("F" + (i + 1), path.getStatus().name(),
                    Integer.toString(path.getReturnCount()), end, blocked));
            for (BpmnNode task : tasks) {
                row.add(visited.contains(task.getId()) ? "X" : "-");
            }
            rows.add(row);
        }
        return rows;
    }

    private String csvCell(String value) {
        String trimmed = value.stripLeading();
        if (trimmed.length() > 1 && "=+-@".indexOf(trimmed.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
