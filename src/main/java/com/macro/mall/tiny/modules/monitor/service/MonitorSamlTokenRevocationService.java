package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.security.util.JwtTokenUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class MonitorSamlTokenRevocationService implements LogoutHandler {
    private static final String KEY_PREFIX = "monitor:saml:revoked-before:";

    private final StringRedisTemplate redis;

    @Value("${jwt.expiration:604800}")
    private long tokenLifetimeSeconds;

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal principal)) return;
        String tenantKey = principal.getRelyingPartyRegistrationId();
        String subject = principal.getName();
        if (tenantKey == null || subject == null || subject.isBlank()) return;
        long revokedAt = System.currentTimeMillis();
        redis.opsForValue().set(key(tenantKey, subject), Long.toString(revokedAt),
                Duration.ofSeconds(Math.max(60, tokenLifetimeSeconds + 60)));
    }

    public boolean isRevoked(JwtTokenUtil.SamlTokenIdentity identity) {
        String value = redis.opsForValue().get(key(identity.tenantKey(), identity.subject()));
        if (value == null) return false;
        try {
            return identity.issuedAt() <= Long.parseLong(value);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private String key(String tenantKey, String subject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenantKey + "\u0000" + subject).getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
