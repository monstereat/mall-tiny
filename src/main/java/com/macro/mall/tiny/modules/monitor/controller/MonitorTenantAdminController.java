package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantMemberRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantRoleRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantRoleView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantSamlConfigRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantSamlConfigView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertNotificationRouteRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertNotificationRouteView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTeamRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTeamMemberRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorScimTokenCreated;
import com.macro.mall.tiny.modules.monitor.dto.MonitorScimTokenRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorScimTokenView;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenant;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeam;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeamMember;
import com.macro.mall.tiny.modules.monitor.service.MonitorTenantService;
import com.macro.mall.tiny.modules.monitor.service.MonitorTenantRoleService;
import com.macro.mall.tiny.modules.monitor.service.MonitorTenantSamlService;
import com.macro.mall.tiny.modules.monitor.service.MonitorScimCredentialService;
import com.macro.mall.tiny.modules.monitor.service.MonitorAlertNotificationRouteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/monitor/admin/tenants")
@RequiredArgsConstructor
public class MonitorTenantAdminController {

    private final MonitorTenantService tenantService;
    private final MonitorTenantRoleService roleService;
    private final MonitorScimCredentialService scimCredentialService;
    private final MonitorTenantSamlService samlService;
    private final MonitorAlertNotificationRouteService notificationRouteService;

    @GetMapping
    public CommonResult<List<MonitorTenant>> tenants() {
        return CommonResult.success(tenantService.listTenants());
    }

    @PostMapping
    public CommonResult<MonitorTenant> createTenant(@Valid @RequestBody MonitorTenantRequest request) {
        return CommonResult.success(tenantService.createTenant(request));
    }

    @GetMapping("/{tenantId}/teams")
    public CommonResult<List<MonitorTeam>> teams(@PathVariable Long tenantId) {
        return CommonResult.success(tenantService.listTeams(tenantId));
    }

    @GetMapping("/{tenantId}/alert-routes")
    public CommonResult<List<MonitorAlertNotificationRouteView>> alertRoutes(@PathVariable Long tenantId) {
        return CommonResult.success(notificationRouteService.listForTenant(tenantId));
    }

    @PostMapping("/{tenantId}/alert-routes")
    public CommonResult<MonitorAlertNotificationRouteView> createAlertRoute(
            @PathVariable Long tenantId,
            @Valid @RequestBody MonitorAlertNotificationRouteRequest request) {
        return CommonResult.success(notificationRouteService.create(tenantId, request));
    }

    @PutMapping("/{tenantId}/alert-routes/{routeId}")
    public CommonResult<MonitorAlertNotificationRouteView> updateAlertRoute(
            @PathVariable Long tenantId,
            @PathVariable Long routeId,
            @Valid @RequestBody MonitorAlertNotificationRouteRequest request) {
        return CommonResult.success(notificationRouteService.update(tenantId, routeId, request));
    }

    @DeleteMapping("/{tenantId}/alert-routes/{routeId}")
    public CommonResult<Void> deleteAlertRoute(@PathVariable Long tenantId, @PathVariable Long routeId) {
        notificationRouteService.delete(tenantId, routeId);
        return CommonResult.success(null);
    }

    @GetMapping("/{tenantId}/members")
    public CommonResult<List<MonitorTenantMember>> members(@PathVariable Long tenantId) {
        return CommonResult.success(tenantService.listMembers(tenantId));
    }

    @GetMapping("/{tenantId}/roles")
    public CommonResult<List<MonitorTenantRoleView>> roles(@PathVariable Long tenantId) {
        return CommonResult.success(roleService.list(tenantId));
    }

    @PostMapping("/{tenantId}/roles")
    public CommonResult<MonitorTenantRoleView> createRole(
            @PathVariable Long tenantId,
            @Valid @RequestBody MonitorTenantRoleRequest request) {
        return CommonResult.success(roleService.create(tenantId, request));
    }

    @PutMapping("/{tenantId}/roles/{roleId}")
    public CommonResult<MonitorTenantRoleView> updateRole(
            @PathVariable Long tenantId,
            @PathVariable Long roleId,
            @Valid @RequestBody MonitorTenantRoleRequest request) {
        return CommonResult.success(roleService.update(tenantId, roleId, request));
    }

    @DeleteMapping("/{tenantId}/roles/{roleId}")
    public CommonResult<Void> deleteRole(@PathVariable Long tenantId, @PathVariable Long roleId) {
        roleService.delete(tenantId, roleId);
        return CommonResult.success(null);
    }

    @GetMapping("/{tenantId}/audit-logs")
    public CommonResult<List<MonitorTenantAuditLog>> auditLogs(
            @PathVariable Long tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return CommonResult.success(tenantService.listAuditLogs(tenantId, limit));
    }

    @PostMapping("/{tenantId}/teams")
    public CommonResult<MonitorTeam> createTeam(
            @PathVariable Long tenantId,
            @Valid @RequestBody MonitorTeamRequest request) {
        return CommonResult.success(tenantService.createTeam(tenantId, request));
    }

    @GetMapping("/{tenantId}/teams/{teamId}/members")
    public CommonResult<List<MonitorTeamMember>> teamMembers(
            @PathVariable Long tenantId,
            @PathVariable Long teamId) {
        return CommonResult.success(tenantService.listTeamMembers(tenantId, teamId));
    }

    @PutMapping("/{tenantId}/teams/{teamId}/members")
    public CommonResult<MonitorTeamMember> updateTeamMember(
            @PathVariable Long tenantId,
            @PathVariable Long teamId,
            @Valid @RequestBody MonitorTeamMemberRequest request) {
        return CommonResult.success(tenantService.addOrUpdateTeamMember(tenantId, teamId, request));
    }

    @DeleteMapping("/{tenantId}/teams/{teamId}/members/{adminId}")
    public CommonResult<Void> removeTeamMember(
            @PathVariable Long tenantId,
            @PathVariable Long teamId,
            @PathVariable Long adminId) {
        tenantService.removeTeamMember(tenantId, teamId, adminId);
        return CommonResult.success(null);
    }

    @GetMapping("/{tenantId}/scim-tokens")
    public CommonResult<List<MonitorScimTokenView>> scimTokens(@PathVariable Long tenantId) {
        return CommonResult.success(scimCredentialService.listTokens(tenantId));
    }

    @PostMapping("/{tenantId}/scim-tokens")
    public CommonResult<MonitorScimTokenCreated> createScimToken(
            @PathVariable Long tenantId,
            @Valid @RequestBody MonitorScimTokenRequest request) {
        return CommonResult.success(scimCredentialService.createToken(tenantId, request));
    }

    @GetMapping("/{tenantId}/saml")
    public CommonResult<MonitorTenantSamlConfigView> samlConfig(@PathVariable Long tenantId) {
        return CommonResult.success(samlService.getConfig(tenantId));
    }

    @PutMapping("/{tenantId}/saml")
    public CommonResult<MonitorTenantSamlConfigView> updateSamlConfig(
            @PathVariable Long tenantId,
            @Valid @RequestBody MonitorTenantSamlConfigRequest request) {
        return CommonResult.success(samlService.saveConfig(tenantId, request));
    }

    @DeleteMapping("/{tenantId}/scim-tokens/{tokenId}")
    public CommonResult<Void> revokeScimToken(@PathVariable Long tenantId, @PathVariable Long tokenId) {
        scimCredentialService.revokeToken(tenantId, tokenId);
        return CommonResult.success(null);
    }

    @PutMapping("/{tenantId}/members")
    public CommonResult<MonitorTenantMember> updateMember(
            @PathVariable Long tenantId,
            @Valid @RequestBody MonitorTenantMemberRequest request) {
        return CommonResult.success(tenantService.addOrUpdateMember(tenantId, request));
    }

    @DeleteMapping("/{tenantId}/members/{adminId}")
    public CommonResult<Void> removeMember(@PathVariable Long tenantId, @PathVariable Long adminId) {
        tenantService.removeMember(tenantId, adminId);
        return CommonResult.success(null);
    }
}
