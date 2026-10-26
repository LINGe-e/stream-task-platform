package com.a22.platform.auth;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 登录令牌实体，持久化到数据库。
 * 用户登出时删除对应记录。
 */
@Entity
@Table(name = "user_tokens")
public class TokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String token;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TokenEntity() { }

    public TokenEntity(String token, Long userId) {
        this.token = token;
        this.userId = userId;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getToken() { return token; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
}