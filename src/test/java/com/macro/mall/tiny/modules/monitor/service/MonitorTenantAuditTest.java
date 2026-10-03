package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantMemberRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTeamMemberRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantRoleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMemberMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenant;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeam;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeamMember;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorTenantAuditTest {

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

    private MonitorTenantService service() {
        return new MonitorTenantService(tenantMapper, auditLogMapper, memberMapper, projectMapper, projectMemberMapper,
                teamMapper, teamMemberMapper,
                roleMapper, new MonitorTenantAuthorizationService(memberMapper, roleMapper, accessService, new ObjectMapper()),
                accessService, adminService, new ObjectMapper());
    }

    @Test
    void tenantCreationWritesActorAndNonSecretAuditDetails() throws Exception {
        when(tenantMapper.selectOne(any())).thenReturn(null);
        when(accessService.currentAdminId()).thenReturn(42L);
        when(tenantMapper.insert(any(MonitorTenant.class))).thenAnswer(invocation -> {
            ((MonitorTenant) invocation.getArgument(0)).setId(7L);
            return 1;
        });
        when(teamMapper.insert(any(MonitorTeam.class))).thenAnswer(invocation -> {
            ((MonitorTeam) invocation.getArgument(0)).setId(9L);
            return 1;
        });

        MonitorTenantRequest request = new MonitorTenantRequest();
        request.setName("Acme");
        request.setTenantKey("acme");
        service().createTenant(request);

        ArgumentCaptor<MonitorTenantAuditLog> audit = ArgumentCaptor.forClass(MonitorTenantAuditLog.class);
        verify(auditLogMapper).insert(audit.capture());
        assertEquals(7L, audit.getValue().getTenantId());
        assertEquals(42L, audit.getValue().getActorAdminId());
        assertEquals("tenant.created", audit.getValue().getAction());
        var details = new ObjectMapper().readTree(audit.getValue().getDetailJson());
        assertEquals("Acme", details.path("name").asText());
        assertEquals("acme", details.path("tenantKey").asText());
    }

    @Test
    void lastOwnerCannotDemoteSelf() {
        when(tenantMapper.lockTenant(7L)).thenReturn(7L);
        when(accessService.currentAdminId()).thenReturn(42L);
        when(memberMapper.selectOne(any())).thenReturn(member(42L, "OWNER"), member(42L, "OWNER"));
        when(memberMapper.selectList(any())).thenReturn(List.of(member(42L, "OWNER")));
        UmsAdmin admin = new UmsAdmin();
        admin.setId(42L);
        admin.setStatus(1);
        when(adminService.getById(42L)).thenReturn(admin);
        UmsResource monitorResource = new UmsResource();
        monitorResource.setUrl("/monitor/admin/**");
        when(adminService.getResourceList(42L)).thenReturn(List.of(monitorResource));
        MonitorTenantMemberRequest request = new MonitorTenantMemberRequest();
        request.setAdminId(42L);
        request.setRole("VIEWER");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().addOrUpdateMember(7L, request));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(memberMapper, never()).updateById(any(MonitorTenantMember.class));
        verify(auditLogMapper, never()).insert(any(MonitorTenantAuditLog.class));
    }

    @Test
    void teamMemberGrantWritesAuditAndRequiresExistingTenantMember() throws Exception {
        when(memberMapper.selectOne(any())).thenReturn(member(42L, "OWNER"), member(84L, "MEMBER"));
        when(teamMapper.selectOne(any())).thenReturn(team(9L, 7L));
        when(teamMemberMapper.selectOne(any())).thenReturn(null);
        when(accessService.currentAdminId()).thenReturn(42L);
        UmsAdmin admin = new UmsAdmin();
        admin.setId(84L);
        admin.setStatus(1);
        when(adminService.getById(84L)).thenReturn(admin);
        UmsResource monitorResource = new UmsResource();
        monitorResource.setUrl("/monitor/admin/**");
        when(adminService.getResourceList(84L)).thenReturn(List.of(monitorResource));

        MonitorTeamMemberRequest request = new MonitorTeamMemberRequest();
        request.setAdminId(84L);
        request.setRole("VIEWER");
        service().addOrUpdateTeamMember(7L, 9L, request);

        ArgumentCaptor<MonitorTeamMember> membership = ArgumentCaptor.forClass(MonitorTeamMember.class);
        verify(teamMemberMapper).insert(membership.capture());
        assertEquals(7L, membership.getValue().getTenantId());
        assertEquals(9L, membership.getValue().getTeamId());
        assertEquals("VIEWER", membership.getValue().getRole());
        ArgumentCaptor<MonitorTenantAuditLog> audit = ArgumentCaptor.forClass(MonitorTenantAuditLog.class);
        verify(auditLogMapper).insert(audit.capture());
        assertEquals("team.member_added", audit.getValue().getAction());
        assertEquals(42L, audit.getValue().getActorAdminId());
    }

    @Test
    void removingTenantMemberAlsoRevokesTeamMemberships() {
        when(tenantMapper.lockTenant(7L)).thenReturn(7L);
        when(accessService.currentAdminId()).thenReturn(42L);
        when(memberMapper.selectOne(any())).thenReturn(member(42L, "OWNER"), member(84L, "MEMBER"));
        when(projectMapper.selectList(any())).thenReturn(List.of());

        service().removeMember(7L, 84L);

        verify(teamMemberMapper).delete(any());
        verify(memberMapper).deleteById(3L);
        verify(auditLogMapper).insert(any(MonitorTenantAuditLog.class));
    }

    @Test
    void memberCannotReadTenantAuditLog() {
        when(accessService.currentAdminId()).thenReturn(42L);
        when(memberMapper.selectOne(any())).thenReturn(member(42L, "MEMBER"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().listAuditLogs(7L, 50));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(auditLogMapper, never()).selectList(any());
    }

    private MonitorTenantMember member(Long adminId, String role) {
        MonitorTenantMember member = new MonitorTenantMember();
        member.setId(3L);
        member.setTenantId(7L);
        member.setAdminId(adminId);
        member.setRole(role);
        return member;
    }

    private MonitorTeam team(Long teamId, Long tenantId) {
        MonitorTeam team = new MonitorTeam();
        team.setId(teamId);
        team.setTenantId(tenantId);
        team.setTeamKey("backend");
        return team;
    }
}
