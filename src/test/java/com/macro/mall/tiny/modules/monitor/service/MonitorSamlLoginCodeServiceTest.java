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
    private final MonitorSamlTokenRevocationService revocationService = mock(MonitorSamlTokenRevocationService.class);
    private final MonitorSamlLoginCodeService service = new MonitorSamlLoginCodeService(
            redis, adminService, jwtTokenUtil, revocationService);

    @Test
    void issuesShortLivedOpaqueCode() {
        when(redis.opsForValue()).thenReturn(values);
        String code = service.issue("alice", "tenant-a", "alice@example.test");

        assertTrue(code.matches("[A-Za-z0-9_-]{40,64}"));
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(startsWith("monitor:saml:login-code:"), payload.capture(),
                eq(java.time.Duration.ofSeconds(60)));
        assertTrue(payload.getValue().matches("YWxpY2U\\.dGVuYW50LWE\\.YWxpY2VAZXhhbXBsZS50ZXN0\\.\\d{13}"));
    }

    @Test
    void exchangesCodeOnceAndRejectsMalformedCodes() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete(anyString())).thenReturn(
                "YWxpY2U.dGVuYW50LWE.YWxpY2VAZXhhbXBsZS50ZXN0.1791120000000").thenReturn(null);
        UserDetails user = mock(UserDetails.class);
        when(adminService.loadUserByUsername("alice")).thenReturn(user);
        when(jwtTokenUtil.generateSamlToken(user, "tenant-a", "alice@example.test", 1791120000000L))
                .thenReturn("jwt-token");
        String code = "a".repeat(43);

        assertEquals("jwt-token", service.exchange(code));
        verify(jwtTokenUtil).generateSamlToken(user, "tenant-a", "alice@example.test", 1791120000000L);
        assertNull(service.exchange(code));
        assertNull(service.exchange("bad code"));
        verify(values, times(2)).getAndDelete("monitor:saml:login-code:" + code);
    }

    @Test
    void rejectsLoginCodeIssuedBeforeSamlLogout() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete(anyString())).thenReturn(
                "YWxpY2U.dGVuYW50LWE.YWxpY2VAZXhhbXBsZS50ZXN0.1791120000000");
        when(revocationService.isRevoked(new JwtTokenUtil.SamlTokenIdentity(
                "tenant-a", "alice@example.test", 1791120000000L))).thenReturn(true);

        assertNull(service.exchange("a".repeat(43)));
        verify(adminService, never()).loadUserByUsername(anyString());
        verify(jwtTokenUtil, never()).generateSamlToken(any(), anyString(), anyString(), anyLong());
    }

    @Test
    void consumesAndRejectsMalformedStoredPayload() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete(anyString())).thenReturn("not-a-valid-payload");

        assertNull(service.exchange("a".repeat(43)));
        verify(adminService, never()).loadUserByUsername(anyString());
    }
}
