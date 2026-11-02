package com.pia.vvs.analysis;

import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.SequenceFlow;

import java.util.List;

/**
 * Proyección para visualizar ramas, no una ejecución independiente ni un ProcessPath COMPLETE.
 * Conserva ocurrencias de nodos y flujos; los números de origen corresponden a F1, F2, etc.
 * de la lista original de caminos, incluidos sus huecos por caminos truncados.
 */
public class BranchFlow {
    private final List<BpmnNode> nodes;
    private final List<SequenceFlow> flows;
    private final List<SequenceFlow> selectedParallelFlows;
    private final List<Integer> sourcePathNumbers;

    BranchFlow(List<BpmnNode> nodes, List<SequenceFlow> flows,
               List<SequenceFlow> selectedParallelFlows, List<Integer> sourcePathNumbers) {
        this.nodes = List.copyOf(nodes);
        this.flows = List.copyOf(flows);
        this.selectedParallelFlows = List.copyOf(selectedParallelFlows);
        this.sourcePathNumbers = List.copyOf(sourcePathNumbers);
    }

    public List<BpmnNode> getNodes() { return nodes; }
    public List<SequenceFlow> getFlows() { return flows; }
    public List<SequenceFlow> getSelectedParallelFlows() { return selectedParallelFlows; }
    public List<Integer> getSourcePathNumbers() { return sourcePathNumbers; }
}
