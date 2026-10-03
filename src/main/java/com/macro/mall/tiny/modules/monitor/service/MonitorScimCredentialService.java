package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorScimTokenCreated;
import com.macro.mall.tiny.modules.monitor.dto.MonitorScimTokenRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorScimTokenView;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorScimTokenMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorScimToken;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MonitorScimCredentialService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE_URL = "/scim/v2";

    private final MonitorScimTokenMapper tokenMapper;
    private final MonitorTenantAuditLogMapper auditLogMapper;
    private final MonitorProjectAccessService accessService;
    private final MonitorTenantAuthorizationService authorization;
    private final ObjectMapper objectMapper;

    public List<MonitorScimTokenView> listTokens(Long tenantId) {
        authorization.requirePermission(tenantId, "SCIM_MANAGE");
        return tokenMapper.selectList(Wrappers.<MonitorScimToken>lambdaQuery()
                        .eq(MonitorScimToken::getTenantId, tenantId)
                        .orderByAsc(MonitorScimToken::getId))
                .stream().map(this::view).toList();
    }

    @Transactional
    public MonitorScimTokenCreated createToken(Long tenantId, MonitorScimTokenRequest request) {
        Long actorId = authorization.requirePermission(tenantId, "SCIM_MANAGE").getAdminId();
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = "scim_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        MonitorScimToken token = new MonitorScimToken();
        token.setTenantId(tenantId);
        token.setName(request.getName().trim());
        token.setTokenHash(hash(secret));
        token.setCreatedBy(actorId);
        token.setCreateTime(new Date());
        tokenMapper.insert(token);
        audit(tenantId, actorId, "scim.token_created", token.getId(), Map.of("name", token.getName()));
        return new MonitorScimTokenCreated(view(token), secret, BASE_URL);
    }

    @Transactional
    public void revokeToken(Long tenantId, Long tokenId) {
        Long actorId = authorization.requirePermission(tenantId, "SCIM_MANAGE").getAdminId();
        MonitorScimToken token = tokenMapper.selectOne(Wrappers.<MonitorScimToken>lambdaQuery()
                .eq(MonitorScimToken::getId, tokenId)
                .eq(MonitorScimToken::getTenantId, tenantId)
                .last("LIMIT 1"));
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SCIM token not found");
        }
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(new Date());
            tokenMapper.updateById(token);
            audit(tenantId, actorId, "scim.token_revoked", tokenId, Map.of("name", token.getName()));
        }
    }

    public MonitorScimContext authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SCIM bearer token required");
        }
        String secret = authorization.substring(7).trim();
        if (secret.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SCIM bearer token required");
        }
        MonitorScimToken token = tokenMapper.selectOne(Wrappers.<MonitorScimToken>lambdaQuery()
                .eq(MonitorScimToken::getTokenHash, hash(secret))
                .isNull(MonitorScimToken::getRevokedAt)
                .last("LIMIT 1"));
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid SCIM bearer token");
        }
        tokenMapper.touchLastUsed(token.getId());
        return new MonitorScimContext(token.getTenantId(), token.getCreatedBy(), token.getId());
    }

    private MonitorScimTokenView view(MonitorScimToken token) {
        MonitorScimTokenView view = new MonitorScimTokenView();
        view.setId(token.getId());
        view.setTenantId(token.getTenantId());
        view.setName(token.getName());
        view.setCreateTime(token.getCreateTime());
        view.setLastUsedAt(token.getLastUsedAt());
        view.setRevokedAt(token.getRevokedAt());
        return view;
    }

    private void audit(Long tenantId, Long actorId, String action, Long tokenId, Map<String, Object> details) {
        MonitorTenantAuditLog log = new MonitorTenantAuditLog();
        log.setTenantId(tenantId);
        log.setActorAdminId(actorId);
        log.setAction(action);
        log.setResourceType("scim_token");
        log.setResourceId(Long.toString(tokenId));
        try {
            log.setDetailJson(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize SCIM audit details", e);
        }
        auditLogMapper.insert(log);
    }

    private String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
