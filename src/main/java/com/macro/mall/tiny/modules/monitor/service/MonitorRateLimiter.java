package com.macro.mall.tiny.modules.monitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 用 Redis 统计每个项目每分钟接收了多少条事件，因此多个 Server 实例共用同一份额度。
 * tryAcquire 返回 false 表示本次会超过配置上限；接入服务会把它转换成 HTTP 429（请求过多）。
 */
@Component
@RequiredArgsConstructor
public class MonitorRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${monitor.ingest.rate-limit-per-minute:5000}")
    private long rateLimitPerMinute;

    public boolean tryAcquire(String projectId) {
        return tryAcquire(projectId, 1L);
    }

    public boolean tryAcquire(String projectId, long permits) {
        if (permits <= 0) {
            return true;
        }
        long minuteBucket = Instant.now().getEpochSecond() / 60;
        String key = "monitor:ingest:rate:" + projectId + ":" + minuteBucket;
        // Redis 会安全地增加计数，即使多个请求同时到达也不会互相覆盖；每分钟用不同计数，并在两分钟后清理。
        Long count = stringRedisTemplate.opsForValue().increment(key, permits);
        if (count != null && count == permits) {
            stringRedisTemplate.expire(key, Duration.ofMinutes(2));
        }
        return count != null && count <= rateLimitPerMinute;
    }
}
