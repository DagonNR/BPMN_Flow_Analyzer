package com.pia.vvs.analysis;

import com.pia.vvs.graph.ProcessGraph;
import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explora alternativas estructurales; no evalúa condiciones ni permuta tareas concurrentes. */
public class PathAnalyzer {
    private final ApplicationProcessRules rules = new ApplicationProcessRules();

    public List<ProcessPath> analyze(ProcessGraph graph, int maxReturns) {
        if (maxReturns < 0 || maxReturns > 100) {
            throw new IllegalArgumentException("El límite de retornos debe estar entre 0 y 100.");
        }
        validate(graph);
        Set<String> backEdges = new HashSet<>();
        findBackEdges(graph, graph.getStartEvents().get(0), new HashSet<>(), new HashSet<>(), backEdges);
        List<ProcessPath> paths = new ArrayList<>();
        State state = new State();
        BpmnNode start = graph.getStartEvents().get(0);
        state.nodes.add(start);
        activate(graph.getOutgoingFlows(start.getId()), state, backEdges, maxReturns);
        walk(graph, state, backEdges, maxReturns, paths);
        return List.copyOf(paths);
    }

    private void walk(ProcessGraph graph, State state, Set<String> backEdges,
                      int maxReturns, List<ProcessPath> paths) {
        while (true) {
            BpmnNode node = nextNode(graph, state);
            if (node == null) {
                if (!state.pending.isEmpty()) {
                    throw new IllegalArgumentException("No se pudo completar la sincronización de las ramas.");
                }
                if (state.nodes.get(state.nodes.size() - 1).getType() != NodeType.END_EVENT) {
                    throw new IllegalArgumentException("El recorrido no alcanza un evento final.");
                }
                paths.add(state.result(null));
                return;
            }
            consume(graph, node, state);
            state.nodes.add(node);
            if (node.getType() == NodeType.END_EVENT) {
                continue;
            }
            List<List<SequenceFlow>> alternatives = alternatives(graph, node);
            if (alternatives.size() == 1) {
                SequenceFlow blocked = activate(alternatives.get(0), state, backEdges, maxReturns);
                if (blocked != null) {
                    paths.add(state.result(blocked));
                    return;
                }
                continue;
            }
            Set<String> blockedAlternatives = new HashSet<>();
            for (List<SequenceFlow> alternative : alternatives) {
                State branch = new State(state);
                SequenceFlow blocked = activate(alternative, branch, backEdges, maxReturns);
                if (blocked == null) {
                    walk(graph, branch, backEdges, maxReturns, paths);
                } else if (blockedAlternatives.add(blocked.getId())) {
                    // La activación es atómica: mismo prefijo y bloqueo representan un solo truncado.
                    paths.add(branch.result(blocked));
                }
            }
            return;
        }
    }

    private List<List<SequenceFlow>> alternatives(ProcessGraph graph, BpmnNode node) {
        List<SequenceFlow> outgoing = graph.getOutgoingFlows(node.getId());
        if (node.getType() == NodeType.EXCLUSIVE_GATEWAY) {
            return outgoing.stream().map(List::of).toList();
        }
        if (node.getType() != NodeType.INCLUSIVE_GATEWAY) {
            return List.of(outgoing);
        }
        if (ApplicationProcessRules.PROCESS_ID.equals(graph.getProcess().getId())) {
            return rules.getAlternatives(graph, node);
        }
        // Todos los subconjuntos no vacíos son candidatos, no ejecuciones demostradas.
        List<List<SequenceFlow>> combinations = new ArrayList<>();
        combinations.add(List.of());
        for (SequenceFlow flow : outgoing) {
            int size = combinations.size();
            for (int i = 0; i < size; i++) {
                List<SequenceFlow> combination = new ArrayList<>(combinations.get(i));
                combination.add(flow);
                combinations.add(List.copyOf(combination));
            }
        }
        return List.copyOf(combinations.subList(1, combinations.size()));
    }

    private BpmnNode nextNode(ProcessGraph graph, State state) {
        for (BpmnNode node : graph.getProcess().getNodes().values()) {
            List<SequenceFlow> incoming = graph.getIncomingFlows(node.getId());
            if (incoming.isEmpty()) {
                continue;
            }
            boolean enabled = node.getType() == NodeType.PARALLEL_GATEWAY
                    ? incoming.stream().allMatch(flow -> state.pending.containsKey(flow.getId()))
                    : incoming.stream().anyMatch(flow -> state.pending.containsKey(flow.getId()));
            if (enabled && node.getType() == NodeType.INCLUSIVE_GATEWAY && incoming.size() > 1) {
                enabled = inclusiveJoinReady(graph, node, state);
            }
            if (enabled) {
                return node;
            }
        }
        return null;
    }

    private boolean inclusiveJoinReady(ProcessGraph graph, BpmnNode join, State state) {
        for (SequenceFlow incoming : graph.getIncomingFlows(join.getId())) {
            if (state.pending.containsKey(incoming.getId())) {
                continue;
            }
            for (SequenceFlow flow : graph.getProcess().getSequenceFlows()) {
                if (state.pending.containsKey(flow.getId())
                        && canReach(graph, flow.getTargetRef(), incoming.getSourceRef(), join.getId())) {
                    return false;
                }
            }
        }
        return true;
    }

    // No se atraviesa la convergencia: sus salidas pertenecen al trabajo posterior.
    private boolean canReach(ProcessGraph graph, String start, String target, String blocked) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (current.equals(blocked) || !visited.add(current)) {
                continue;
            }
            if (current.equals(target)) {
                return true;
            }
            for (SequenceFlow flow : graph.getOutgoingFlows(current)) {
                pending.addLast(flow.getTargetRef());
            }
        }
        return false;
    }

    private void consume(ProcessGraph graph, BpmnNode node, State state) {
        for (SequenceFlow flow : graph.getIncomingFlows(node.getId())) {
            Integer count = state.pending.get(flow.getId());
            if (count != null) {
                if (count == 1) {
                    state.pending.remove(flow.getId());
                } else {
                    state.pending.put(flow.getId(), count - 1);
                }
                if (node.getType() != NodeType.PARALLEL_GATEWAY
                        && node.getType() != NodeType.INCLUSIVE_GATEWAY) {
                    return;
                }
            }
        }
    }

    private SequenceFlow activate(List<SequenceFlow> outgoing, State state,
                                  Set<String> backEdges, int maxReturns) {
        for (SequenceFlow flow : outgoing) {
            if (backEdges.contains(flow.getId()) && state.returns.getOrDefault(flow.getId(), 0) == maxReturns) {
                return flow;
            }
        }
        if (outgoing.size() > 1) {
            state.concurrentBranches.add(List.copyOf(outgoing));
        }
        for (SequenceFlow flow : outgoing) {
            if (backEdges.contains(flow.getId())) {
                int count = state.returns.getOrDefault(flow.getId(), 0);
                state.returns.put(flow.getId(), count + 1);
            }
            state.flows.add(flow);
            state.pending.merge(flow.getId(), 1, Integer::sum);
        }
        return null;
    }

    private void findBackEdges(ProcessGraph graph, BpmnNode node, Set<String> visited,
                               Set<String> active, Set<String> backEdges) {
        visited.add(node.getId());
        active.add(node.getId());
        for (SequenceFlow flow : graph.getOutgoingFlows(node.getId())) {
            if (active.contains(flow.getTargetRef())) {
                backEdges.add(flow.getId());
            } else if (!visited.contains(flow.getTargetRef())) {
                findBackEdges(graph, graph.getTargetNode(flow), visited, active, backEdges);
            }
        }
        active.remove(node.getId());
    }

    private void validate(ProcessGraph graph) {
        if (!graph.getProcess().getUnsupportedBehaviors().isEmpty()) {
            throw new IllegalArgumentException("Comportamiento no soportado para caminos: "
                    + String.join(", ", graph.getProcess().getUnsupportedBehaviors()));
        }
        if (graph.getStartEvents().size() != 1) {
            throw new IllegalArgumentException("El análisis requiere exactamente un evento inicial.");
        }
        if (ApplicationProcessRules.PROCESS_ID.equals(graph.getProcess().getId())) {
            rules.validate(graph);
        }
        for (BpmnNode node : graph.getProcess().getNodes().values()) {
            boolean supported = node.getType().isTask() || switch (node.getType()) {
                case START_EVENT, END_EVENT, EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY, INCLUSIVE_GATEWAY -> true;
                default -> false;
            };
            if (!supported) {
                throw new IllegalArgumentException("Tipo de nodo no soportado para caminos: " + node.getId());
            }
            List<SequenceFlow> outgoing = graph.getOutgoingFlows(node.getId());
            if (node.getType() == NodeType.INCLUSIVE_GATEWAY
                    && graph.getIncomingFlows(node.getId()).size() > 1
                    && outgoing.stream().anyMatch(flow -> canReach(graph, flow.getTargetRef(), node.getId(), null))) {
                throw new IllegalArgumentException("Convergencia inclusiva dentro de un ciclo no soportada: "
                        + node.getId());
            }
            if (node.getType() == NodeType.END_EVENT) {
                if (!outgoing.isEmpty()) {
                    throw new IllegalArgumentException("Un evento final no debe tener salidas: " + node.getId());
                }
            } else if (outgoing.isEmpty()) {
                throw new IllegalArgumentException("Nodo sin salida: " + node.getId());
            }
            if (node.getType() == NodeType.START_EVENT && !graph.getIncomingFlows(node.getId()).isEmpty()) {
                throw new IllegalArgumentException("El evento inicial no debe tener entradas.");
            }
            if (!node.getType().isGateway() && outgoing.size() > 1) {
                throw new IllegalArgumentException("Ramificación sin compuerta no soportada: " + node.getId());
            }
        }
    }

    private static class State {
        private final List<BpmnNode> nodes = new ArrayList<>();
        private final List<SequenceFlow> flows = new ArrayList<>();
        private final List<List<SequenceFlow>> concurrentBranches = new ArrayList<>();
        private final Map<String, Integer> pending = new HashMap<>();
        private final Map<String, Integer> returns = new HashMap<>();

        State() { }

        State(State source) {
            nodes.addAll(source.nodes);
            flows.addAll(source.flows);
            concurrentBranches.addAll(source.concurrentBranches);
            pending.putAll(source.pending);
            returns.putAll(source.returns);
        }

        ProcessPath result(SequenceFlow blocked) {
            return new ProcessPath(nodes, flows, blocked, returns.values().stream().mapToInt(Integer::intValue).sum(),
                    concurrentBranches);
        }
    }
}
