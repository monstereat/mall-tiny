package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Date;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MonitorIssueService {

    private static final Duration USER_CARDINALITY_TTL = Duration.ofDays(90);
    private static final Duration ISSUE_EVENT_DEDUP_TTL = Duration.ofDays(7);

    private final MonitorIssueMapper issueMapper;
    private final StringRedisTemplate redisTemplate;

    public void aggregate(MonitorProject project, MonitorEventEnvelope event, String fingerprint) {
        String processedKey = "monitor:issue:aggregated:" + event.getEventId();
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(processedKey, "1", ISSUE_EVENT_DEDUP_TTL);
        if (!Boolean.TRUE.equals(first)) {
            return;
        }

        try {
            long affectedUsers = updateAffectedUsers(project.getId(), fingerprint, event.getUserId());
            issueMapper.upsert(
                    project.getId(),
                    fingerprint,
                    resolveTitle(event.getData()),
                    affectedUsers,
                    new Date(event.getTimestamp()),
                    event.getRelease()
            );
        } catch (RuntimeException ex) {
            redisTemplate.delete(processedKey);
            throw ex;
        }
    }

    private long updateAffectedUsers(Long projectId, String fingerprint, String userId) {
        String key = "monitor:issue:users:" + projectId + ":" + fingerprint;
        if (StringUtils.hasText(userId)) {
            redisTemplate.opsForHyperLogLog().add(key, userId);
            redisTemplate.expire(key, USER_CARDINALITY_TTL);
        }
        Long size = redisTemplate.opsForHyperLogLog().size(key);
        return size == null ? 0L : size;
    }

    private String resolveTitle(Map<String, Object> data) {
        if (data == null) {
            return "Unknown frontend error";
        }
        Object message = data.get("message");
        if (message != null && StringUtils.hasText(String.valueOf(message))) {
            return truncate(String.valueOf(message), 512);
        }
        Object name = data.get("name");
        return name == null ? "Unknown frontend error" : truncate(String.valueOf(name), 512);
    }

    private String truncate(String source, int maxLength) {
        return source.length() <= maxLength ? source : source.substring(0, maxLength);
    }
}
