package com.a22.platform.auth;

/**
 * 线程级当前用户上下文。
 * 由 AuthInterceptor 在请求进入时设置，请求结束时清除。
 */
public final class AuthContext {

    private static final ThreadLocal<UserEntity> CURRENT = new ThreadLocal<>();

    private AuthContext() { }

    public static void set(UserEntity user) {
        CURRENT.set(user);
    }

    /** 获取当前登录用户，未登录时返回 null */
    public static UserEntity get() {
        return CURRENT.get();
    }

    /** 获取当前用户 ID，未登录时返回 null */
    public static Long getUserId() {
        UserEntity user = CURRENT.get();
        return user != null ? user.getId() : null;
    }

    /** 获取当前用户名，未登录时返回 null */
    public static String getUsername() {
        UserEntity user = CURRENT.get();
        return user != null ? user.getUsername() : null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}