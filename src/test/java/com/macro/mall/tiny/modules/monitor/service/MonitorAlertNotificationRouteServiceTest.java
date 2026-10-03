package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertNotificationRouteRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertNotificationRouteMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertNotificationRoute;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorAlertNotificationRouteServiceTest {
    private final MonitorAlertNotificationRouteMapper routeMapper = mock(MonitorAlertNotificationRouteMapper.class);
    private final MonitorAlertRuleMapper ruleMapper = mock(MonitorAlertRuleMapper.class);
    private final MonitorTenantAuditLogMapper auditLogMapper = mock(MonitorTenantAuditLogMapper.class);
    private final MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
    private final MonitorTenantAuthorizationService authorization = mock(MonitorTenantAuthorizationService.class);
    private final MonitorAlertNotificationRouteService service = new MonitorAlertNotificationRouteService(
            routeMapper, ruleMapper, auditLogMapper, accessService, authorization, new ObjectMapper());

    @Test
    void createsOwnerRouteAndExcludesWebhookSecretFromAudit() throws Exception {
        when(accessService.currentAdminId()).thenReturn(7L);
        when(authorization.requirePermission(3L, "ALERT_ROUTE_MANAGE")).thenReturn(owner(7L));
        when(routeMapper.insert(any(MonitorAlertNotificationRoute.class))).thenAnswer(invocation -> {
            ((MonitorAlertNotificationRoute) invocation.getArgument(0)).setId(18L);
            return 1;
        });
        MonitorAlertNotificationRouteRequest request = request("Pager", "https://hooks.example.test/alert?token=private");

        var view = service.create(3L, request);

        assertEquals(18L, view.id());
        assertEquals("https://hooks.example.test/alert?token=private", view.webhookUrl());
        var audit = org.mockito.ArgumentCaptor.forClass(com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog.class);
        verify(auditLogMapper).insert(audit.capture());
        assertEquals("alert_route.created", audit.getValue().getAction());
        assertFalse(audit.getValue().getDetailJson().contains("private"));
        assertEquals("hooks.example.test", new ObjectMapper().readTree(audit.getValue().getDetailJson()).get("host").asText());
    }

    @Test
    void requiresTenantOwnerAndAbsoluteHttpUrl() {
        when(authorization.requirePermission(3L, "ALERT_ROUTE_MANAGE"))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN));
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> service.create(3L, request("Pager", "https://hooks.example.test/alert")));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        verifyNoInteractions(routeMapper);

        doReturn(owner(9L)).when(authorization).requirePermission(3L, "ALERT_ROUTE_MANAGE");
        ResponseStatusException invalid = assertThrows(ResponseStatusException.class,
                () -> service.create(3L, request("Pager", "javascript:alert(1)")));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        verifyNoInteractions(routeMapper);
    }

    @Test
    void rejectsRoutesOutsideProjectTenant() {
        when(routeMapper.selectCount(any())).thenReturn(0L);
        MonitorProject project = new MonitorProject();
        project.setTenantId(4L);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.validateRoute(project, 18L));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void preventsDeletingRouteUsedByAnyAlertRule() {
        when(authorization.requirePermission(3L, "ALERT_ROUTE_MANAGE")).thenReturn(owner(7L));
        MonitorAlertNotificationRoute route = new MonitorAlertNotificationRoute();
        route.setId(18L);
        route.setTenantId(3L);
        route.setName("Pager");
        when(routeMapper.selectOne(any())).thenReturn(route);
        when(ruleMapper.selectCount(any())).thenReturn(1L);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.delete(3L, 18L));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(routeMapper, org.mockito.Mockito.never()).deleteById(18L);
    }

    private MonitorAlertNotificationRouteRequest request(String name, String webhookUrl) {
        MonitorAlertNotificationRouteRequest request = new MonitorAlertNotificationRouteRequest();
        request.setName(name);
        request.setWebhookUrl(webhookUrl);
        return request;
    }

    private MonitorTenantMember owner(Long adminId) { return member(adminId, "OWNER"); }

    private MonitorTenantMember member(Long adminId, String role) {
        MonitorTenantMember member = new MonitorTenantMember();
        member.setAdminId(adminId);
        member.setRole(role);
        return member;
    }
}
