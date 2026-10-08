package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantRoleMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MonitorTenantAuthorizationService {
    public static final Set<String> SUPPORTED_PERMISSIONS = Set.of(
            "TENANT_MEMBER_READ",
            "TENANT_MEMBER_MANAGE",
            "TEAM_MANAGE",
            "AUDIT_READ",
            "SCIM_MANAGE",
            "ALERT_ROUTE_READ",
            "ALERT_ROUTE_MANAGE"
    );

    private final MonitorTenantMemberMapper memberMapper;
    private final MonitorTenantRoleMapper roleMapper;
    private final MonitorProjectAccessService accessService;
    private final ObjectMapper objectMapper;

    public MonitorTenantMember requireMember(Long tenantId) {
        MonitorTenantMember member = findMember(tenantId, accessService.currentAdminId());
        if (member == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant not found");
        return member;
    }

    public MonitorTenantMember requireOwner(Long tenantId) {
        MonitorTenantMember member = requireMember(tenantId);
        if (!"OWNER".equals(member.getRole()) || member.getCustomRoleId() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant owner permission required");
        }
        return member;
    }

    public MonitorTenantMember requirePermission(Long tenantId, String permission) {
        MonitorTenantMember member = requireMember(tenantId);
        if ("OWNER".equals(member.getRole()) && member.getCustomRoleId() == null) return member;
        if (!hasPermission(member, permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant permission required: " + permission);
        }
        return member;
    }

    public boolean hasPermission(MonitorTenantMember member, String permission) {
        if (member == null || member.getCustomRoleId() == null || permission == null) return false;
        MonitorTenantRole role = roleMapper.selectOne(Wrappers.<MonitorTenantRole>lambdaQuery()
                .eq(MonitorTenantRole::getId, member.getCustomRoleId())
                .eq(MonitorTenantRole::getTenantId, member.getTenantId())
                .last("LIMIT 1"));
        if (role == null) return false;
        try {
            List<String> permissions = objectMapper.readValue(role.getPermissionsJson(), new TypeReference<>() {});
            if (permissions == null) return false;
            if ("TENANT_MEMBER_READ".equals(permission) && permissions.contains("TENANT_MEMBER_MANAGE")) return true;
            if ("ALERT_ROUTE_READ".equals(permission) && permissions.contains("ALERT_ROUTE_MANAGE")) return true;
            return permissions.contains(permission);
        } catch (Exception ignored) {
            return false;
        }
    }

    public List<String> permissionsFor(MonitorTenantMember member) {
        if (member == null) return List.of();
        if ("OWNER".equals(member.getRole()) && member.getCustomRoleId() == null) {
            return SUPPORTED_PERMISSIONS.stream().sorted().toList();
        }
        if (member.getCustomRoleId() == null) return List.of();
        MonitorTenantRole role = roleMapper.selectOne(Wrappers.<MonitorTenantRole>lambdaQuery()
                .eq(MonitorTenantRole::getId, member.getCustomRoleId())
                .eq(MonitorTenantRole::getTenantId, member.getTenantId())
                .last("LIMIT 1"));
        return role == null ? List.of() : permissions(role);
    }

    public List<String> permissions(MonitorTenantRole role) {
        try {
            List<String> permissions = objectMapper.readValue(role.getPermissionsJson(), new TypeReference<>() {});
            return permissions == null ? List.of() : permissions;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private MonitorTenantMember findMember(Long tenantId, Long adminId) {
        return memberMapper.selectOne(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenantId)
                .eq(MonitorTenantMember::getAdminId, adminId)
                .last("LIMIT 1"));
    }
}
