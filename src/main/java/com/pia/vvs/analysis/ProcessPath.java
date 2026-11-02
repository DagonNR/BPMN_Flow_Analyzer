package com.pia.vvs.analysis;

import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.SequenceFlow;

import java.util.List;

/**
 * Resultado estructural inmutable. COMPLETE alcanza un final sin tokens pendientes;
 * TRUNCATED detiene una alternativa al intentar superar el límite de un retorno.
 * Ningún estado demuestra la viabilidad de las condiciones de negocio.
 * Los nodos son un orden determinista de procesamiento de tokens, no necesariamente
 * una cadena de aristas entre nodos consecutivos ni un orden temporal entre ramas.
 * Los flujos conservan sus IDs, extremos, nombres y condiciones originales.
 */
public class ProcessPath {
    public enum Status { COMPLETE, TRUNCATED }

    private final List<BpmnNode> nodes;
    private final List<SequenceFlow> flows;
    private final SequenceFlow blockedFlow;
    private final int returnCount;
    private final List<List<SequenceFlow>> concurrentBranches;

    ProcessPath(List<BpmnNode> nodes, List<SequenceFlow> flows,
                SequenceFlow blockedFlow, int returnCount, List<List<SequenceFlow>> concurrentBranches) {
        this.nodes = List.copyOf(nodes);
        this.flows = List.copyOf(flows);
        this.blockedFlow = blockedFlow;
        this.returnCount = returnCount;
        this.concurrentBranches = concurrentBranches.stream().map(List::copyOf).toList();
    }

    public List<BpmnNode> getNodes() { return nodes; }
    public List<SequenceFlow> getFlows() { return flows; }
    public SequenceFlow getBlockedFlow() { return blockedFlow; }
    public int getReturnCount() { return returnCount; }
    /** Cada grupo contiene las salidas activadas juntas en una ocurrencia de AND u OR. */
    public List<List<SequenceFlow>> getConcurrentBranches() { return concurrentBranches; }
    public boolean isComplete() { return blockedFlow == null; }
    public Status getStatus() { return isComplete() ? Status.COMPLETE : Status.TRUNCATED; }
}
