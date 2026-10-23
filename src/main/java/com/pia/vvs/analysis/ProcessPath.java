package com.pia.vvs.analysis;

import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.SequenceFlow;

import java.util.List;

public class ProcessPath {
    public enum Status { COMPLETE, TRUNCATED }

    private final List<BpmnNode> nodes;
    private final List<SequenceFlow> flows;
    private final SequenceFlow blockedFlow;
    private final int returnCount;

    ProcessPath(List<BpmnNode> nodes, List<SequenceFlow> flows,
                SequenceFlow blockedFlow, int returnCount) {
        this.nodes = List.copyOf(nodes);
        this.flows = List.copyOf(flows);
        this.blockedFlow = blockedFlow;
        this.returnCount = returnCount;
    }

    public List<BpmnNode> getNodes() { return nodes; }
    public List<SequenceFlow> getFlows() { return flows; }
    public SequenceFlow getBlockedFlow() { return blockedFlow; }
    public int getReturnCount() { return returnCount; }
    public boolean isComplete() { return blockedFlow == null; }
    public Status getStatus() { return isComplete() ? Status.COMPLETE : Status.TRUNCATED; }
}
