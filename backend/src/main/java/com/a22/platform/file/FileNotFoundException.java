package com.a22.platform.file;

public class FileNotFoundException extends RuntimeException {
    public FileNotFoundException(long id) {
        super("未找到文件：" + id);
    }
}
