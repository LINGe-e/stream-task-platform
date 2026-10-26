package com.a22.platform.engine;

public record DagBranchResult(
        String branchName,
        String branchType,
        String sourceNodeId,
        String sourceNodeLabel,
        String targetNodeId,
        String targetNodeLabel,
        int inputCount,
        int outputCount,
        String path,
        String conditionSummary
) {}
