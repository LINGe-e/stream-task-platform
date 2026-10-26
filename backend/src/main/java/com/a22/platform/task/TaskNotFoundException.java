package com.a22.platform.task;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(long id) {
        super("未找到任务：" + id);
    }
}
