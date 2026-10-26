package com.a22.platform.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认证与用户管理服务。
 * 角色层级：SADMIN（高级管理员）> ADMIN（管理员）> USER（普通用户）> OPS_READONLY（只读运维）
 *
 * 权限规则：
 * - SADMIN 可管理所有用户（包括设置/降级/删除管理员）
 * - ADMIN 只能管理 USER 和 OPS_READONLY，不能动其他 ADMIN 和 SADMIN
 * - 管理员之间不能互相删除或降级
 * - 不能删除或降级最后一个 SADMIN
 */
@Service
public class AuthService {

    /** 所有合法角色，按权限从高到低排列 */
    public static final List<String> ALL_ROLES = List.of("SADMIN", "ADMIN", "USER", "OPS_READONLY");

    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, TokenRepository tokenRepository) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
    }

    /** 应用启动时确保存在默认高级管理员账号，如果已有 admin 但角色不是 SADMIN 则自动升级 */
    @PostConstruct
    @Transactional
    void seedAdmin() {
        var existing = userRepository.findByUsername("admin");
        if (existing.isEmpty()) {
            userRepository.save(new UserEntity(
                    "admin",
                    passwordEncoder.encode("Admin@123456"),
                    "SADMIN",
                    "管理员"
            ));
        } else if (!"SADMIN".equals(existing.get().getRole())) {
            // 已有 admin 但角色不是 SADMIN，自动升级
            UserEntity admin = existing.get();
            admin.setRole("SADMIN");
            userRepository.save(admin);
        }
    }

    // ==================== 认证 ====================

    @Transactional
    public Map<String, Object> login(String username, String password) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户名或密码错误"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new RuntimeException("用户名或密码错误");
        }

        String token = UUID.randomUUID().toString();
        tokenRepository.save(new TokenEntity(token, user.getId()));

        return Map.of(
                "token", token,
                "user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "role", user.getRole(),
                        "displayName", user.getDisplayName()
                )
        );
    }

    @Transactional
    public Map<String, Object> register(String username, String password, String displayName) {
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在");
        }

        UserEntity user = userRepository.save(new UserEntity(
                username,
                passwordEncoder.encode(password),
                "USER",
                displayName != null && !displayName.isBlank() ? displayName : username
        ));

        String token = UUID.randomUUID().toString();
        tokenRepository.save(new TokenEntity(token, user.getId()));

        return Map.of(
                "token", token,
                "user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "role", user.getRole(),
                        "displayName", user.getDisplayName()
                )
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> me(String token) {
        TokenEntity tokenEntity = tokenRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Token 无效"));
        UserEntity user = userRepository.findById(tokenEntity.getUserId())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole(),
                "displayName", user.getDisplayName()
        );
    }

    @Transactional(readOnly = true)
    public UserEntity resolveToken(String token) {
        TokenEntity tokenEntity = tokenRepository.findByToken(token).orElse(null);
        if (tokenEntity == null) return null;
        return userRepository.findById(tokenEntity.getUserId()).orElse(null);
    }

    @Transactional
    public void logout(String token) {
        tokenRepository.findByToken(token).ifPresent(tokenRepository::delete);
    }

    // ==================== 管理员操作 ====================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers() {
        return userRepository.findAll().stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("username", u.getUsername());
                    m.put("role", u.getRole());
                    m.put("displayName", u.getDisplayName());
                    m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
                    m.put("updatedAt", u.getUpdatedAt() != null ? u.getUpdatedAt().toString() : "");
                    return m;
                })
                .toList();
    }

    /**
     * 修改用户角色。
     * 权限规则：
     * - 只有 SADMIN 可以设置/降级 ADMIN 角色
     * - ADMIN 只能管理 USER 和 OPS_READONLY
     * - 管理员之间不能互相降级（需 SADMIN 操作）
     * - 不能降级最后一个 SADMIN
     */
    @Transactional
    public Map<String, Object> changeRole(Long operatorId, Long targetUserId, String newRole) {
        if (!ALL_ROLES.contains(newRole)) {
            throw new IllegalArgumentException("无效角色：" + newRole);
        }

        UserEntity operator = userRepository.findById(operatorId)
                .orElseThrow(() -> new UserNotFoundException(operatorId));
        UserEntity target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserNotFoundException(targetUserId));

        String operatorRole = operator.getRole();
        String targetRole = target.getRole();

        // SADMIN 不能降级自己
        if (operatorId.equals(targetUserId) && "SADMIN".equals(targetRole) && !"SADMIN".equals(newRole)) {
            throw new IllegalStateException("高级管理员不能降级自己");
        }

        // 非 SADMIN 不能操作 ADMIN 或 SADMIN
        if (!"SADMIN".equals(operatorRole)) {
            if ("ADMIN".equals(targetRole) || "SADMIN".equals(targetRole)) {
                throw new IllegalStateException("只有高级管理员可以修改管理员角色");
            }
            // ADMIN 不能设置 SADMIN 或 ADMIN
            if ("SADMIN".equals(newRole) || "ADMIN".equals(newRole)) {
                throw new IllegalStateException("只有高级管理员可以设置管理员角色");
            }
        }

        // SADMIN 有且只有一个：不能将其他人提升为 SADMIN
        if ("SADMIN".equals(newRole) && !"SADMIN".equals(targetRole)) {
            long existingSAdmin = userRepository.countByRole("SADMIN");
            if (existingSAdmin >= 1) {
                throw new IllegalStateException("高级管理员已有且仅有一人，不能重复设置");
            }
        }

        // 不能降级最后一个 SADMIN
        if ("SADMIN".equals(targetRole) && !"SADMIN".equals(newRole)) {
            long sadminCount = userRepository.countByRole("SADMIN");
            if (sadminCount <= 1) {
                throw new IllegalStateException("不能降级最后一个高级管理员");
            }
        }

        target.setRole(newRole);
        userRepository.save(target);

        return Map.of(
                "id", target.getId(),
                "username", target.getUsername(),
                "role", target.getRole(),
                "displayName", target.getDisplayName()
        );
    }

    @Transactional
    public Map<String, Object> adminResetPassword(Long operatorId, Long targetUserId) {
        UserEntity operator = userRepository.findById(operatorId)
                .orElseThrow(() -> new UserNotFoundException(operatorId));
        UserEntity target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserNotFoundException(targetUserId));

        // 非 SADMIN 不能重置 ADMIN/SADMIN 的密码
        checkManagePermission(operator, target);

        String tempPassword = generateTempPassword();
        target.setPasswordHash(passwordEncoder.encode(tempPassword));
        userRepository.save(target);

        tokenRepository.deleteByUserId(targetUserId);

        return Map.of(
                "userId", target.getId(),
                "username", target.getUsername(),
                "tempPassword", tempPassword
        );
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("原密码错误");
        }

        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("新密码长度至少 8 位");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        tokenRepository.deleteByUserId(userId);
    }

    /**
     * 删除用户。
     * 规则：
     * - 非 SADMIN 不能删除 ADMIN/SADMIN
     * - 管理员之间不能互相删除
     * - 不能删除最后一个 SADMIN
     */
    @Transactional
    public Map<String, Object> changeUsername(Long userId, String newUsername) {
        if (newUsername == null || newUsername.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (newUsername.length() < 2 || newUsername.length() > 30) {
            throw new IllegalArgumentException("用户名长度应在 2-30 个字符之间");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        UserEntity existing = userRepository.findByUsername(newUsername).orElse(null);
        if (existing != null && !existing.getId().equals(userId)) {
            throw new IllegalStateException("用户名已被使用");
        }
        user.setUsername(newUsername);
        userRepository.save(user);
        tokenRepository.deleteByUserId(userId);
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole(),
                "displayName", user.getDisplayName()
        );
    }

    @Transactional
    public Map<String, Object> changeDisplayName(Long userId, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("显示名称不能为空");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.setDisplayName(displayName);
        userRepository.save(user);
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole(),
                "displayName", user.getDisplayName()
        );
    }

    @Transactional
    public void deleteUser(Long operatorId, Long targetUserId) {
        UserEntity operator = userRepository.findById(operatorId)
                .orElseThrow(() -> new UserNotFoundException(operatorId));
        UserEntity target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserNotFoundException(targetUserId));

        // 非 SADMIN 不能删除 ADMIN/SADMIN
        checkManagePermission(operator, target);

        // 不能删除最后一个 SADMIN
        if ("SADMIN".equals(target.getRole())) {
            long sadminCount = userRepository.countByRole("SADMIN");
            if (sadminCount <= 1) {
                throw new IllegalStateException("不能删除最后一个高级管理员");
            }
        }

        tokenRepository.deleteByUserId(targetUserId);
        userRepository.delete(target);
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查操作者是否有权限管理目标用户。
     * 规则：只有 SADMIN 可以管理 ADMIN/SADMIN 账号。
     */
    private void checkManagePermission(UserEntity operator, UserEntity target) {
        String operatorRole = operator.getRole();
        String targetRole = target.getRole();

        if ("SADMIN".equals(targetRole) || "ADMIN".equals(targetRole)) {
            if (!"SADMIN".equals(operatorRole)) {
                throw new IllegalStateException("只有高级管理员可以操作管理员账号");
            }
            // SADMIN 不能对自己操作（重置密码时允许，删除/降级时在外层处理）
        }
    }

    /** 判断角色是否为管理员级别（SADMIN 或 ADMIN） */
    public static boolean isAdmin(String role) {
        return "SADMIN".equals(role) || "ADMIN".equals(role);
    }

    /** 判断角色是否为高级管理员 */
    public static boolean isSAdmin(String role) {
        return "SADMIN".equals(role);
    }

    private String generateTempPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return sb.toString();
    }
}