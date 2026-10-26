package com.a22.platform.engine;

public class NodeExecutionException extends RuntimeException {
    private final String nodeId;
    private final String nodeLabel;
    private final String nodeType;

    public NodeExecutionException(String nodeId, String nodeLabel, String nodeType, Throwable cause) {
        super("节点[" + nodeLabel + "]执行失败：" + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
        this.nodeId = nodeId;
        this.nodeLabel = nodeLabel;
        this.nodeType = nodeType;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeLabel() {
        return nodeLabel;
    }

    public String getNodeType() {
        return nodeType;
    }
}
