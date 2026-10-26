package com.a22.platform.file;

public class InvalidFileException extends RuntimeException {
    public InvalidFileException(String message) { super(message); }
    public InvalidFileException(String message, Throwable cause) { super(message, cause); }
}
