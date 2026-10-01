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
        MonitorProject project = requireActiveProject(projectKey);
        validateKey(rawIngestKey, project.getIngestKeyHash(), "ingest");
        return project;
    }

    public MonitorProject validateReleaseKey(String projectKey, String rawReleaseKey) {
        MonitorProject project = requireActiveProject(projectKey);
        validateKey(rawReleaseKey, project.getReleaseKeyHash(), "release");
        return project;
    }

    public MonitorProject getActiveProject(String projectKey) {
        String cacheKey = cacheKey(projectKey);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            String[] parts = cached.split("\\|", 3);
            if (parts.length >= 2) {
                MonitorProject project = new MonitorProject();
                project.setId(Long.parseLong(parts[0]));
                project.setProjectKey(projectKey);
                project.setIngestKeyHash(parts[1]);
                project.setReleaseKeyHash(parts.length == 3 ? parts[2] : "");
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
                    project.getId() + "|" + nullToEmpty(project.getIngestKeyHash()) + "|" + nullToEmpty(project.getReleaseKeyHash()),
                    CACHE_TTL
            );
        }
        return project;
    }

    public void evict(String projectKey) {
        redisTemplate.delete(cacheKey(projectKey));
    }

    private MonitorProject requireActiveProject(String projectKey) {
        MonitorProject project = getActiveProject(projectKey);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "monitor project is disabled or missing");
        }
        return project;
    }

    private void validateKey(String rawKey, String expectedHash, String keyType) {
        if (!StringUtils.hasText(rawKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing monitor " + keyType + " key");
        }
        if (!constantTimeEquals(expectedHash, sha256(rawKey))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid monitor " + keyType + " key");
        }
    }

    private String cacheKey(String projectKey) {
        return "monitor:project:auth:" + projectKey;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
