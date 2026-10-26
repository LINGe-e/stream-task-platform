package com.a22.platform.flink;

public record FlinkSubmitRequest(int parallelism) {
    public int safeParallelism() {
        return parallelism > 0 ? parallelism : 1;
    }
}
