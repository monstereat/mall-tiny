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

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            routeMapper, ruleMapper, auditLogMapper, accessService, authorization, new ObjectMapper(),
            new MonitorAlertWebhookClient(new ObjectMapper()));

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
    void hidesWebhookUrlFromTenantMemberWithReadOnlyPermission() {
        MonitorTenantMember readOnly = member(8L, "MEMBER");
        readOnly.setTenantId(3L);
        readOnly.setCustomRoleId(44L);
        MonitorAlertNotificationRoute route = route(18L, "Pager");
        when(authorization.requirePermission(3L, "ALERT_ROUTE_READ")).thenReturn(readOnly);
        when(authorization.hasPermission(readOnly, "ALERT_ROUTE_MANAGE")).thenReturn(false);
        when(routeMapper.selectList(any())).thenReturn(List.of(route));

        var views = service.listForTenant(3L);

        assertEquals(1, views.size());
        assertEquals(18L, views.get(0).id());
        assertEquals("Pager", views.get(0).name());
        assertNull(views.get(0).webhookUrl());
        assertEquals(route.getCreateTime(), views.get(0).createTime());
        assertEquals(route.getUpdateTime(), views.get(0).updateTime());
    }

    @Test
    void keepsWebhookUrlVisibleToMemberWithManagePermission() {
        MonitorTenantMember manager = member(8L, "MEMBER");
        manager.setTenantId(3L);
        manager.setCustomRoleId(45L);
        MonitorAlertNotificationRoute route = route(18L, "Pager");
        when(authorization.requirePermission(3L, "ALERT_ROUTE_READ")).thenReturn(manager);
        when(authorization.hasPermission(manager, "ALERT_ROUTE_MANAGE")).thenReturn(true);
        when(routeMapper.selectList(any())).thenReturn(List.of(route));

        var views = service.listForTenant(3L);

        assertEquals("https://hooks.example.test/alert?token=private", views.get(0).webhookUrl());
    }

    @Test
    void keepsWebhookUrlVisibleToTenantOwner() {
        MonitorTenantMember owner = owner(7L);
        owner.setTenantId(3L);
        when(authorization.requirePermission(3L, "ALERT_ROUTE_READ")).thenReturn(owner);
        when(routeMapper.selectList(any())).thenReturn(List.of(route(18L, "Pager")));

        var views = service.listForTenant(3L);

        assertEquals("https://hooks.example.test/alert?token=private", views.get(0).webhookUrl());
    }

    @Test
    void keepsWebhookUrlInUpdateResponseForRouteManager() {
        MonitorTenantMember manager = member(8L, "MEMBER");
        when(authorization.requirePermission(3L, "ALERT_ROUTE_MANAGE")).thenReturn(manager);
        MonitorAlertNotificationRoute route = route(18L, "Pager");
        when(routeMapper.selectOne(any())).thenReturn(route);

        var updated = service.update(3L, 18L, request("Pager", "https://hooks.example.test/updated?token=private"));

        assertEquals("https://hooks.example.test/updated?token=private", updated.webhookUrl());
    }

    @Test
    void resolvesStoredWebhookUrlForAlertDeliveryRegardlessOfListRedaction() {
        MonitorAlertNotificationRoute route = route(18L, "Pager");
        when(routeMapper.selectOne(any())).thenReturn(route);
        MonitorProject project = new MonitorProject();
        project.setTenantId(3L);

        assertEquals("https://hooks.example.test/alert?token=private", service.resolveUrl(project, 18L));
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

        ResponseStatusException loopback = assertThrows(ResponseStatusException.class,
                () -> service.create(3L, request("Pager", "http://127.0.0.1/metadata")));
        assertEquals(HttpStatus.BAD_REQUEST, loopback.getStatusCode());
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

    private MonitorAlertNotificationRoute route(Long id, String name) {
        MonitorAlertNotificationRoute route = new MonitorAlertNotificationRoute();
        route.setId(id);
        route.setTenantId(3L);
        route.setName(name);
        route.setWebhookUrl("https://hooks.example.test/alert?token=private");
        route.setCreateTime(new Date(1_700_000_000_000L));
        route.setUpdateTime(new Date(1_700_000_001_000L));
        return route;
    }

    private MonitorTenantMember owner(Long adminId) { return member(adminId, "OWNER"); }

    private MonitorTenantMember member(Long adminId, String role) {
        MonitorTenantMember member = new MonitorTenantMember();
        member.setAdminId(adminId);
        member.setRole(role);
        return member;
    }
}
