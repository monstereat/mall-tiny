package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.dto.MonitorProjectMemberRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMemberMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorProjectMember;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MonitorProjectAccessService {

    private final MonitorProjectMapper projectMapper;
    private final MonitorProjectMemberMapper memberMapper;
    private final UmsAdminService adminService;

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
        List<MonitorProjectMember> memberships = memberMapper.selectList(
                Wrappers.<MonitorProjectMember>lambdaQuery()
                        .eq(MonitorProjectMember::getAdminId, currentAdminId())
                        .orderByAsc(MonitorProjectMember::getProjectId)
        );
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<Long> projectIds = memberships.stream().map(MonitorProjectMember::getProjectId).distinct().toList();
        return projectMapper.selectList(
                Wrappers.<MonitorProject>lambdaQuery()
                        .in(MonitorProject::getId, projectIds)
                        .eq(MonitorProject::getStatus, 1)
                        .orderByAsc(MonitorProject::getId)
        );
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
        MonitorProjectMember membership = findMembership(project.getId(), currentAdminId());
        if (membership == null) {
            // Hide whether an inaccessible project exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor project not found");
        }
        if (write && "VIEWER".equals(membership.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project write permission required");
        }
        return project;
    }

    public MonitorProject requireProjectOwner(String projectKey) {
        MonitorProject project = requireProject(projectKey, true);
        requireOwner(project.getId());
        return project;
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
        MonitorProject project = requireProject(projectKey, true);
        requireOwner(project.getId());
        if (!Set.of("MEMBER", "VIEWER").contains(request.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be MEMBER or VIEWER");
        }
        UmsAdmin target = adminService.getById(request.getAdminId());
        if (target == null || target.getStatus() == null || target.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "active admin account not found");
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
        MonitorProject project = requireProject(projectKey, true);
        requireOwner(project.getId());
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
        MonitorProjectMember membership = findMembership(projectId, currentAdminId());
        if (membership == null || !"OWNER".equals(membership.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project owner permission required");
        }
    }

    private MonitorProjectMember findMembership(Long projectId, Long adminId) {
        return memberMapper.selectOne(
                Wrappers.<MonitorProjectMember>lambdaQuery()
                        .eq(MonitorProjectMember::getProjectId, projectId)
                        .eq(MonitorProjectMember::getAdminId, adminId)
                        .last("LIMIT 1")
        );
    }
}
