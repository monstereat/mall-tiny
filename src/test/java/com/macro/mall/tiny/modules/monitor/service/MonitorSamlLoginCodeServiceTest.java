package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import com.macro.mall.tiny.security.util.JwtTokenUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.userdetails.UserDetails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorSamlLoginCodeServiceTest {
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UmsAdminService adminService = mock(UmsAdminService.class);
    private final JwtTokenUtil jwtTokenUtil = mock(JwtTokenUtil.class);
    private final MonitorSamlLoginCodeService service = new MonitorSamlLoginCodeService(redis, adminService, jwtTokenUtil);

    @Test
    void issuesShortLivedOpaqueCode() {
        when(redis.opsForValue()).thenReturn(values);
        String code = service.issue("alice", "tenant-a", "alice@example.test");

        assertTrue(code.matches("[A-Za-z0-9_-]{40,64}"));
        verify(values).set(startsWith("monitor:saml:login-code:"),
                eq("YWxpY2U.dGVuYW50LWE.YWxpY2VAZXhhbXBsZS50ZXN0"), eq(java.time.Duration.ofSeconds(60)));
    }

    @Test
    void exchangesCodeOnceAndRejectsMalformedCodes() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete(anyString())).thenReturn("YWxpY2U.dGVuYW50LWE.YWxpY2VAZXhhbXBsZS50ZXN0").thenReturn(null);
        UserDetails user = mock(UserDetails.class);
        when(adminService.loadUserByUsername("alice")).thenReturn(user);
        when(jwtTokenUtil.generateSamlToken(user, "tenant-a", "alice@example.test")).thenReturn("jwt-token");
        String code = "a".repeat(43);

        assertEquals("jwt-token", service.exchange(code));
        verify(jwtTokenUtil).generateSamlToken(user, "tenant-a", "alice@example.test");
        assertNull(service.exchange(code));
        assertNull(service.exchange("bad code"));
        verify(values, times(2)).getAndDelete("monitor:saml:login-code:" + code);
    }

    @Test
    void consumesAndRejectsMalformedStoredPayload() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete(anyString())).thenReturn("not-a-valid-payload");

        assertNull(service.exchange("a".repeat(43)));
        verify(adminService, never()).loadUserByUsername(anyString());
    }
}
