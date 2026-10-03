package com.macro.mall.tiny.security.component;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class MonitorSamlSecurityContextRepository extends HttpSessionSecurityContextRepository {
    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("/logout".equals(path) || "/logout/saml2/slo".equals(path)) {
            return super.loadDeferredContext(request);
        }
        return new DeferredSecurityContext() {
            private final SecurityContext context = SecurityContextHolder.createEmptyContext();

            @Override
            public SecurityContext get() {
                return context;
            }

            @Override
            public boolean isGenerated() {
                return true;
            }
        };
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = context.getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal) {
            super.saveContext(context, request, response);
        }
    }
}
