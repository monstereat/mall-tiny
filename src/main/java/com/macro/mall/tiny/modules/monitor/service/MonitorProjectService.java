package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class MonitorProjectService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final MonitorProjectMapper projectMapper;
    private final StringRedisTemplate redisTemplate;

    public MonitorProject validateIngestKey(String projectKey, String rawIngestKey) {
        if (!StringUtils.hasText(rawIngestKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing monitor ingest key");
        }
        MonitorProject project = getActiveProject(projectKey);
        if (project == null || !constantTimeEquals(project.getIngestKeyHash(), sha256(rawIngestKey))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid monitor project or ingest key");
        }
        return project;
    }

    public MonitorProject getActiveProject(String projectKey) {
        String cacheKey = cacheKey(projectKey);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            String[] parts = cached.split("\\|", 2);
            if (parts.length == 2) {
                MonitorProject project = new MonitorProject();
                project.setId(Long.parseLong(parts[0]));
                project.setProjectKey(projectKey);
                project.setIngestKeyHash(parts[1]);
                project.setStatus(1);
                return project;
            }
        }

        MonitorProject project = projectMapper.selectOne(
                Wrappers.<MonitorProject>lambdaQuery()
                        .eq(MonitorProject::getProjectKey, projectKey)
                        .eq(MonitorProject::getStatus, 1)
                        .last("LIMIT 1")
        );
        if (project != null) {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    project.getId() + "|" + project.getIngestKeyHash(),
                    CACHE_TTL
            );
        }
        return project;
    }

    public void evict(String projectKey) {
        redisTemplate.delete(cacheKey(projectKey));
    }

    private String cacheKey(String projectKey) {
        return "monitor:project:auth:" + projectKey;
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String sha256(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
