package com.a22.platform.auth;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(Long id) {
        super("未找到用户：" + id);
    }

    public UserNotFoundException(String username) {
        super("未找到用户：" + username);
    }
}