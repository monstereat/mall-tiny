package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantSamlConfigMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantSamlConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class MonitorSamlRegistrationRepository implements RelyingPartyRegistrationRepository, Iterable<RelyingPartyRegistration> {
    private final MonitorTenantSamlConfigMapper configMapper;
    private final MonitorSamlSigningCredential signingCredential;
    private final ConcurrentHashMap<String, CachedRegistration> registrations = new ConcurrentHashMap<>();

    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
        if (registrationId == null || !registrationId.matches("[A-Za-z0-9_-]{1,64}")) return null;
        MonitorTenantSamlConfig config = configMapper.selectOne(Wrappers.<MonitorTenantSamlConfig>lambdaQuery()
                .eq(MonitorTenantSamlConfig::getTenantKey, registrationId)
                .eq(MonitorTenantSamlConfig::getEnabled, 1)
                .last("LIMIT 1"));
        if (config == null) {
            registrations.remove(registrationId);
            return null;
        }
        CachedRegistration cached = registrations.get(registrationId);
        if (cached != null && Objects.equals(cached.updateTime(), config.getUpdateTime())) return cached.registration();
        var builder = RelyingPartyRegistrations
                .fromMetadata(new ByteArrayInputStream(config.getMetadataXml().getBytes(StandardCharsets.UTF_8)))
                .registrationId(registrationId)
                .singleLogoutServiceLocation("{baseUrl}/logout/saml2/slo")
                .singleLogoutServiceResponseLocation("{baseUrl}/logout/saml2/slo")
                .singleLogoutServiceBinding(Saml2MessageBinding.POST);
        Saml2X509Credential signing = signingCredential.get();
        if (signing != null) {
            builder.signingX509Credentials(credentials -> credentials.add(signing));
            builder.authnRequestsSigned(true);
        }
        RelyingPartyRegistration registration = builder.build();
        registrations.put(registrationId, new CachedRegistration(config.getUpdateTime(), registration));
        return registration;
    }

    @Override
    public Iterator<RelyingPartyRegistration> iterator() {
        List<String> tenantKeys = configMapper.selectList(Wrappers.<MonitorTenantSamlConfig>lambdaQuery()
                        .eq(MonitorTenantSamlConfig::getEnabled, 1)
                        .select(MonitorTenantSamlConfig::getTenantKey))
                .stream().map(MonitorTenantSamlConfig::getTenantKey).toList();
        return tenantKeys.stream().map(this::findByRegistrationId).filter(java.util.Objects::nonNull).iterator();
    }

    public void clearCache() {
        registrations.clear();
    }

    private record CachedRegistration(java.util.Date updateTime, RelyingPartyRegistration registration) { }
}
