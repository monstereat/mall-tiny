package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.security.util.JwtTokenUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorSamlTokenRevocationServiceTest {
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MonitorSamlTokenRevocationService service = new MonitorSamlTokenRevocationService(redis);

    @Test
    void revokesEarlierTokensForTheSameTenantAndSubject() {
        when(redis.opsForValue()).thenReturn(values);
        Saml2AuthenticatedPrincipal principal = mock(Saml2AuthenticatedPrincipal.class);
        when(principal.getRelyingPartyRegistrationId()).thenReturn("tenant-a");
        when(principal.getName()).thenReturn("alice@example.test");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);

        service.logout(null, null, authentication);

        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        var revokedAt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), revokedAt.capture(), any(java.time.Duration.class));
        long cutoff = Long.parseLong(revokedAt.getValue());
        when(values.get(key.getValue())).thenReturn(revokedAt.getValue());
        assertTrue(service.isRevoked(new JwtTokenUtil.SamlTokenIdentity("tenant-a", "alice@example.test", cutoff - 1)));
        assertFalse(service.isRevoked(new JwtTokenUtil.SamlTokenIdentity("tenant-a", "alice@example.test", cutoff + 1)));
        assertTrue(key.getValue().startsWith("monitor:saml:revoked-before:"));
        verify(redis, atLeastOnce()).opsForValue();
    }

    @Test
    void malformedRevocationMarkerFailsClosed() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("invalid");

        assertTrue(service.isRevoked(new JwtTokenUtil.SamlTokenIdentity("tenant-a", "alice@example.test", 10)));
    }
}
