package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantRoleRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantRoleView;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantRoleMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MonitorTenantRoleService {
    private final MonitorTenantRoleMapper roleMapper;
    private final MonitorTenantMemberMapper memberMapper;
    private final MonitorTenantAuditLogMapper auditLogMapper;
    private final MonitorTenantAuthorizationService authorization;
    private final ObjectMapper objectMapper;

    public List<MonitorTenantRoleView> list(Long tenantId) {
        authorization.requirePermission(tenantId, "TENANT_MEMBER_READ");
        return roleMapper.selectList(Wrappers.<MonitorTenantRole>lambdaQuery()
                        .eq(MonitorTenantRole::getTenantId, tenantId)
                        .orderByAsc(MonitorTenantRole::getName))
                .stream().map(this::view).toList();
    }

    @Transactional
    public MonitorTenantRoleView create(Long tenantId, MonitorTenantRoleRequest request) {
        var actor = authorization.requireOwner(tenantId);
        MonitorTenantRole role = new MonitorTenantRole();
        role.setTenantId(tenantId);
        role.setRoleKey(request.getRoleKey().trim());
        role.setName(request.getName().trim());
        role.setPermissionsJson(serializePermissions(request.getPermissions()));
        try {
            roleMapper.insert(role);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant role key already exists");
        }
        audit(tenantId, actor.getAdminId(), "tenant_role.created", role,
                Map.of("roleKey", role.getRoleKey(), "name", role.getName(), "permissions", request.getPermissions()));
        return view(role);
    }

    @Transactional
    public MonitorTenantRoleView update(Long tenantId, Long roleId, MonitorTenantRoleRequest request) {
        var actor = authorization.requireOwner(tenantId);
        MonitorTenantRole role = requireRole(tenantId, roleId);
        String oldKey = role.getRoleKey();
        role.setRoleKey(request.getRoleKey().trim());
        role.setName(request.getName().trim());
        role.setPermissionsJson(serializePermissions(request.getPermissions()));
        try {
            roleMapper.updateById(role);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant role key already exists");
        }
        audit(tenantId, actor.getAdminId(), "tenant_role.updated", role,
                Map.of("previousRoleKey", oldKey, "roleKey", role.getRoleKey(), "name", role.getName(),
                        "permissions", request.getPermissions()));
        return view(role);
    }

    @Transactional
    public void delete(Long tenantId, Long roleId) {
        var actor = authorization.requireOwner(tenantId);
        MonitorTenantRole role = requireRole(tenantId, roleId);
        if (memberMapper.selectCount(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenantId)
                .eq(MonitorTenantMember::getCustomRoleId, roleId)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "remove this role from tenant members before deleting it");
        }
        try {
            roleMapper.deleteById(roleId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "remove this role from tenant members before deleting it");
        }
        audit(tenantId, actor.getAdminId(), "tenant_role.deleted", role,
                Map.of("roleKey", role.getRoleKey(), "name", role.getName()));
    }

    public MonitorTenantRole requireAssignableRole(Long tenantId, Long roleId) {
        return requireRole(tenantId, roleId);
    }

    private MonitorTenantRole requireRole(Long tenantId, Long roleId) {
        MonitorTenantRole role = roleMapper.selectOne(Wrappers.<MonitorTenantRole>lambdaQuery()
                .eq(MonitorTenantRole::getTenantId, tenantId)
                .eq(MonitorTenantRole::getId, roleId)
                .last("LIMIT 1"));
        if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant role not found");
        return role;
    }

    private String serializePermissions(List<String> input) {
        List<String> permissions = input.stream().distinct().sorted().toList();
        if (!MonitorTenantAuthorizationService.SUPPORTED_PERMISSIONS.containsAll(permissions)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported tenant role permission");
        }
        try {
            return objectMapper.writeValueAsString(permissions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize tenant role permissions", e);
        }
    }

    private void audit(Long tenantId, Long actorId, String action, MonitorTenantRole role,
                       Map<String, Object> details) {
        MonitorTenantAuditLog log = new MonitorTenantAuditLog();
        log.setTenantId(tenantId);
        log.setActorAdminId(actorId);
        log.setAction(action);
        log.setResourceType("tenant_role");
        log.setResourceId(String.valueOf(role.getId()));
        try {
            log.setDetailJson(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize tenant role audit details", e);
        }
        auditLogMapper.insert(log);
    }

    private MonitorTenantRoleView view(MonitorTenantRole role) {
        return new MonitorTenantRoleView(role.getId(), role.getTenantId(), role.getRoleKey(), role.getName(),
                authorization.permissions(role), role.getCreateTime(), role.getUpdateTime());
    }
}
