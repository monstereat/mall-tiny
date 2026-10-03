package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import com.macro.mall.tiny.security.util.JwtTokenUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class MonitorSamlLoginCodeService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "monitor:saml:login-code:";
    private static final Duration CODE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final UmsAdminService adminService;
    private final JwtTokenUtil jwtTokenUtil;

    public String issue(String username, String tenantKey, String subject) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String payload = encode(username) + "." + encode(tenantKey) + "." + encode(subject);
        redis.opsForValue().set(KEY_PREFIX + code, payload, CODE_TTL);
        return code;
    }

    public String exchange(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{40,64}")) return null;
        String payload = redis.opsForValue().getAndDelete(KEY_PREFIX + code);
        if (payload == null) return null;
        String[] parts = payload.split("\\.", -1);
        if (parts.length != 3) return null;
        String username = decode(parts[0]);
        String tenantKey = decode(parts[1]);
        String subject = decode(parts[2]);
        if (username == null || tenantKey == null || subject == null) return null;
        return jwtTokenUtil.generateSamlToken(adminService.loadUserByUsername(username), tenantKey, subject);
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            return decoded.isBlank() ? null : decoded;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
