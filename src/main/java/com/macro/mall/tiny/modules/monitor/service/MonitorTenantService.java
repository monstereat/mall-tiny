package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantMemberRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTeamRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTeamMemberRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantRoleMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenant;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantRole;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorProjectMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeam;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeamMember;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MonitorTenantService {

    private final MonitorTenantMapper tenantMapper;
    private final MonitorTenantAuditLogMapper auditLogMapper;
    private final MonitorTenantMemberMapper memberMapper;
    private final MonitorProjectMapper projectMapper;
    private final MonitorProjectMemberMapper projectMemberMapper;
    private final MonitorTeamMapper teamMapper;
    private final MonitorTeamMemberMapper teamMemberMapper;
    private final MonitorTenantRoleMapper roleMapper;
    private final MonitorTenantAuthorizationService authorization;
    private final MonitorProjectAccessService accessService;
    private final UmsAdminService adminService;
    private final ObjectMapper objectMapper;

    public List<MonitorTenant> listTenants() {
        Long adminId = accessService.currentAdminId();
        List<MonitorTenantMember> memberships = memberMapper.selectList(
                Wrappers.<MonitorTenantMember>lambdaQuery().eq(MonitorTenantMember::getAdminId, adminId)
        );
        if (memberships.isEmpty()) {
            return List.of();
        }
        return tenantMapper.selectList(Wrappers.<MonitorTenant>lambdaQuery()
                .in(MonitorTenant::getId, memberships.stream().map(MonitorTenantMember::getTenantId).distinct().toList())
                .eq(MonitorTenant::getStatus, 1)
                .orderByAsc(MonitorTenant::getId));
    }

    @Transactional
    public MonitorTenant createTenant(MonitorTenantRequest request) {
        MonitorTenant existing = tenantMapper.selectOne(Wrappers.<MonitorTenant>lambdaQuery()
                .eq(MonitorTenant::getTenantKey, request.getTenantKey()).last("LIMIT 1"));
        if (existing != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant key already exists");
        }
        MonitorTenant tenant = new MonitorTenant();
        tenant.setName(request.getName());
        tenant.setTenantKey(request.getTenantKey());
        tenant.setStatus(1);
        tenantMapper.insert(tenant);

        Long actorAdminId = accessService.currentAdminId();
        MonitorTenantMember membership = new MonitorTenantMember();
        membership.setTenantId(tenant.getId());
        membership.setAdminId(actorAdminId);
        membership.setRole("OWNER");
        memberMapper.insert(membership);

        MonitorTeam defaultTeam = new MonitorTeam();
        defaultTeam.setTenantId(tenant.getId());
        defaultTeam.setName("Default Team");
        defaultTeam.setTeamKey("default");
        defaultTeam.setIsDefault(1);
        teamMapper.insert(defaultTeam);
        audit(tenant.getId(), actorAdminId, "tenant.created", "tenant", tenant.getId(),
                Map.of("name", tenant.getName(), "tenantKey", tenant.getTenantKey()));
        return tenant;
    }

    public List<MonitorTeam> listTeams(Long tenantId) {
        requireTenantMember(tenantId);
        return teamMapper.selectList(Wrappers.<MonitorTeam>lambdaQuery()
                .eq(MonitorTeam::getTenantId, tenantId)
                .orderByAsc(MonitorTeam::getId));
    }

    public List<MonitorTenantMember> listMembers(Long tenantId) {
        requireTenantPermission(tenantId, "TENANT_MEMBER_READ");
        return memberMapper.selectList(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenantId)
                .orderByAsc(MonitorTenantMember::getId));
    }

    @Transactional
    public MonitorTeam createTeam(Long tenantId, MonitorTeamRequest request) {
        requireTenantPermission(tenantId, "TEAM_MANAGE");
        MonitorTeam existing = teamMapper.selectOne(Wrappers.<MonitorTeam>lambdaQuery()
                .eq(MonitorTeam::getTenantId, tenantId)
                .eq(MonitorTeam::getTeamKey, request.getTeamKey())
                .last("LIMIT 1"));
        if (existing != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "team key already exists in tenant");
        }
        MonitorTeam team = new MonitorTeam();
        team.setTenantId(tenantId);
        team.setName(request.getName());
        team.setTeamKey(request.getTeamKey());
        team.setIsDefault(0);
        teamMapper.insert(team);
        audit(tenantId, accessService.currentAdminId(), "team.created", "team", team.getId(),
                Map.of("name", team.getName(), "teamKey", team.getTeamKey()));
        return team;
    }

    public List<MonitorTeamMember> listTeamMembers(Long tenantId, Long teamId) {
        requireTenantPermission(tenantId, "TEAM_MANAGE");
        requireTeam(tenantId, teamId);
        return teamMemberMapper.selectList(Wrappers.<MonitorTeamMember>lambdaQuery()
                .eq(MonitorTeamMember::getTeamId, teamId)
                .orderByAsc(MonitorTeamMember::getId));
    }

    @Transactional
    public MonitorTeamMember addOrUpdateTeamMember(Long tenantId, Long teamId, MonitorTeamMemberRequest request) {
        requireTenantPermission(tenantId, "TEAM_MANAGE");
        requireTeam(tenantId, teamId);
        UmsAdmin target = adminService.getById(request.getAdminId());
        if (target == null || target.getStatus() == null || target.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "active admin account not found");
        }
        if (memberMapper.selectOne(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenantId)
                .eq(MonitorTenantMember::getAdminId, request.getAdminId())
                .last("LIMIT 1")) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "admin must first be added to the tenant");
        }
        List<UmsResource> resources = adminService.getResourceList(target.getId());
        boolean hasMonitorResource = resources != null && resources.stream()
                .map(UmsResource::getUrl)
                .anyMatch("/monitor/admin/**"::equals);
        if (!hasMonitorResource) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "admin must first receive the mall-tiny monitor admin RBAC resource");
        }
        MonitorTeamMember member = teamMemberMapper.selectOne(Wrappers.<MonitorTeamMember>lambdaQuery()
                .eq(MonitorTeamMember::getTeamId, teamId)
                .eq(MonitorTeamMember::getAdminId, request.getAdminId())
                .last("LIMIT 1"));
        String previousRole = member == null ? null : member.getRole();
        if (member == null) {
            member = new MonitorTeamMember();
            member.setTenantId(tenantId);
            member.setTeamId(teamId);
            member.setAdminId(request.getAdminId());
            member.setRole(request.getRole());
            teamMemberMapper.insert(member);
        } else {
            member.setRole(request.getRole());
            teamMemberMapper.updateById(member);
        }
        audit(tenantId, accessService.currentAdminId(),
                previousRole == null ? "team.member_added" : "team.member_role_changed", "team_member",
                request.getAdminId(), Map.of("teamId", teamId,
                        "previousRole", previousRole == null ? "" : previousRole, "role", request.getRole()));
        return member;
    }

    @Transactional
    public void removeTeamMember(Long tenantId, Long teamId, Long adminId) {
        requireTenantPermission(tenantId, "TEAM_MANAGE");
        requireTeam(tenantId, teamId);
        MonitorTeamMember member = teamMemberMapper.selectOne(Wrappers.<MonitorTeamMember>lambdaQuery()
                .eq(MonitorTeamMember::getTeamId, teamId)
                .eq(MonitorTeamMember::getAdminId, adminId)
                .last("LIMIT 1"));
        if (member == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "team member not found");
        }
        teamMemberMapper.deleteById(member.getId());
        audit(tenantId, accessService.currentAdminId(), "team.member_removed", "team_member", adminId,
                Map.of("teamId", teamId, "previousRole", member.getRole()));
    }

    @Transactional
    public MonitorTenantMember addOrUpdateMember(Long tenantId, MonitorTenantMemberRequest request) {
        lockTenantMembershipChanges(tenantId);
        MonitorTenantMember actor = requireTenantPermission(tenantId, "TENANT_MEMBER_MANAGE");
        boolean actorIsOwner = "OWNER".equals(actor.getRole()) && actor.getCustomRoleId() == null;
        MonitorTenantRole customRole = null;
        if (request.getCustomRoleId() != null) {
            if (!"MEMBER".equals(request.getRole())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "custom tenant roles must use the MEMBER project access level");
            }
            customRole = roleMapper.selectOne(Wrappers.<MonitorTenantRole>lambdaQuery()
                    .eq(MonitorTenantRole::getTenantId, tenantId)
                    .eq(MonitorTenantRole::getId, request.getCustomRoleId())
                    .last("LIMIT 1"));
            if (customRole == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant role not found");
            if (!actorIsOwner && !authorization.permissionsFor(actor).containsAll(authorization.permissions(customRole))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "cannot assign a tenant role with permissions you do not have");
            }
        } else if (!actorIsOwner && !"MEMBER".equals(request.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "delegated tenant member managers may only assign the MEMBER access level");
        }
        UmsAdmin target = adminService.getById(request.getAdminId());
        if (target == null || target.getStatus() == null || target.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "active admin account not found");
        }
        List<UmsResource> resources = adminService.getResourceList(target.getId());
        boolean hasMonitorResource = resources != null && resources.stream()
                .map(UmsResource::getUrl)
                .anyMatch("/monitor/admin/**"::equals);
        if (!hasMonitorResource) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "admin must first receive the mall-tiny monitor admin RBAC resource");
        }
        MonitorTenantMember member = memberMapper.selectOne(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenantId)
                .eq(MonitorTenantMember::getAdminId, request.getAdminId())
                .last("LIMIT 1"));
        String previousRole = member == null ? null : member.getRole();
        if ("OWNER".equals(previousRole) && !actorIsOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a tenant owner may change an owner");
        }
        if ("OWNER".equals(request.getRole()) && (customRole != null || !actorIsOwner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a tenant owner may assign the owner role");
        }
        boolean remainsOwner = "OWNER".equals(request.getRole()) && customRole == null;
        if ("OWNER".equals(previousRole) && !remainsOwner && ownerCount(tenantId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant must retain at least one owner");
        }
        if (member == null) {
            member = new MonitorTenantMember();
            member.setTenantId(tenantId);
            member.setAdminId(request.getAdminId());
            member.setRole(request.getRole());
            member.setCustomRoleId(customRole == null ? null : customRole.getId());
            memberMapper.insert(member);
        } else {
            member.setRole(request.getRole());
            member.setCustomRoleId(customRole == null ? null : customRole.getId());
            memberMapper.updateById(member);
        }
        audit(tenantId, accessService.currentAdminId(),
                previousRole == null ? "member.added" : "member.role_changed", "member", request.getAdminId(),
                Map.of("previousRole", previousRole == null ? "" : previousRole, "role", request.getRole(),
                        "customRoleId", customRole == null ? "" : customRole.getId()));
        return member;
    }

    @Transactional
    public void removeMember(Long tenantId, Long adminId) {
        lockTenantMembershipChanges(tenantId);
        MonitorTenantMember actor = requireTenantPermission(tenantId, "TENANT_MEMBER_MANAGE");
        MonitorTenantMember member = memberMapper.selectOne(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenantId)
                .eq(MonitorTenantMember::getAdminId, adminId)
                .last("LIMIT 1"));
        if (member == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant member not found");
        }
        if ("OWNER".equals(member.getRole()) && !"OWNER".equals(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a tenant owner may remove an owner");
        }
        if ("OWNER".equals(member.getRole()) && ownerCount(tenantId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant must retain at least one owner");
        }
        List<Long> projectIds = tenantProjectIds(tenantId);
        if (!projectIds.isEmpty()) {
            List<MonitorProjectMember> projectOwners = projectMemberMapper.selectList(
                    Wrappers.<MonitorProjectMember>lambdaQuery()
                            .in(MonitorProjectMember::getProjectId, projectIds)
                            .eq(MonitorProjectMember::getRole, "OWNER")
                            .last("FOR UPDATE"));
            boolean removesLastProjectOwner = projectOwners.stream()
                    .filter(owner -> Objects.equals(owner.getAdminId(), adminId))
                    .anyMatch(owner -> projectOwners.stream()
                            .filter(other -> Objects.equals(other.getProjectId(), owner.getProjectId()))
                            .count() <= 1);
            if (removesLastProjectOwner) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "transfer project ownership before removing the last project owner");
            }
        }
        teamMemberMapper.delete(Wrappers.<MonitorTeamMember>lambdaQuery()
                .eq(MonitorTeamMember::getTenantId, tenantId)
                .eq(MonitorTeamMember::getAdminId, adminId));
        if (!projectIds.isEmpty()) {
            projectMemberMapper.delete(Wrappers.<MonitorProjectMember>lambdaQuery()
                    .in(MonitorProjectMember::getProjectId, projectIds)
                    .eq(MonitorProjectMember::getAdminId, adminId));
        }
        memberMapper.deleteById(member.getId());
        audit(tenantId, accessService.currentAdminId(), "member.removed", "member", adminId,
                Map.of("previousRole", member.getRole()));
    }

    public List<MonitorTenantAuditLog> listAuditLogs(Long tenantId, int limit) {
        requireTenantPermission(tenantId, "AUDIT_READ");
        int safeLimit = Math.max(1, Math.min(200, limit));
        return auditLogMapper.selectList(Wrappers.<MonitorTenantAuditLog>lambdaQuery()
                .eq(MonitorTenantAuditLog::getTenantId, tenantId)
                .orderByDesc(MonitorTenantAuditLog::getId)
                .last("LIMIT " + safeLimit));
    }

    private long ownerCount(Long tenantId) {
        tenantMapper.lockTenant(tenantId);
        return memberMapper.selectList(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenantId)
                .eq(MonitorTenantMember::getRole, "OWNER")
                .last("FOR UPDATE")).size();
    }

    private void lockTenantMembershipChanges(Long tenantId) {
        if (tenantMapper.lockTenant(tenantId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant not found");
        }
    }

    private List<Long> tenantProjectIds(Long tenantId) {
        return projectMapper.selectList(Wrappers.<MonitorProject>lambdaQuery()
                        .eq(MonitorProject::getTenantId, tenantId))
                .stream()
                .map(MonitorProject::getId)
                .filter(id -> id != null)
                .distinct()
                .toList();
    }

    private MonitorTeam requireTeam(Long tenantId, Long teamId) {
        MonitorTeam team = teamMapper.selectOne(Wrappers.<MonitorTeam>lambdaQuery()
                .eq(MonitorTeam::getTenantId, tenantId)
                .eq(MonitorTeam::getId, teamId)
                .last("LIMIT 1"));
        if (team == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "team not found");
        }
        return team;
    }

    private void audit(Long tenantId, Long actorAdminId, String action, String resourceType,
                       Object resourceId, Map<String, Object> details) {
        MonitorTenantAuditLog log = new MonitorTenantAuditLog();
        log.setTenantId(tenantId);
        log.setActorAdminId(actorAdminId);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(String.valueOf(resourceId));
        try {
            log.setDetailJson(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize tenant audit details", e);
        }
        auditLogMapper.insert(log);
    }

    private MonitorTenantMember requireTenantMember(Long tenantId) {
        return authorization.requireMember(tenantId);
    }

    private MonitorTenantMember requireTenantOwner(Long tenantId) {
        return authorization.requireOwner(tenantId);
    }

    private MonitorTenantMember requireTenantPermission(Long tenantId, String permission) {
        return authorization.requirePermission(tenantId, permission);
    }
}
