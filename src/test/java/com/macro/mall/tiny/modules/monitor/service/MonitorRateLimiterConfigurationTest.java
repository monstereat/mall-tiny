package com.macro.mall.tiny.modules.monitor.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class MonitorRateLimiterConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RateLimiterConfiguration.class);

    @Test
    void usesDefaultLimitWhenEnvironmentVariableIsNotSet() {
        contextRunner.run(context -> {
            MonitorRateLimiter rateLimiter = context.getBean(MonitorRateLimiter.class);
            assertEquals(5000L, ReflectionTestUtils.getField(rateLimiter, "rateLimitPerMinute"));
        });
    }

    @Test
    void parsesExplicitEnvironmentVariableOverride() {
        contextRunner.withPropertyValues("MONITOR_RATE_LIMIT_PER_MINUTE=12000").run(context -> {
            MonitorRateLimiter rateLimiter = context.getBean(MonitorRateLimiter.class);
            assertEquals(12000L, ReflectionTestUtils.getField(rateLimiter, "rateLimitPerMinute"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MonitorRateLimiter.class)
    static class RateLimiterConfiguration {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
