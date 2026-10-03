package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertNotificationRouteRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertNotificationRouteOption;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertNotificationRouteView;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertNotificationRouteMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertNotificationRoute;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MonitorAlertNotificationRouteService {
    private final MonitorAlertNotificationRouteMapper routeMapper;
    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorTenantAuditLogMapper auditLogMapper;
    private final MonitorProjectAccessService accessService;
    private final MonitorTenantAuthorizationService authorization;
    private final ObjectMapper objectMapper;

    public List<MonitorAlertNotificationRouteOption> listForProject(MonitorProject project) {
        return routeMapper.selectList(Wrappers.<MonitorAlertNotificationRoute>lambdaQuery()
                        .eq(MonitorAlertNotificationRoute::getTenantId, project.getTenantId())
                        .orderByAsc(MonitorAlertNotificationRoute::getName))
                .stream().map(route -> new MonitorAlertNotificationRouteOption(route.getId(), route.getName())).toList();
    }

    public List<MonitorAlertNotificationRouteView> listForTenant(Long tenantId) {
        authorization.requirePermission(tenantId, "ALERT_ROUTE_READ");
        return routeMapper.selectList(Wrappers.<MonitorAlertNotificationRoute>lambdaQuery()
                        .eq(MonitorAlertNotificationRoute::getTenantId, tenantId)
                        .orderByAsc(MonitorAlertNotificationRoute::getName))
                .stream().map(this::view).toList();
    }

    @Transactional
    public MonitorAlertNotificationRouteView create(Long tenantId, MonitorAlertNotificationRouteRequest request) {
        authorization.requirePermission(tenantId, "ALERT_ROUTE_MANAGE");
        MonitorAlertNotificationRoute route = new MonitorAlertNotificationRoute();
        route.setTenantId(tenantId);
        route.setName(request.getName().trim());
        route.setWebhookUrl(normalizeUrl(request.getWebhookUrl()));
        try {
            routeMapper.insert(route);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a route with this name already exists");
        }
        audit(tenantId, "alert_route.created", route,
                Map.of("name", route.getName(), "host", URI.create(route.getWebhookUrl()).getHost()));
        return view(route);
    }

    @Transactional
    public MonitorAlertNotificationRouteView update(Long tenantId, Long id,
                                                     MonitorAlertNotificationRouteRequest request) {
        authorization.requirePermission(tenantId, "ALERT_ROUTE_MANAGE");
        MonitorAlertNotificationRoute route = requireRoute(tenantId, id);
        route.setName(request.getName().trim());
        route.setWebhookUrl(normalizeUrl(request.getWebhookUrl()));
        try {
            routeMapper.updateById(route);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a route with this name already exists");
        }
        audit(tenantId, "alert_route.updated", route,
                Map.of("name", route.getName(), "host", URI.create(route.getWebhookUrl()).getHost()));
        return view(route);
    }

    @Transactional
    public void delete(Long tenantId, Long id) {
        authorization.requirePermission(tenantId, "ALERT_ROUTE_MANAGE");
        MonitorAlertNotificationRoute route = requireRoute(tenantId, id);
        if (ruleMapper.selectCount(Wrappers.<MonitorAlertRule>lambdaQuery()
                .eq(MonitorAlertRule::getNotificationRouteId, id)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "remove this route from alert rules before deleting it");
        }
        routeMapper.deleteById(id);
        audit(tenantId, "alert_route.deleted", route, Map.of("name", route.getName()));
    }

    public String resolveUrl(MonitorProject project, Long routeId) {
        if (routeId == null) return null;
        MonitorAlertNotificationRoute route = routeMapper.selectOne(Wrappers.<MonitorAlertNotificationRoute>lambdaQuery()
                .eq(MonitorAlertNotificationRoute::getId, routeId)
                .eq(MonitorAlertNotificationRoute::getTenantId, project.getTenantId())
                .last("LIMIT 1"));
        if (route == null) throw new IllegalStateException("alert notification route no longer exists");
        return route.getWebhookUrl();
    }

    public void validateRoute(MonitorProject project, Long routeId) {
        if (routeId == null) return;
        Long count = routeMapper.selectCount(Wrappers.<MonitorAlertNotificationRoute>lambdaQuery()
                .eq(MonitorAlertNotificationRoute::getId, routeId)
                .eq(MonitorAlertNotificationRoute::getTenantId, project.getTenantId()));
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notification route must belong to the project tenant");
        }
    }

    private MonitorAlertNotificationRoute requireRoute(Long tenantId, Long id) {
        MonitorAlertNotificationRoute route = routeMapper.selectOne(Wrappers.<MonitorAlertNotificationRoute>lambdaQuery()
                .eq(MonitorAlertNotificationRoute::getId, id)
                .eq(MonitorAlertNotificationRoute::getTenantId, tenantId)
                .last("LIMIT 1"));
        if (route == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert notification route not found");
        return route;
    }

    private String normalizeUrl(String value) {
        if (!StringUtils.hasText(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhook URL is required");
        try {
            URI uri = URI.create(value.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || !StringUtils.hasText(uri.getHost()) || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhook URL must be an absolute HTTP or HTTPS URL");
        }
    }

    private void audit(Long tenantId, String action, MonitorAlertNotificationRoute route, Map<String, Object> detail) {
        try {
            MonitorTenantAuditLog log = new MonitorTenantAuditLog();
            log.setTenantId(tenantId);
            log.setActorAdminId(accessService.currentAdminId());
            log.setAction(action);
            log.setResourceType("alert_notification_route");
            log.setResourceId(String.valueOf(route.getId()));
            log.setDetailJson(objectMapper.writeValueAsString(detail));
            auditLogMapper.insert(log);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("alert notification audit serialization failed", e);
        }
    }

    private MonitorAlertNotificationRouteView view(MonitorAlertNotificationRoute route) {
        return new MonitorAlertNotificationRouteView(route.getId(), route.getName(), route.getWebhookUrl(),
                route.getCreateTime(), route.getUpdateTime());
    }
}
