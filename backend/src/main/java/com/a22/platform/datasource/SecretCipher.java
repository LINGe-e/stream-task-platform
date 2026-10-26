package com.a22.platform.datasource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 使用 AES-GCM 加密数据源密码；密钥通过环境变量注入。
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.datasource.encryption-key:a22-local-datasource-key}") String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            key = new SecretKeySpec(digest, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("无法初始化数据源密码加密", exception);
        }
    }

    public String encrypt(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) return "";
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception exception) {
            throw new IllegalStateException("数据源密码加密失败", exception);
        }
    }

    public String decrypt(String storedPassword) {
        if (storedPassword == null || storedPassword.isEmpty()) return "";
        if (!storedPassword.startsWith(PREFIX)) return storedPassword;
        try {
            String payload = storedPassword.substring(PREFIX.length());
            String[] parts = payload.split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("加密数据格式错误");
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("数据源密码解密失败，请检查 A22_DATASOURCE_ENCRYPTION_KEY", exception);
        }
    }
}
