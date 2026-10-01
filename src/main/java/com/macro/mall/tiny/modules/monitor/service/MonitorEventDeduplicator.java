package com.macro.mall.tiny.modules.monitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class MonitorEventDeduplicator {

    private static final Duration DEDUP_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redisTemplate;

    public boolean reserve(String eventId) {
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent("monitor:event:processed:" + eventId, "1", DEDUP_TTL);
        return Boolean.TRUE.equals(first);
    }

    public void release(String eventId) {
        redisTemplate.delete("monitor:event:processed:" + eventId);
    }
}
