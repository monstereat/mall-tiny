package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantMemberRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantRoleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMemberMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorProjectMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantRole;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeam;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeamMember;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorTenantMemberRevocationTest {

    @Mock private MonitorTenantMapper tenantMapper;
    @Mock private MonitorTenantAuditLogMapper auditLogMapper;
    @Mock private MonitorTenantMemberMapper memberMapper;
    @Mock private MonitorProjectMapper projectMapper;
    @Mock private MonitorProjectMemberMapper projectMemberMapper;
    @Mock private MonitorTenantRoleMapper roleMapper;
    @Mock private MonitorTeamMapper teamMapper;
    @Mock private MonitorTeamMemberMapper teamMemberMapper;
    @Mock private MonitorProjectAccessService accessService;
    @Mock private UmsAdminService adminService;

    @BeforeEach
    void initializeMyBatisPlusLambdaMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "tenant-member-revocation-test");
        TableInfoHelper.initTableInfo(assistant, MonitorTenantMember.class);
        TableInfoHelper.initTableInfo(assistant, MonitorTenantRole.class);
        TableInfoHelper.initTableInfo(assistant, MonitorTeam.class);
        TableInfoHelper.initTableInfo(assistant, MonitorTeamMember.class);
        TableInfoHelper.initTableInfo(assistant, MonitorProject.class);
        TableInfoHelper.initTableInfo(assistant, MonitorProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, MonitorTenantAuditLog.class);
    }

    @Test
    void removingAndReaddingMemberDoesNotRestoreOldDirectProjectRoles() {
        when(tenantMapper.lockTenant(7L)).thenReturn(7L);
        when(accessService.currentAdminId()).thenReturn(42L);
        when(memberMapper.selectOne(any())).thenReturn(
                member(1L, 42L, "OWNER"), member(2L, 84L, "MEMBER"),
                member(1L, 42L, "OWNER"), null);
        when(projectMapper.selectList(any())).thenReturn(List.of(project(101L), project(102L)));
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(
                projectMember(101L, 84L, "OWNER"),
                projectMember(101L, 42L, "OWNER")));
        when(adminService.getById(84L)).thenReturn(activeAdmin(84L));
        when(adminService.getResourceList(84L)).thenReturn(List.of(monitorAdminResource()));

        MonitorTenantService service = service();
        service.removeMember(7L, 84L);

        MonitorTenantMemberRequest request = new MonitorTenantMemberRequest();
        request.setAdminId(84L);
        request.setRole("MEMBER");
        service.addOrUpdateMember(7L, request);

        ArgumentCaptor<Wrapper<MonitorProjectMember>> deletion = ArgumentCaptor.forClass(Wrapper.class);
        verify(projectMemberMapper).delete(deletion.capture());
        String sqlSegment = deletion.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("project_id IN"));
        assertTrue(sqlSegment.contains("admin_id"), sqlSegment);
        var parameters = ((AbstractWrapper<?, ?, ?>) deletion.getValue()).getParamNameValuePairs();
        assertTrue(parameters.containsValue(84L));
        assertTrue(parameters.containsValue(101L));
        assertTrue(parameters.containsValue(102L));

        ArgumentCaptor<MonitorTenantMember> readdedMembership = ArgumentCaptor.forClass(MonitorTenantMember.class);
        verify(memberMapper).insert(readdedMembership.capture());
        assertEquals("MEMBER", readdedMembership.getValue().getRole());
        verify(projectMemberMapper, never()).insert(any(MonitorProjectMember.class));
        verify(projectMemberMapper, never()).updateById(any(MonitorProjectMember.class));
        verify(teamMemberMapper).delete(any());
        verify(tenantMapper, org.mockito.Mockito.times(2)).lockTenant(7L);
    }

    @Test
    void tenantMemberCannotBeRemovedWhenTheyAreTheLastProjectOwner() {
        when(tenantMapper.lockTenant(7L)).thenReturn(7L);
        when(accessService.currentAdminId()).thenReturn(42L);
        when(memberMapper.selectOne(any())).thenReturn(member(1L, 42L, "OWNER"), member(2L, 84L, "MEMBER"));
        when(projectMapper.selectList(any())).thenReturn(List.of(project(101L)));
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(projectMember(101L, 84L, "OWNER")));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().removeMember(7L, 84L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(projectMemberMapper, never()).delete(any());
        verify(teamMemberMapper, never()).delete(any());
        verify(memberMapper, never()).deleteById(2L);
        verify(auditLogMapper, never()).insert(any(MonitorTenantAuditLog.class));
    }

    @Test
    void removingTenantOwnerCannotRemoveTheLastTenantOwner() {
        MonitorTenantMember owner = member(1L, 42L, "OWNER");
        when(tenantMapper.lockTenant(7L)).thenReturn(7L);
        when(accessService.currentAdminId()).thenReturn(42L);
        when(memberMapper.selectOne(any())).thenReturn(owner, owner);
        when(memberMapper.selectList(any())).thenReturn(List.of(owner));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().removeMember(7L, 42L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(projectMapper, never()).selectList(any());
        verify(projectMemberMapper, never()).delete(any());
        verify(memberMapper, never()).deleteById(1L);
        InOrder order = inOrder(tenantMapper, memberMapper);
        order.verify(tenantMapper).lockTenant(7L);
        order.verify(memberMapper, org.mockito.Mockito.times(2)).selectOne(any());
    }

    @Test
    void tenantMemberWithAnotherProjectOwnerCanBeRemovedWithoutOrphaningProject() {
        when(tenantMapper.lockTenant(7L)).thenReturn(7L);
        when(accessService.currentAdminId()).thenReturn(42L);
        when(memberMapper.selectOne(any())).thenReturn(member(1L, 42L, "OWNER"), member(2L, 84L, "MEMBER"));
        when(projectMapper.selectList(any())).thenReturn(List.of(project(101L)));
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(
                projectMember(101L, 84L, "OWNER"), projectMember(101L, 42L, "OWNER")));

        service().removeMember(7L, 84L);

        verify(projectMemberMapper).delete(any());
        verify(memberMapper).deleteById(2L);
    }

    private MonitorTenantService service() {
        return new MonitorTenantService(tenantMapper, auditLogMapper, memberMapper, projectMapper, projectMemberMapper,
                teamMapper, teamMemberMapper, roleMapper,
                new MonitorTenantAuthorizationService(memberMapper, roleMapper, accessService, new ObjectMapper()),
                accessService, adminService, new ObjectMapper());
    }

    private MonitorTenantMember member(Long id, Long adminId, String role) {
        MonitorTenantMember member = new MonitorTenantMember();
        member.setId(id);
        member.setTenantId(7L);
        member.setAdminId(adminId);
        member.setRole(role);
        return member;
    }

    private MonitorProject project(Long id) {
        MonitorProject project = new MonitorProject();
        project.setId(id);
        project.setTenantId(7L);
        return project;
    }

    private MonitorProjectMember projectMember(Long projectId, Long adminId, String role) {
        MonitorProjectMember member = new MonitorProjectMember();
        member.setProjectId(projectId);
        member.setAdminId(adminId);
        member.setRole(role);
        return member;
    }

    private UmsAdmin activeAdmin(Long id) {
        UmsAdmin admin = new UmsAdmin();
        admin.setId(id);
        admin.setStatus(1);
        return admin;
    }

    private UmsResource monitorAdminResource() {
        UmsResource resource = new UmsResource();
        resource.setUrl("/monitor/admin/**");
        return resource;
    }
}
