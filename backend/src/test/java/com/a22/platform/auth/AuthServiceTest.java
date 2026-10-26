package com.a22.platform.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService 单元测试。
 * 使用 Mockito 模拟 Repository，不依赖数据库。
 */
class AuthServiceTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<Long, UserEntity> userStore = new LinkedHashMap<>();
    private final Map<String, TokenEntity> tokenStore = new HashMap<>();
    private long nextUserId = 1;

    private UserRepository userRepo;
    private TokenRepository tokenRepo;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userStore.clear();
        tokenStore.clear();
        nextUserId = 1;

        userRepo = mock(UserRepository.class);
        tokenRepo = mock(TokenRepository.class);

        // 模拟 findByUsername
        when(userRepo.findByUsername(anyString())).thenAnswer(inv -> {
            String username = inv.getArgument(0);
            return userStore.values().stream().filter(u -> u.getUsername().equals(username)).findFirst();
        });

        // 模拟 existsByUsername
        when(userRepo.existsByUsername(anyString())).thenAnswer(inv -> {
            String username = inv.getArgument(0);
            return userStore.values().stream().anyMatch(u -> u.getUsername().equals(username));
        });

        // 模拟 countByRole
        when(userRepo.countByRole(anyString())).thenAnswer(inv -> {
            String role = inv.getArgument(0);
            return userStore.values().stream().filter(u -> role.equals(u.getRole())).count();
        });

        // 模拟 save
        when(userRepo.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                setId(entity, nextUserId++);
            }
            userStore.put(entity.getId(), entity);
            return entity;
        });

        // 模拟 findById
        when(userRepo.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.ofNullable(userStore.get(id));
        });

        // 模拟 findAll
        when(userRepo.findAll()).thenAnswer(inv -> new ArrayList<>(userStore.values()));

        // 模拟 delete
        doAnswer(inv -> {
            UserEntity entity = inv.getArgument(0);
            userStore.remove(entity.getId());
            return null;
        }).when(userRepo).delete(any(UserEntity.class));

        // Token repo mocks
        when(tokenRepo.findByToken(anyString())).thenAnswer(inv -> {
            String token = inv.getArgument(0);
            return Optional.ofNullable(tokenStore.get(token));
        });

        when(tokenRepo.save(any(TokenEntity.class))).thenAnswer(inv -> {
            TokenEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                setId(entity, (long) (tokenStore.size() + 1));
            }
            tokenStore.put(entity.getToken(), entity);
            return entity;
        });

        // 模拟 delete(TokenEntity)
        doAnswer(inv -> {
            TokenEntity entity = inv.getArgument(0);
            tokenStore.remove(entity.getToken());
            return null;
        }).when(tokenRepo).delete(any(TokenEntity.class));

        // 模拟 deleteByUserId
        doAnswer(inv -> {
            Long userId = inv.getArgument(0);
            tokenStore.entrySet().removeIf(e -> e.getValue().getUserId().equals(userId));
            return null;
        }).when(tokenRepo).deleteByUserId(anyLong());

        authService = new AuthService(userRepo, tokenRepo);

        // 手动 seed 管理员
        UserEntity admin = new UserEntity("admin", encoder.encode("Admin@123456"), "SADMIN", "管理员");
        userRepo.save(admin);
    }

    private void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ==================== 认证测试 ====================

    @Test
    void loginWithValidCredentials() {
        Map<String, Object> result = authService.login("admin", "Admin@123456");
        assertNotNull(result.get("token"));
        Map<?, ?> user = (Map<?, ?>) result.get("user");
        assertEquals("admin", user.get("username"));
        assertEquals("SADMIN", user.get("role"));
    }

    @Test
    void loginWithWrongPassword() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.login("admin", "wrongpassword"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
    }

    @Test
    void loginWithNonexistentUser() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.login("nobody", "12345678"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
    }

    @Test
    void registerNewUser() {
        Map<String, Object> result = authService.register("testuser", "password123", "测试用户");
        assertNotNull(result.get("token"));
        Map<?, ?> user = (Map<?, ?>) result.get("user");
        assertEquals("testuser", user.get("username"));
        assertEquals("USER", user.get("role"));
        assertEquals("测试用户", user.get("displayName"));
    }

    @Test
    void registerDuplicateUsername() {
        authService.register("testuser", "password123", "测试");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.register("testuser", "password456", "测试2"));
        assertTrue(ex.getMessage().contains("用户名已存在"));
    }

    @Test
    void meWithValidToken() {
        Map<String, Object> loginResult = authService.login("admin", "Admin@123456");
        String token = (String) loginResult.get("token");

        Map<String, Object> meResult = authService.me(token);
        assertEquals("admin", meResult.get("username"));
        assertEquals("SADMIN", meResult.get("role"));
    }

    @Test
    void meWithInvalidToken() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.me("invalid-token"));
        assertTrue(ex.getMessage().contains("Token 无效"));
    }

    @Test
    void logout() {
        Map<String, Object> loginResult = authService.login("admin", "Admin@123456");
        String token = (String) loginResult.get("token");

        authService.logout(token);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.me(token));
        assertTrue(ex.getMessage().contains("Token 无效"));
    }

    // ==================== 管理员操作测试 ====================

    @Test
    void listUsers() {
        authService.register("user1", "password123", "用户1");
        authService.register("user2", "password123", "用户2");

        List<Map<String, Object>> users = authService.listUsers();
        assertTrue(users.size() >= 3);
    }

    @Test
    void changeRole() {
        Map<String, Object> regResult = authService.register("user1", "password123", "用户1");
        Long userId = getUserId(regResult);

        Map<String, Object> result = authService.changeRole(findSAdminId(), userId, "OPS_READONLY");
        assertEquals("OPS_READONLY", result.get("role"));
    }

    @Test
    void changeRoleInvalidRole() {
        Map<String, Object> regResult = authService.register("user1", "password123", "用户1");
        Long userId = getUserId(regResult);

        assertThrows(IllegalArgumentException.class,
                () -> authService.changeRole(findSAdminId(), userId, "SUPERADMIN"));
    }

    @Test
    void cannotDemoteLastSAdmin() {
        Long sadminId = findSAdminId();
        assertThrows(IllegalStateException.class,
                () -> authService.changeRole(sadminId, sadminId, "USER"));
    }

    @Test
    void adminResetPassword() {
        Map<String, Object> regResult = authService.register("user1", "password123", "用户1");
        Long userId = getUserId(regResult);

        Map<String, Object> result = authService.adminResetPassword(findSAdminId(), userId);
        String tempPassword = (String) result.get("tempPassword");
        assertNotNull(tempPassword);
        assertEquals(12, tempPassword.length());

        Map<String, Object> loginResult = authService.login("user1", tempPassword);
        assertNotNull(loginResult.get("token"));
    }

    @Test
    void adminResetPasswordInvalidatesOldTokens() {
        Map<String, Object> regResult = authService.register("user1", "password123", "用户1");
        Long userId = getUserId(regResult);
        String oldToken = (String) regResult.get("token");

        authService.adminResetPassword(findSAdminId(), userId);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> authService.me(oldToken));
        assertTrue(ex.getMessage().contains("Token 无效"));
    }

    @Test
    void deleteUser() {
        Map<String, Object> regResult = authService.register("user1", "password123", "用户1");
        Long userId = getUserId(regResult);

        authService.deleteUser(findSAdminId(), userId);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.login("user1", "password123"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
    }

    @Test
    void cannotDeleteLastSAdmin() {
        Long sadminId = findSAdminId();
        assertThrows(IllegalStateException.class,
                () -> authService.deleteUser(sadminId, sadminId));
    }

    @Test
    void cannotSetDuplicateSAdmin() {
        Map<String, Object> regResult = authService.register("user2", "password123", "用户2");
        Long user2Id = getUserId(regResult);
        assertThrows(IllegalStateException.class,
                () -> authService.changeRole(findSAdminId(), user2Id, "SADMIN"));
    }
    @Test
    void canDeleteNonLastAdmin() {
        Map<String, Object> regResult = authService.register("admin2", "password123", "管理员2");
        Long admin2Id = getUserId(regResult);
        authService.changeRole(findSAdminId(), admin2Id, "ADMIN");

        assertDoesNotThrow(() -> authService.deleteUser(findSAdminId(), admin2Id));
    }

    @Test
    void changePassword() {
        Map<String, Object> regResult = authService.register("user1", "password123", "用户1");
        Long userId = getUserId(regResult);

        authService.changePassword(userId, "password123", "newpassword123");

        Map<String, Object> loginResult = authService.login("user1", "newpassword123");
        assertNotNull(loginResult.get("token"));
    }

    @Test
    void changePasswordWithWrongOldPassword() {
        Map<String, Object> regResult = authService.register("user1", "password123", "用户1");
        Long userId = getUserId(regResult);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.changePassword(userId, "wrongpassword", "newpassword123"));
        assertTrue(ex.getMessage().contains("原密码错误"));
    }

    // ==================== 辅助方法 ====================

    private Long getUserId(Map<String, Object> regResult) {
        return ((Number) ((Map<?, ?>) regResult.get("user")).get("id")).longValue();
    }

    private Long findSAdminId() {
        return userStore.values().stream()
                .filter(u -> "SADMIN".equals(u.getRole()))
                .findFirst()
                .map(UserEntity::getId)
                .orElseThrow();
    }

    private Long findAdminId() {
        return userStore.values().stream()
                .filter(u -> "ADMIN".equals(u.getRole()))
                .findFirst()
                .map(UserEntity::getId)
                .orElseThrow();
    }
}