package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDataScrubbingRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorProjectCredentials;
import com.macro.mall.tiny.modules.monitor.dto.MonitorProjectRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MonitorProjectService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final MonitorProjectMapper projectMapper;
    private final MonitorTenantAuditLogMapper auditLogMapper;
    private final MonitorProjectAccessService accessService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public MonitorProjectCredentials create(MonitorProjectRequest request) {
        MonitorProject exists = projectMapper.selectOne(
                Wrappers.<MonitorProject>lambdaQuery()
                        .eq(MonitorProject::getProjectKey, request.getProjectKey())
                        .last("LIMIT 1")
        );
        if (exists != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "project key already exists");
        }

        String ingestKey = generateKey();
        String releaseKey = generateKey();

        MonitorProject project = new MonitorProject();
        com.macro.mall.tiny.modules.monitor.model.MonitorTeam team = accessService.teamForProjectCreation(request.getTeamId());
        project.setTenantId(team.getTenantId());
        project.setTeamId(team.getId());
        project.setName(request.getName());
        project.setProjectKey(request.getProjectKey());
        project.setPlatform(request.getPlatform() == null || request.getPlatform().isBlank() ? "web" : request.getPlatform());
        project.setIngestKeyHash(sha256(ingestKey));
        project.setReleaseKeyHash(sha256(releaseKey));
        project.setStatus(1);
        Long ownerId = accessService.currentAdminId();
        project.setOwnerId(ownerId);
        projectMapper.insert(project);
        accessService.addOwner(project.getId(), ownerId);

        return new MonitorProjectCredentials(project, ingestKey, releaseKey);
    }

    @Transactional
    public MonitorProjectCredentials rotateKeys(String projectKey) {
        MonitorProject project = requireActiveProject(projectKey);
        String ingestKey = generateKey();
        String releaseKey = generateKey();
        project.setIngestKeyHash(sha256(ingestKey));
        project.setReleaseKeyHash(sha256(releaseKey));
        projectMapper.updateById(project);
        evict(projectKey);
        return new MonitorProjectCredentials(project, ingestKey, releaseKey);
    }

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

    @Transactional
    public MonitorProject updateScrubbingSettings(String projectKey, MonitorDataScrubbingRequest request) {
        MonitorProject project = projectMapper.selectOne(
                Wrappers.<MonitorProject>lambdaQuery()
                        .eq(MonitorProject::getProjectKey, projectKey)
                        .eq(MonitorProject::getStatus, 1)
                        .last("LIMIT 1")
        );
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor project not found");
        }
        boolean previousScrubEmails = Boolean.TRUE.equals(project.getScrubEmails());
        boolean previousScrubCreditCards = Boolean.TRUE.equals(project.getScrubCreditCards());
        boolean previousScrubIpAddresses = Boolean.TRUE.equals(project.getScrubIpAddresses());
        boolean previousScrubPhoneNumbers = Boolean.TRUE.equals(project.getScrubPhoneNumbers());
        boolean previousScrubChineseIdNumbers = Boolean.TRUE.equals(project.getScrubChineseIdNumbers());
        List<String> previousCustomSensitiveFields = MonitorSensitiveFieldNames.fromStorage(project.getCustomSensitiveFields());
        List<String> customSensitiveFields;
        try {
            customSensitiveFields = MonitorSensitiveFieldNames.validateAndNormalize(request.getCustomSensitiveFields());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        project.setScrubEmails(request.getScrubEmails());
        project.setScrubCreditCards(request.getScrubCreditCards());
        project.setScrubIpAddresses(request.getScrubIpAddresses());
        project.setScrubPhoneNumbers(request.getScrubPhoneNumbers());
        project.setScrubChineseIdNumbers(request.getScrubChineseIdNumbers());
        project.setCustomSensitiveFields(MonitorSensitiveFieldNames.toStorage(customSensitiveFields));
        projectMapper.updateById(project);
        auditScrubbingSettings(project, previousScrubEmails, previousScrubCreditCards, previousScrubIpAddresses,
                previousScrubPhoneNumbers, previousScrubChineseIdNumbers, previousCustomSensitiveFields);
        evictAfterCommit(projectKey);
        return project;
    }

    private void auditScrubbingSettings(MonitorProject project, boolean previousScrubEmails,
                                        boolean previousScrubCreditCards, boolean previousScrubIpAddresses,
                                        boolean previousScrubPhoneNumbers, boolean previousScrubChineseIdNumbers,
                                        List<String> previousCustomSensitiveFields) {
        MonitorTenantAuditLog log = new MonitorTenantAuditLog();
        log.setTenantId(project.getTenantId());
        log.setActorAdminId(accessService.currentAdminId());
        log.setAction("project.data_scrubbing_updated");
        log.setResourceType("monitor_project");
        log.setResourceId(String.valueOf(project.getId()));
        try {
            log.setDetailJson(objectMapper.writeValueAsString(Map.of(
                    "scrubEmails", Map.of("before", previousScrubEmails, "after", project.getScrubEmails()),
                    "scrubCreditCards", Map.of("before", previousScrubCreditCards, "after", project.getScrubCreditCards()),
                    "scrubIpAddresses", Map.of("before", previousScrubIpAddresses, "after", project.getScrubIpAddresses()),
                    "scrubPhoneNumbers", Map.of("before", previousScrubPhoneNumbers, "after", project.getScrubPhoneNumbers()),
                    "scrubChineseIdNumbers", Map.of("before", previousScrubChineseIdNumbers,
                            "after", project.getScrubChineseIdNumbers()),
                    "customSensitiveFields", Map.of("before", previousCustomSensitiveFields,
                            "after", MonitorSensitiveFieldNames.fromStorage(project.getCustomSensitiveFields()))
            )));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize project data scrubbing audit details", e);
        }
        auditLogMapper.insert(log);
    }

    private void evictAfterCommit(String projectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(projectKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(projectKey);
            }
        });
    }

    public MonitorProject getActiveProject(String projectKey) {
        String cacheKey = cacheKey(projectKey);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            String[] parts = cached.split("\\|", -1);
            if (parts.length >= 2) {
                MonitorProject project = new MonitorProject();
                project.setId(Long.parseLong(parts[0]));
                project.setProjectKey(projectKey);
                project.setIngestKeyHash(parts[1]);
                project.setReleaseKeyHash(parts.length >= 3 ? parts[2] : "");
                project.setScrubEmails(parts.length >= 4 && "1".equals(parts[3]));
                project.setScrubCreditCards(parts.length >= 5 && "1".equals(parts[4]));
                project.setScrubIpAddresses(parts.length >= 6 && "1".equals(parts[5]));
                project.setScrubPhoneNumbers(parts.length >= 7 && "1".equals(parts[6]));
                project.setScrubChineseIdNumbers(parts.length >= 8 && "1".equals(parts[7]));
                project.setCustomSensitiveFields(parts.length >= 9 ? parts[8] : "");
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
                    project.getId() + "|" + nullToEmpty(project.getIngestKeyHash()) + "|" + nullToEmpty(project.getReleaseKeyHash())
                            + "|" + enabled(project.getScrubEmails()) + "|" + enabled(project.getScrubCreditCards())
                            + "|" + enabled(project.getScrubIpAddresses())
                            + "|" + enabled(project.getScrubPhoneNumbers())
                            + "|" + enabled(project.getScrubChineseIdNumbers()) + "|"
                            + nullToEmpty(project.getCustomSensitiveFields()),
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

    private String generateKey() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return "mon_" + HexFormat.of().formatHex(bytes);
    }

    private String cacheKey(String projectKey) {
        return "monitor:project:auth:v2:" + projectKey;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String enabled(Boolean value) {
        return Boolean.TRUE.equals(value) ? "1" : "0";
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
