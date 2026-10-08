package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorProjectMemberRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMemberMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorProjectMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeam;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeamMember;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MonitorProjectAccessService {

    private final MonitorProjectMapper projectMapper;
    private final MonitorProjectMemberMapper memberMapper;
    private final MonitorTenantMemberMapper tenantMemberMapper;
    private final MonitorTeamMapper teamMapper;
    private final MonitorTeamMemberMapper teamMemberMapper;
    private final UmsAdminService adminService;
    private final MonitorTenantMapper tenantMapper;
    private final MonitorTenantAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public MonitorProjectAccessService(MonitorProjectMapper projectMapper,
                                       MonitorProjectMemberMapper memberMapper,
                                       MonitorTenantMemberMapper tenantMemberMapper,
                                       MonitorTeamMapper teamMapper,
                                       MonitorTeamMemberMapper teamMemberMapper,
                                       UmsAdminService adminService,
                                       MonitorTenantMapper tenantMapper,
                                       MonitorTenantAuditLogMapper auditLogMapper,
                                       ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.memberMapper = memberMapper;
        this.tenantMemberMapper = tenantMemberMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.adminService = adminService;
        this.tenantMapper = tenantMapper;
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
    }

    MonitorProjectAccessService(MonitorProjectMapper projectMapper,
                                MonitorProjectMemberMapper memberMapper,
                                MonitorTenantMemberMapper tenantMemberMapper,
                                MonitorTeamMapper teamMapper,
                                MonitorTeamMemberMapper teamMemberMapper,
                                UmsAdminService adminService) {
        this(projectMapper, memberMapper, tenantMemberMapper, teamMapper, teamMemberMapper, adminService,
                null, null, null);
    }

    public Long currentAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        UmsAdmin admin = adminService.getAdminByUsername(authentication.getName());
        if (admin == null || admin.getStatus() == null || admin.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "active admin account required");
        }
        return admin.getId();
    }

    public List<MonitorProject> listProjects() {
        Long adminId = currentAdminId();
        List<MonitorTenantMember> tenantMemberships = tenantMemberMapper.selectList(
                Wrappers.<MonitorTenantMember>lambdaQuery()
                        .eq(MonitorTenantMember::getAdminId, adminId)
        );
        if (tenantMemberships.isEmpty()) {
            return List.of();
        }
        List<Long> tenantIds = tenantMemberships.stream()
                .map(MonitorTenantMember::getTenantId).distinct().toList();
        Set<Long> projectIds = new LinkedHashSet<>();
        List<MonitorProjectMember> memberships = memberMapper.selectList(
                Wrappers.<MonitorProjectMember>lambdaQuery()
                        .eq(MonitorProjectMember::getAdminId, adminId)
                        .orderByAsc(MonitorProjectMember::getProjectId)
        );
        memberships.stream().map(MonitorProjectMember::getProjectId).forEach(projectIds::add);
        List<MonitorTeamMember> teamMemberships = teamMemberMapper.selectList(
                Wrappers.<MonitorTeamMember>lambdaQuery().eq(MonitorTeamMember::getAdminId, adminId)
        );
        List<Long> teamIds = teamMemberships.stream().map(MonitorTeamMember::getTeamId).distinct().toList();
        if (!teamIds.isEmpty()) {
            projectMapper.selectList(Wrappers.<MonitorProject>lambdaQuery()
                            .in(MonitorProject::getTeamId, teamIds)
                            .in(MonitorProject::getTenantId, tenantIds)
                            .eq(MonitorProject::getStatus, 1))
                    .stream().map(MonitorProject::getId).forEach(projectIds::add);
        }
        if (projectIds.isEmpty()) {
            return List.of();
        }
        List<MonitorProject> projects = projectMapper.selectList(
                Wrappers.<MonitorProject>lambdaQuery()
                        .in(MonitorProject::getId, projectIds)
                        .in(MonitorProject::getTenantId, tenantIds)
                        .eq(MonitorProject::getStatus, 1)
                        .orderByAsc(MonitorProject::getId)
        );
        Map<Long, String> tenantRoles = tenantMemberships.stream().collect(Collectors.toMap(
                MonitorTenantMember::getTenantId, MonitorTenantMember::getRole, (first, ignored) -> first
        ));
        Map<Long, String> projectRoles = memberships.stream().collect(Collectors.toMap(
                MonitorProjectMember::getProjectId, MonitorProjectMember::getRole, (first, ignored) -> first
        ));
        Map<Long, String> teamRoles = teamMemberships.stream().collect(Collectors.toMap(
                MonitorTeamMember::getTeamId, MonitorTeamMember::getRole, (first, ignored) -> first
        ));
        for (MonitorProject project : projects) {
            String effectiveRole = projectRoles.get(project.getId());
            if (effectiveRole == null) {
                effectiveRole = teamRoles.get(project.getTeamId());
            }
            project.setCanWrite(!"VIEWER".equals(tenantRoles.get(project.getTenantId()))
                    && effectiveRole != null && !"VIEWER".equals(effectiveRole));
        }
        return projects;
    }

    public MonitorProject requireProject(String projectKey, boolean write) {
        MonitorProject project = projectMapper.selectOne(
                Wrappers.<MonitorProject>lambdaQuery()
                        .eq(MonitorProject::getProjectKey, projectKey)
                        .eq(MonitorProject::getStatus, 1)
                        .last("LIMIT 1")
        );
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor project not found");
        }
        MonitorTenantMember tenantMembership = findTenantMembership(project.getTenantId(), currentAdminId());
        if (tenantMembership == null) {
            // Hide whether an inaccessible project exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor project not found");
        }
        MonitorProjectMember membership = findMembership(project.getId(), currentAdminId());
        MonitorTeamMember teamMembership = findTeamMembership(project.getTenantId(), project.getTeamId(), currentAdminId());
        if (membership == null && teamMembership == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor project not found");
        }
        if (write && !hasProjectWritePermission(tenantMembership, membership, teamMembership)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project write permission required");
        }
        return project;
    }

    public MonitorProject requireProjectOwner(String projectKey) {
        MonitorProject project = requireProject(projectKey, true);
        requireOwner(project.getId());
        return project;
    }

    public boolean canManageProject(MonitorProject project) {
        Long adminId = currentAdminId();
        MonitorTenantMember tenantMembership = findTenantMembership(project.getTenantId(), adminId);
        MonitorProjectMember membership = findMembership(project.getId(), adminId);
        return tenantMembership != null && !"VIEWER".equals(tenantMembership.getRole())
                && membership != null && "OWNER".equals(membership.getRole());
    }

    public boolean canWriteProject(MonitorProject project) {
        Long adminId = currentAdminId();
        MonitorTenantMember tenantMembership = findTenantMembership(project.getTenantId(), adminId);
        MonitorProjectMember membership = findMembership(project.getId(), adminId);
        MonitorTeamMember teamMembership = findTeamMembership(project.getTenantId(), project.getTeamId(), adminId);
        return hasProjectWritePermission(tenantMembership, membership, teamMembership);
    }

    private boolean hasProjectWritePermission(MonitorTenantMember tenantMembership,
                                              MonitorProjectMember membership,
                                              MonitorTeamMember teamMembership) {
        if (tenantMembership == null || "VIEWER".equals(tenantMembership.getRole())
                || (membership == null && teamMembership == null)) return false;
        String effectiveRole = membership == null ? teamMembership.getRole() : membership.getRole();
        return !"VIEWER".equals(effectiveRole);
    }

    public MonitorTeam teamForProjectCreation(Long requestedTeamId) {
        Long adminId = currentAdminId();
        MonitorTeam team;
        if (requestedTeamId != null) {
            team = teamMapper.selectById(requestedTeamId);
        } else {
            List<MonitorTenantMember> writableMemberships = tenantMemberMapper.selectList(
                    Wrappers.<MonitorTenantMember>lambdaQuery()
                            .eq(MonitorTenantMember::getAdminId, adminId)
                            .ne(MonitorTenantMember::getRole, "VIEWER")
                            .orderByAsc(MonitorTenantMember::getTenantId)
            );
            if (writableMemberships.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant project creation permission required");
            }
            if (writableMemberships.size() > 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "teamId is required when multiple tenants are available");
            }
            team = teamMapper.selectOne(Wrappers.<MonitorTeam>lambdaQuery()
                    .eq(MonitorTeam::getTenantId, writableMemberships.get(0).getTenantId())
                    .eq(MonitorTeam::getIsDefault, 1)
                    .last("LIMIT 1"));
        }
        if (team == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "team not found");
        }
        MonitorTenantMember tenantMembership = findTenantMembership(team.getTenantId(), adminId);
        if (tenantMembership == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "team not found");
        }
        if ("VIEWER".equals(tenantMembership.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant write permission required");
        }
        if (!"OWNER".equals(tenantMembership.getRole())) {
            MonitorTeamMember teamMembership = findTeamMembership(team.getTenantId(), team.getId(), adminId);
            if (teamMembership == null || "VIEWER".equals(teamMembership.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "team project creation permission required");
            }
        }
        return team;
    }

    public List<MonitorProjectMember> listMembers(String projectKey) {
        MonitorProject project = requireProject(projectKey, false);
        requireOwner(project.getId());
        return memberMapper.selectList(
                Wrappers.<MonitorProjectMember>lambdaQuery()
                        .eq(MonitorProjectMember::getProjectId, project.getId())
                        .orderByAsc(MonitorProjectMember::getId)
        );
    }

    @Transactional
    public MonitorProjectMember addOrUpdateMember(String projectKey, MonitorProjectMemberRequest request) {
        MonitorProject project = requireProjectOwner(projectKey);
        lockTenantMembershipChanges(project.getTenantId());
        project = requireProjectOwner(projectKey);
        if (!Set.of("MEMBER", "VIEWER").contains(request.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be MEMBER or VIEWER");
        }
        UmsAdmin target = adminService.getById(request.getAdminId());
        if (target == null || target.getStatus() == null || target.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "active admin account not found");
        }
        if (findTenantMembership(project.getTenantId(), request.getAdminId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "admin must first be added to the project tenant");
        }
        List<UmsResource> targetResources = adminService.getResourceList(target.getId());
        boolean hasMonitorAdminResource = targetResources != null && targetResources.stream()
                .map(UmsResource::getUrl)
                .anyMatch("/monitor/admin/**"::equals);
        if (!hasMonitorAdminResource) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "admin must first receive the mall-tiny monitor admin RBAC resource"
            );
        }
        MonitorProjectMember membership = findMembership(project.getId(), request.getAdminId());
        if (membership == null) {
            membership = new MonitorProjectMember();
            membership.setProjectId(project.getId());
            membership.setAdminId(request.getAdminId());
            membership.setRole(request.getRole());
            memberMapper.insert(membership);
        } else {
            if ("OWNER".equals(membership.getRole())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "owner role cannot be changed");
            }
            membership.setRole(request.getRole());
            memberMapper.updateById(membership);
        }
        return membership;
    }

    @Transactional
    public void removeMember(String projectKey, Long adminId) {
        MonitorProject project = requireProjectOwner(projectKey);
        lockTenantMembershipChanges(project.getTenantId());
        project = requireProjectOwner(projectKey);
        MonitorProjectMember membership = findMembership(project.getId(), adminId);
        if (membership == null) {
            return;
        }
        if ("OWNER".equals(membership.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "owner membership cannot be removed");
        }
        memberMapper.deleteById(membership.getId());
    }

    @Transactional
    public MonitorProjectMember transferOwner(String projectKey, Long targetAdminId) {
        MonitorProject project = requireProjectOwner(projectKey);
        lockTenantMembershipChanges(project.getTenantId());
        project = requireProjectOwner(projectKey);

        Long actorAdminId = currentAdminId();
        List<MonitorProjectMember> memberships = memberMapper.selectList(
                Wrappers.<MonitorProjectMember>lambdaQuery()
                        .eq(MonitorProjectMember::getProjectId, project.getId())
                        .orderByAsc(MonitorProjectMember::getId)
                        .last("FOR UPDATE")
        );
        MonitorProjectMember oldOwner = memberships.stream()
                .filter(member -> actorAdminId.equals(member.getAdminId()))
                .filter(member -> "OWNER".equals(member.getRole()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "project owner permission required"));
        MonitorProjectMember newOwner = memberships.stream()
                .filter(member -> targetAdminId.equals(member.getAdminId()))
                .filter(member -> "MEMBER".equals(member.getRole()) || "VIEWER".equals(member.getRole()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "target must be an existing project member"));

        UmsAdmin targetAdmin = adminService.getById(targetAdminId);
        if (targetAdmin == null || targetAdmin.getStatus() == null || targetAdmin.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target must be an active admin account");
        }
        MonitorTenantMember targetTenantMembership = findTenantMembership(project.getTenantId(), targetAdminId);
        if (targetTenantMembership == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "target must remain a member of the project tenant");
        }
        if ("VIEWER".equals(targetTenantMembership.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "target must have tenant write permission to become project owner");
        }
        List<UmsResource> targetResources = adminService.getResourceList(targetAdmin.getId());
        boolean hasMonitorAdminResource = targetResources != null && targetResources.stream()
                .map(UmsResource::getUrl)
                .anyMatch("/monitor/admin/**"::equals);
        if (!hasMonitorAdminResource) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "target must first receive the mall-tiny monitor admin RBAC resource");
        }

        newOwner.setRole("OWNER");
        if (memberMapper.updateById(newOwner) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "could not transfer project ownership");
        }
        oldOwner.setRole("MEMBER");
        if (memberMapper.updateById(oldOwner) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "could not transfer project ownership");
        }
        auditOwnerTransfer(project, actorAdminId, oldOwner.getAdminId(), newOwner.getAdminId());
        return newOwner;
    }

    @Transactional
    public void addOwner(Long projectId, Long adminId) {
        MonitorProjectMember existing = findMembership(projectId, adminId);
        if (existing != null) {
            existing.setRole("OWNER");
            memberMapper.updateById(existing);
            return;
        }
        MonitorProjectMember owner = new MonitorProjectMember();
        owner.setProjectId(projectId);
        owner.setAdminId(adminId);
        owner.setRole("OWNER");
        memberMapper.insert(owner);
    }

    private void requireOwner(Long projectId) {
        MonitorProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor project not found");
        }
        Long adminId = currentAdminId();
        MonitorTenantMember tenantMembership = findTenantMembership(project.getTenantId(), adminId);
        MonitorProjectMember membership = findMembership(projectId, adminId);
        if (tenantMembership == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor project not found");
        }
        if ("VIEWER".equals(tenantMembership.getRole())
                || membership == null || !"OWNER".equals(membership.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project owner permission required");
        }
    }

    private void lockTenantMembershipChanges(Long tenantId) {
        if (tenantMapper.lockTenant(tenantId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor project not found");
        }
    }

    private void auditOwnerTransfer(MonitorProject project, Long actorAdminId,
                                    Long oldOwnerAdminId, Long newOwnerAdminId) {
        MonitorTenantAuditLog log = new MonitorTenantAuditLog();
        log.setTenantId(project.getTenantId());
        log.setActorAdminId(actorAdminId);
        log.setAction("project.owner_transferred");
        log.setResourceType("monitor_project");
        log.setResourceId(String.valueOf(project.getId()));
        try {
            log.setDetailJson(objectMapper.writeValueAsString(Map.of(
                    "projectKey", project.getProjectKey(),
                    "oldOwnerAdminId", oldOwnerAdminId,
                    "newOwnerAdminId", newOwnerAdminId
            )));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize project owner transfer audit details", e);
        }
        if (auditLogMapper.insert(log) != 1) {
            throw new IllegalStateException("Could not record project owner transfer audit log");
        }
    }

    private MonitorTenantMember findTenantMembership(Long tenantId, Long adminId) {
        if (tenantId == null) {
            return null;
        }
        return tenantMemberMapper.selectOne(
                Wrappers.<MonitorTenantMember>lambdaQuery()
                        .eq(MonitorTenantMember::getTenantId, tenantId)
                        .eq(MonitorTenantMember::getAdminId, adminId)
                        .last("LIMIT 1")
        );
    }

    private MonitorProjectMember findMembership(Long projectId, Long adminId) {
        return memberMapper.selectOne(
                Wrappers.<MonitorProjectMember>lambdaQuery()
                        .eq(MonitorProjectMember::getProjectId, projectId)
                        .eq(MonitorProjectMember::getAdminId, adminId)
                        .last("LIMIT 1")
        );
    }

    private MonitorTeamMember findTeamMembership(Long tenantId, Long teamId, Long adminId) {
        if (tenantId == null || teamId == null) {
            return null;
        }
        return teamMemberMapper.selectOne(Wrappers.<MonitorTeamMember>lambdaQuery()
                .eq(MonitorTeamMember::getTenantId, tenantId)
                .eq(MonitorTeamMember::getTeamId, teamId)
                .eq(MonitorTeamMember::getAdminId, adminId)
                .last("LIMIT 1"));
    }
}
