package com.pia.vvs.analysis;

import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Separa regiones AND estructuradas de caminos ya analizados, sin volver a ejecutar el proceso. */
public class BranchFlowProjector {
    public List<BranchFlow> project(ProcessGraph graph, List<ProcessPath> paths) {
        Map<String, List<Branch>> regions = new HashMap<>();
        Map<Signature, BranchFlow> distinct = new LinkedHashMap<>();
        for (int i = 0; i < paths.size(); i++) {
            ProcessPath path = paths.get(i);
            if (!path.isComplete()) {
                continue;
            }
            List<BranchFlow> views = List.of(new BranchFlow(path.getNodes(), path.getFlows(), List.of(), List.of(i + 1)));
            Set<String> visitedSplits = new HashSet<>();
            for (BpmnNode node : path.getNodes()) {
                if (node.getType() != NodeType.PARALLEL_GATEWAY || graph.getOutgoingFlows(node.getId()).size() < 2) {
                    continue;
                }
                if (!visitedSplits.add(node.getId())) {
                    throw unsupported(node.getId(), "la bifurcación se visita varias veces en un mismo camino");
                }
                List<Branch> branches = regions.computeIfAbsent(node.getId(), id -> findBranches(graph, id));
                List<BranchFlow> expanded = new ArrayList<>();
                for (BranchFlow view : views) {
                    if (view.getNodes().stream().noneMatch(item -> item.getId().equals(node.getId()))) {
                        expanded.add(view); // Una proyección exterior ya excluyó esta bifurcación anidada.
                    } else {
                        for (Branch selected : branches) {
                            expanded.add(selectBranch(view, branches, selected));
                        }
                    }
                }
                views = expanded;
            }
            for (BranchFlow view : views) {
                Signature signature = new Signature(view.getNodes().stream().map(BpmnNode::getId).toList(),
                        view.getFlows().stream().map(SequenceFlow::getId).toList());
                BranchFlow previous = distinct.get(signature);
                if (previous == null) {
                    distinct.put(signature, view);
                } else {
                    List<Integer> origins = new ArrayList<>(previous.getSourcePathNumbers());
                    if (!origins.contains(i + 1)) {
                        origins.add(i + 1);
                    }
                    distinct.put(signature, new BranchFlow(previous.getNodes(), previous.getFlows(),
                            previous.getSelectedParallelFlows(), origins));
                }
            }
        }
        return List.copyOf(distinct.values());
    }

    private BranchFlow selectBranch(BranchFlow view, List<Branch> branches, Branch selected) {
        Set<String> excludedNodes = new HashSet<>();
        Set<String> excludedFlows = new HashSet<>();
        for (Branch branch : branches) {
            if (branch != selected) {
                excludedNodes.addAll(branch.nodes());
                excludedFlows.addAll(branch.flows());
            }
        }
        List<SequenceFlow> selections = new ArrayList<>(view.getSelectedParallelFlows());
        selections.add(selected.entry());
        return new BranchFlow(
                view.getNodes().stream().filter(node -> !excludedNodes.contains(node.getId())).toList(),
                view.getFlows().stream().filter(flow -> !excludedFlows.contains(flow.getId())).toList(),
                selections, view.getSourcePathNumbers());
    }

    private List<Branch> findBranches(ProcessGraph graph, String split) {
        List<Branch> result = null;
        for (BpmnNode candidate : graph.getProcess().getNodes().values()) {
            if (candidate.getType() != NodeType.PARALLEL_GATEWAY || candidate.getId().equals(split)
                    || graph.getIncomingFlows(candidate.getId()).size() < 2) {
                continue;
            }
            List<Branch> branches = new ArrayList<>();
            Set<String> allNodes = new HashSet<>();
            boolean valid = true;
            for (SequenceFlow entry : graph.getOutgoingFlows(split)) {
                Branch branch = inspectBranch(graph, entry, candidate.getId());
                if (branch == null || branch.nodes().stream().anyMatch(allNodes::contains)) {
                    valid = false;
                    break;
                }
                allNodes.addAll(branch.nodes());
                branches.add(branch);
            }
            if (valid) {
                if (result != null) {
                    throw unsupported(split, "existen varias convergencias candidatas");
                }
                result = List.copyOf(branches);
            }
        }
        if (result == null) {
            throw unsupported(split, "no hay una convergencia AND común con ramas separadas y sin entradas externas");
        }
        return result;
    }

    private Branch inspectBranch(ProcessGraph graph, SequenceFlow entry, String join) {
        Set<String> nodes = new HashSet<>();
        Set<String> flows = new HashSet<>();
        flows.add(entry.getId());
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(entry.getTargetRef());
        boolean reachesJoin = false;
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (id.equals(join)) {
                reachesJoin = true;
                continue; // La convergencia y su continuación permanecen en todas las vistas.
            }
            if (id.equals(entry.getSourceRef()) || graph.getOutgoingFlows(id).isEmpty()) {
                return null; // Retorno a la bifurcación o salida del proceso antes de converger.
            }
            if (!nodes.add(id)) {
                continue;
            }
            for (SequenceFlow flow : graph.getOutgoingFlows(id)) {
                flows.add(flow.getId());
                pending.addLast(flow.getTargetRef());
            }
        }
        if (!reachesJoin) {
            return null;
        }
        for (String id : nodes) {
            for (SequenceFlow incoming : graph.getIncomingFlows(id)) {
                if (!incoming.getId().equals(entry.getId()) && !nodes.contains(incoming.getSourceRef())) {
                    return null;
                }
            }
        }
        return new Branch(entry, Set.copyOf(nodes), Set.copyOf(flows));
    }

    private IllegalArgumentException unsupported(String split, String detail) {
        return new IllegalArgumentException("Vista por ramas no soportada en " + split + ": " + detail + ".");
    }

    private record Branch(SequenceFlow entry, Set<String> nodes, Set<String> flows) { }
    private record Signature(List<String> nodes, List<String> flows) { }
}
