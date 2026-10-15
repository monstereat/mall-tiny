package com.macro.mall.tiny.modules.monitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 防止后台重复处理同一条事件。
 * Kafka 为了不丢数据，失败时可能再次投递同一编号；reserve 会在 Redis 记下“已开始处理”，让重复消息跳过。
 * 处理失败时调用方用 release 删除这条记录以便重试；两天后记录自动过期，清理异常退出留下的旧记录。
 */
@Component
@RequiredArgsConstructor
public class MonitorEventDeduplicator {

    private static final Duration DEDUP_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redisTemplate;

    public boolean reserve(String eventId) {
        // setIfAbsent 会一次性完成“没有记录才写入”；因此同一编号只有第一个消费者能拿到 true。
        // 记录有过期时间，避免进程异常退出后一直挡住重试；正常失败时后台会主动删除记录。
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent("monitor:event:processed:" + eventId, "1", DEDUP_TTL);
        return Boolean.TRUE.equals(first);
    }

    public void release(String eventId) {
        redisTemplate.delete("monitor:event:processed:" + eventId);
    }
}
