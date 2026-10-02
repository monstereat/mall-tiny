package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertSilenceRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertSilence;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MonitorAlertSilenceService {

    private static final Set<String> SCOPES = Set.of("project", "rule", "issue");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MonitorAlertRuleMapper ruleMapper;

    public MonitorAlertSilence create(MonitorProject project, MonitorAlertSilenceRequest request) {
        String scope = request.getScope().trim().toLowerCase();
        if (!SCOPES.contains(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid silence scope");
        }
        if ("rule".equals(scope)) {
            MonitorAlertRule rule = request.getRuleId() == null ? null : ruleMapper.selectOne(
                    Wrappers.<MonitorAlertRule>lambdaQuery()
                            .eq(MonitorAlertRule::getId, request.getRuleId())
                            .eq(MonitorAlertRule::getProjectId, project.getId())
                            .last("LIMIT 1")
            );
            if (rule == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert rule not found");
            }
        }
        if ("issue".equals(scope) && !StringUtils.hasText(request.getFingerprint())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issue silence requires a fingerprint");
        }

        int duration = Math.max(60, Math.min(2592000,
                request.getDurationSeconds() == null ? 1800 : request.getDurationSeconds()));
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        MonitorAlertSilence silence = new MonitorAlertSilence(
                id, project.getId(), scope, request.getRuleId(), request.getFingerprint(),
                request.getReason(), now, now + duration * 1000L);
        String scopedKey = scopedKey(project.getId(), scope, request.getRuleId(), request.getFingerprint());
        Boolean created = redisTemplate.opsForValue().setIfAbsent(
                scopedKey, id, Duration.ofSeconds(duration));
        if (!Boolean.TRUE.equals(created)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "matching alert silence already exists");
        }

        redisTemplate.opsForValue().set(recordKey(id), serialize(silence), Duration.ofSeconds(duration));
        redisTemplate.opsForZSet().add(indexKey(project.getId()), id, silence.expiresAt());
        return silence;
    }

    public List<MonitorAlertSilence> list(MonitorProject project) {
        long now = System.currentTimeMillis();
        String indexKey = indexKey(project.getId());
        ZSetOperations<String, String> index = redisTemplate.opsForZSet();
        index.removeRangeByScore(indexKey, 0, now);
        Set<String> ids = index.rangeByScore(indexKey, now, Double.POSITIVE_INFINITY);
        List<MonitorAlertSilence> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (String id : ids) {
            String value = redisTemplate.opsForValue().get(recordKey(id));
            if (value != null) {
                result.add(deserialize(value));
            }
        }
        return result;
    }

    public void delete(MonitorProject project, String id) {
        String value = redisTemplate.opsForValue().get(recordKey(id));
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert silence not found");
        }
        MonitorAlertSilence silence = deserialize(value);
        if (!project.getId().equals(silence.projectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert silence not found");
        }
        redisTemplate.delete(scopedKey(silence.projectId(), silence.scope(),
                silence.ruleId(), silence.fingerprint()));
        redisTemplate.delete(recordKey(id));
        redisTemplate.opsForZSet().remove(indexKey(project.getId()), id);
    }

    public boolean isSilenced(Long projectId, Long ruleId, String fingerprint) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(scopedKey(projectId, "project", null, null)))
                || Boolean.TRUE.equals(redisTemplate.hasKey(scopedKey(projectId, "rule", ruleId, null)))
                || (StringUtils.hasText(fingerprint)
                && Boolean.TRUE.equals(redisTemplate.hasKey(scopedKey(projectId, "issue", null, fingerprint))));
    }

    private String scopedKey(Long projectId, String scope, Long ruleId, String fingerprint) {
        return switch (scope) {
            case "project" -> "monitor:alert:silence:project:" + projectId;
            case "rule" -> "monitor:alert:silence:rule:" + projectId + ":" + ruleId;
            case "issue" -> "monitor:alert:silence:issue:" + projectId + ":" + fingerprint;
            default -> throw new IllegalArgumentException("unsupported silence scope");
        };
    }

    private String recordKey(String id) {
        return "monitor:alert:silence:record:" + id;
    }

    private String indexKey(Long projectId) {
        return "monitor:alert:silence:index:" + projectId;
    }

    private String serialize(MonitorAlertSilence silence) {
        try {
            return objectMapper.writeValueAsString(silence);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("alert silence serialization failed", e);
        }
    }

    private MonitorAlertSilence deserialize(String value) {
        try {
            return objectMapper.readValue(value, MonitorAlertSilence.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("alert silence deserialization failed", e);
        }
    }
}
