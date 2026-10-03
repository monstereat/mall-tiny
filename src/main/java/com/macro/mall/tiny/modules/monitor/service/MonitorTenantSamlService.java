package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantSamlConfigRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantSamlConfigView;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantSamlConfigMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenant;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantSamlConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class MonitorTenantSamlService {
    private final MonitorTenantSamlConfigMapper configMapper;
    private final MonitorTenantMapper tenantMapper;
    private final MonitorTenantMemberMapper memberMapper;
    private final MonitorTenantAuditLogMapper auditLogMapper;
    private final MonitorProjectAccessService accessService;
    private final MonitorSamlRegistrationRepository registrationRepository;
    private final ObjectMapper objectMapper;

    public MonitorTenantSamlConfigView getConfig(Long tenantId) {
        requireOwner(tenantId);
        MonitorTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant not found");
        MonitorTenantSamlConfig config = configMapper.selectById(tenantId);
        return config == null ? emptyView(tenant) : view(config);
    }

    @Transactional
    public MonitorTenantSamlConfigView saveConfig(Long tenantId, MonitorTenantSamlConfigRequest request) {
        Long actorId = requireOwner(tenantId);
        MonitorTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant not found");
        if (!tenant.getTenantKey().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenant key must use letters, digits, hyphens, or underscores for SAML URLs");
        }
        String emailAttribute = request.getEmailAttribute().trim();
        if (!emailAttribute.matches("[A-Za-z0-9_.:/-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email attribute name is invalid");
        }
        String metadata = request.getMetadataXml().trim();
        if (!metadata.startsWith("<")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IdP metadata must be XML");
        try {
            RelyingPartyRegistrations.fromMetadata(new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8)))
                    .registrationId(tenant.getTenantKey()).build();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IdP metadata is invalid or has no SAML SSO endpoint", e);
        }

        MonitorTenantSamlConfig config = configMapper.selectById(tenantId);
        boolean created = config == null;
        if (created) {
            config = new MonitorTenantSamlConfig();
            config.setTenantId(tenantId);
            config.setTenantKey(tenant.getTenantKey());
        }
        config.setEnabled(request.isEnabled() ? 1 : 0);
        config.setMetadataXml(metadata);
        config.setEmailAttribute(emailAttribute);
        if (created) configMapper.insert(config);
        else configMapper.updateById(config);
        registrationRepository.clearCache();
        audit(tenantId, actorId, created ? "saml.config_created" : "saml.config_updated",
                tenant.getTenantKey(), Map.of("enabled", request.isEnabled(), "emailAttribute", emailAttribute));
        return view(config);
    }

    public MonitorTenantSamlConfig enabledConfig(String tenantKey) {
        return configMapper.selectOne(Wrappers.<MonitorTenantSamlConfig>lambdaQuery()
                .eq(MonitorTenantSamlConfig::getTenantKey, tenantKey)
                .eq(MonitorTenantSamlConfig::getEnabled, 1)
                .last("LIMIT 1"));
    }

    private Long requireOwner(Long tenantId) {
        Long actorId = accessService.currentAdminId();
        MonitorTenantMember member = memberMapper.selectOne(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenantId)
                .eq(MonitorTenantMember::getAdminId, actorId)
                .last("LIMIT 1"));
        if (member == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant not found");
        if (!Set.of("OWNER").contains(member.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant owner permission required");
        }
        return actorId;
    }

    private MonitorTenantSamlConfigView emptyView(MonitorTenant tenant) {
        MonitorTenantSamlConfigView view = new MonitorTenantSamlConfigView();
        view.setTenantId(tenant.getId());
        view.setTenantKey(tenant.getTenantKey());
        view.setEnabled(false);
        view.setEmailAttribute("email");
        view.setLoginUrl("/saml2/authenticate/" + tenant.getTenantKey());
        view.setMetadataUrl("/saml2/metadata/" + tenant.getTenantKey());
        view.setLogoutUrl("/logout/saml2/slo");
        return view;
    }

    private MonitorTenantSamlConfigView view(MonitorTenantSamlConfig config) {
        MonitorTenantSamlConfigView view = new MonitorTenantSamlConfigView();
        view.setTenantId(config.getTenantId());
        view.setTenantKey(config.getTenantKey());
        view.setEnabled(config.getEnabled() == 1);
        view.setMetadataXml(config.getMetadataXml());
        view.setEmailAttribute(config.getEmailAttribute());
        view.setUpdateTime(config.getUpdateTime());
        view.setLoginUrl("/saml2/authenticate/" + config.getTenantKey());
        view.setMetadataUrl("/saml2/metadata/" + config.getTenantKey());
        view.setLogoutUrl("/logout/saml2/slo");
        return view;
    }

    private void audit(Long tenantId, Long actorId, String action, String resourceId, Map<String, Object> details) {
        MonitorTenantAuditLog entry = new MonitorTenantAuditLog();
        entry.setTenantId(tenantId);
        entry.setActorAdminId(actorId);
        entry.setAction(action);
        entry.setResourceType("saml_config");
        entry.setResourceId(resourceId);
        try { entry.setDetailJson(objectMapper.writeValueAsString(details)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not serialize SAML audit details", e); }
        auditLogMapper.insert(entry);
    }
}
