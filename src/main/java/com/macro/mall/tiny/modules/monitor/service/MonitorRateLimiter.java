package com.macro.mall.tiny.modules.monitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class MonitorRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${monitor.ingest.rate-limit-per-minute:5000}")
    private long rateLimitPerMinute;

    public boolean tryAcquire(String projectId) {
        long minuteBucket = Instant.now().getEpochSecond() / 60;
        String key = "monitor:ingest:rate:" + projectId + ":" + minuteBucket;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofMinutes(2));
        }
        return count != null && count <= rateLimitPerMinute;
    }
}
