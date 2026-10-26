package com.a22.platform.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口：登录、注册、当前用户信息、登出、修改密码。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        return authService.login(username.trim(), password);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("密码长度至少 8 位");
        }
        String displayName = body.getOrDefault("displayName", username.trim());
        return authService.register(username.trim(), password, displayName);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        String token = extractToken(auth);
        return authService.me(token);
    }

    @PostMapping("/logout")
    public Map<String, String> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            authService.logout(auth.substring(7));
        }
        return Map.of("message", "已退出登录");
    }

    /** 用户自行修改密码（需要知道原密码） */
    @PostMapping("/change-password")
    public Map<String, String> changePassword(@RequestBody Map<String, String> body) {
        Long userId = AuthContext.getUserId();
        if (userId == null) throw new RuntimeException("未登录");

        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (oldPassword == null || oldPassword.isBlank()) {
            throw new IllegalArgumentException("请输入原密码");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("新密码长度至少 8 位");
        }

        authService.changePassword(userId, oldPassword, newPassword);
        return Map.of("message", "密码修改成功，请重新登录");
    }

    /** 用户自行修改用户名 */
    @PatchMapping("/username")
    public Map<String, Object> updateUsername(@RequestBody Map<String, String> body) {
        Long userId = AuthContext.getUserId();
        if (userId == null) throw new RuntimeException("未登录");
        String newUsername = body.get("username");
        if (newUsername == null || newUsername.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        return authService.changeUsername(userId, newUsername.trim());
    }

    /** 用户修改显示名称 */
    @PatchMapping("/display-name")
    public Map<String, Object> updateDisplayName(@RequestBody Map<String, String> body) {
        Long userId = AuthContext.getUserId();
        if (userId == null) throw new RuntimeException("未登录");
        String displayName = body.get("displayName");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("显示名称不能为空");
        }
        return authService.changeDisplayName(userId, displayName.trim());
    }

    private String extractToken(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new RuntimeException("未登录");
        }
        return auth.substring(7);
    }
}