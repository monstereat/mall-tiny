package com.macro.mall.tiny.security.component;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitorSamlSecurityContextRepositoryTest {
    private final MonitorSamlSecurityContextRepository repository = new MonitorSamlSecurityContextRepository();

    @Test
    void sessionPrincipalIsLoadedOnlyOnSamlLogoutEndpoints() {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        var context = SecurityContextHolder.createEmptyContext();
        Saml2AuthenticatedPrincipal principal = mock(Saml2AuthenticatedPrincipal.class);
        var authentication = new TestingAuthenticationToken(principal, null, "ROLE_USER");
        context.setAuthentication(authentication);
        repository.saveContext(context, loginRequest, response);

        MockHttpServletRequest apiRequest = new MockHttpServletRequest();
        apiRequest.setSession(loginRequest.getSession(false));
        assertNull(repository.loadDeferredContext(apiRequest).get().getAuthentication());

        MockHttpServletRequest logoutRequest = new MockHttpServletRequest("POST", "/logout/saml2/slo");
        logoutRequest.setSession(loginRequest.getSession(false));
        assertSame(authentication, repository.loadDeferredContext(logoutRequest).get().getAuthentication());
    }

    @Test
    void nonSamlAuthenticationIsNeverSavedToSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken("alice", null, "ROLE_USER"));

        repository.saveContext(context, request, new MockHttpServletResponse());

        assertNull(request.getSession(false));
    }
}
