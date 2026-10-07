package com.macro.mall.tiny.security.util;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenUtilTest {
    @Test
    void disabledAccountCannotUseAnUnexpiredToken() {
        JwtTokenUtil tokenUtil = new JwtTokenUtil();
        ReflectionTestUtils.setField(tokenUtil, "secret", "test-signing-secret-with-sufficient-length");
        ReflectionTestUtils.setField(tokenUtil, "expiration", 60L);
        ReflectionTestUtils.setField(tokenUtil, "tokenHead", "Bearer ");

        UserDetails enabled = user(true);
        String token = tokenUtil.generateToken(enabled);

        assertTrue(tokenUtil.validateToken(token, enabled));
        assertFalse(tokenUtil.validateToken(token, user(false)));
    }

    private UserDetails user(boolean enabled) {
        User.UserBuilder builder = User.withUsername("scim-managed-user")
                .password("encoded-password")
                .authorities("ROLE_USER");
        return enabled ? builder.build() : builder.disabled(true).build();
    }
}
