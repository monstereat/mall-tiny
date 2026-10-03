package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenant;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MonitorSamlAuthenticationSuccessHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private final MonitorTenantSamlService samlService;
    private final MonitorTenantMapper tenantMapper;
    private final MonitorTenantMemberMapper memberMapper;
    private final UmsAdminService adminService;
    private final MonitorSamlLoginCodeService loginCodeService;

    @Value("${monitor.sso.frontend-base-url:http://localhost:18088}")
    private String frontendBaseUrl;

    @Override
    public void onAuthenticationSuccess(jakarta.servlet.http.HttpServletRequest request,
                                       jakarta.servlet.http.HttpServletResponse response,
                                       Authentication authentication) throws IOException {
        if (!(authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal principal)) {
            fail(response);
            return;
        }
        String tenantKey = principal.getRelyingPartyRegistrationId();
        String subject = principal.getName();
        if (!StringUtils.hasText(subject) || subject.length() > 512) { fail(response); return; }
        var config = samlService.enabledConfig(tenantKey);
        if (config == null) { fail(response); return; }
        MonitorTenant tenant = tenantMapper.selectById(config.getTenantId());
        if (tenant == null || tenant.getStatus() != 1) { fail(response); return; }

        Object rawEmail = principal.getFirstAttribute(config.getEmailAttribute());
        String email = rawEmail instanceof String value ? value.trim() : null;
        if (!StringUtils.hasText(email) || email.length() > 256) { fail(response); return; }
        List<UmsAdmin> admins = adminService.list(Wrappers.<UmsAdmin>lambdaQuery()
                .eq(UmsAdmin::getEmail, email).eq(UmsAdmin::getStatus, 1).last("LIMIT 2"));
        if (admins.size() != 1) { fail(response); return; }
        UmsAdmin admin = admins.get(0);
        MonitorTenantMember membership = memberMapper.selectOne(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenant.getId())
                .eq(MonitorTenantMember::getAdminId, admin.getId())
                .last("LIMIT 1"));
        if (membership == null) { fail(response); return; }
        UserDetails user = adminService.loadUserByUsername(admin.getUsername());
        if (!user.isEnabled()) { fail(response); return; }

        String code = loginCodeService.issue(admin.getUsername(), tenantKey, subject);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.sendRedirect(frontendBaseUrl.replaceAll("/$", "") + "/login#ssoCode="
                + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }

    @Override
    public void onAuthenticationFailure(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        fail(response);
    }

    private void fail(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(frontendBaseUrl.replaceAll("/$", "") + "/login#ssoError=1");
    }
}
