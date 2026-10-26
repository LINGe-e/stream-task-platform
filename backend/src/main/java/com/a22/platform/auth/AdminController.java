package com.a22.platform.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员专属接口：用户管理。
 * SADMIN 和 ADMIN 可访问，但权限范围不同。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AuthService authService;

    public AdminController(AuthService authService) {
        this.authService = authService;
    }

    /** 获取所有用户列表 */
    @GetMapping("/users")
    public List<Map<String, Object>> listUsers() {
        ensureAdmin();
        return authService.listUsers();
    }

    /** 修改用户角色 */
    @PatchMapping("/users/{id}/role")
    public Map<String, Object> changeRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long operatorId = ensureAdmin();
        String role = body.get("role");
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("角色不能为空");
        }
        return authService.changeRole(operatorId, id, role.trim());
    }

    /** 管理员为用户重置密码，返回临时密码 */
    @PostMapping("/users/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable Long id) {
        Long operatorId = ensureAdmin();
        return authService.adminResetPassword(operatorId, id);
    }

    /** 删除用户 */
    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable Long id) {
        Long operatorId = ensureAdmin();
        authService.deleteUser(operatorId, id);
    }

    /** 确保当前用户是管理员（SADMIN 或 ADMIN），返回操作者 ID */
    private Long ensureAdmin() {
        UserEntity current = AuthContext.get();
        if (current == null || !AuthService.isAdmin(current.getRole())) {
            throw new IllegalStateException("仅管理员可执行此操作");
        }
        return current.getId();
    }
}