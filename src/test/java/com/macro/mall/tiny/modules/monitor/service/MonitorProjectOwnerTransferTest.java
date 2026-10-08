package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMemberMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorProjectMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeamMember;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorProjectOwnerTransferTest {

    private static final long TENANT_ID = 77L;
    private static final long PROJECT_ID = 55L;
    private static final long OLD_OWNER_ID = 10L;
    private static final long TARGET_ID = 20L;

    @Mock private MonitorProjectMapper projectMapper;
    @Mock private MonitorProjectMemberMapper memberMapper;
    @Mock private MonitorTenantMemberMapper tenantMemberMapper;
    @Mock private MonitorTeamMapper teamMapper;
    @Mock private MonitorTeamMemberMapper teamMemberMapper;
    @Mock private UmsAdminService adminService;
    @Mock private MonitorTenantMapper tenantMapper;
    @Mock private MonitorTenantAuditLogMapper auditLogMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MonitorProjectAccessService service;

    @BeforeEach
    void setUp() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "project-owner-transfer-test");
        TableInfoHelper.initTableInfo(assistant, MonitorProject.class);
        TableInfoHelper.initTableInfo(assistant, MonitorProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, MonitorTenantMember.class);
        TableInfoHelper.initTableInfo(assistant, MonitorTenantAuditLog.class);
        TableInfoHelper.initTableInfo(assistant, MonitorTeamMember.class);
        service = new MonitorProjectAccessService(projectMapper, memberMapper, tenantMemberMapper, teamMapper,
                teamMemberMapper, adminService, tenantMapper, auditLogMapper, objectMapper);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("owner", "", List.of()));
        when(adminService.getAdminByUsername("owner")).thenReturn(admin(OLD_OWNER_ID, 1));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void transfersOwnerToActiveProjectMemberAndAuditsActorAndOwners() throws Exception {
        MonitorProjectMember oldOwner = member(101L, OLD_OWNER_ID, "OWNER");
        MonitorProjectMember target = member(102L, TARGET_ID, "VIEWER");
        prepareTransfer(oldOwner, target, admin(TARGET_ID, 1), tenantMember(TARGET_ID, "MEMBER"),
                List.of(monitorAdminResource()));
        when(memberMapper.updateById(any(MonitorProjectMember.class))).thenReturn(1);
        when(auditLogMapper.insert(any(MonitorTenantAuditLog.class))).thenReturn(1);

        MonitorProjectMember transferred = service.transferOwner("project-a", TARGET_ID);

        assertEquals("OWNER", transferred.getRole());
        assertEquals("MEMBER", oldOwner.getRole());
        ArgumentCaptor<MonitorTenantAuditLog> audit = ArgumentCaptor.forClass(MonitorTenantAuditLog.class);
        verify(auditLogMapper).insert(audit.capture());
        assertEquals(TENANT_ID, audit.getValue().getTenantId());
        assertEquals(OLD_OWNER_ID, audit.getValue().getActorAdminId());
        assertEquals("project.owner_transferred", audit.getValue().getAction());
        assertEquals("monitor_project", audit.getValue().getResourceType());
        assertEquals(String.valueOf(PROJECT_ID), audit.getValue().getResourceId());
        JsonNode details = objectMapper.readTree(audit.getValue().getDetailJson());
        assertEquals("project-a", details.get("projectKey").asText());
        assertEquals(OLD_OWNER_ID, details.get("oldOwnerAdminId").asLong());
        assertEquals(TARGET_ID, details.get("newOwnerAdminId").asLong());

        InOrder order = inOrder(tenantMapper, memberMapper);
        order.verify(tenantMapper).lockTenant(TENANT_ID);
        order.verify(memberMapper).selectList(any(Wrapper.class));
        ArgumentCaptor<MonitorProjectMember> updates = ArgumentCaptor.forClass(MonitorProjectMember.class);
        order.verify(memberMapper, times(2)).updateById(updates.capture());
        assertEquals(TARGET_ID, updates.getAllValues().get(0).getAdminId());
        assertEquals("OWNER", updates.getAllValues().get(0).getRole());
        assertEquals(OLD_OWNER_ID, updates.getAllValues().get(1).getAdminId());
        assertEquals("MEMBER", updates.getAllValues().get(1).getRole());
    }

    @Test
    void nonOwnerCannotTransferOwnership() {
        prepareBase(member(101L, OLD_OWNER_ID, "MEMBER"));

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> service.transferOwner("project-a", TARGET_ID));

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        verify(tenantMapper, never()).lockTenant(any());
        verify(memberMapper, never()).updateById(any(MonitorProjectMember.class));
    }

    @Test
    void targetMustAlreadyBeAProjectMember() {
        MonitorProjectMember owner = member(101L, OLD_OWNER_ID, "OWNER");
        prepareTransfer(owner, null, admin(TARGET_ID, 1), tenantMember(TARGET_ID, "MEMBER"),
                List.of(monitorAdminResource()));

        ResponseStatusException invalidTarget = assertThrows(ResponseStatusException.class,
                () -> service.transferOwner("project-a", TARGET_ID));

        assertEquals(HttpStatus.BAD_REQUEST, invalidTarget.getStatusCode());
        verify(memberMapper, never()).updateById(any(MonitorProjectMember.class));
        verify(auditLogMapper, never()).insert(any(MonitorTenantAuditLog.class));
    }

    @Test
    void targetAdminMustExistAndBeActive() {
        MonitorProjectMember owner = member(101L, OLD_OWNER_ID, "OWNER");
        MonitorProjectMember target = member(102L, TARGET_ID, "MEMBER");
        prepareTransfer(owner, target, null, tenantMember(TARGET_ID, "MEMBER"),
                List.of(monitorAdminResource()));

        ResponseStatusException missingAdmin = assertThrows(ResponseStatusException.class,
                () -> service.transferOwner("project-a", TARGET_ID));

        assertEquals(HttpStatus.BAD_REQUEST, missingAdmin.getStatusCode());
        verify(memberMapper, never()).updateById(any(MonitorProjectMember.class));

        resetTargetAdmin(admin(TARGET_ID, 0));
        ResponseStatusException inactiveAdmin = assertThrows(ResponseStatusException.class,
                () -> service.transferOwner("project-a", TARGET_ID));

        assertEquals(HttpStatus.BAD_REQUEST, inactiveAdmin.getStatusCode());
        verify(memberMapper, never()).updateById(any(MonitorProjectMember.class));
    }

    @Test
    void targetNeedsWritableTenantMembershipAndMonitorAdminRbac() {
        MonitorProjectMember owner = member(101L, OLD_OWNER_ID, "OWNER");
        MonitorProjectMember target = member(102L, TARGET_ID, "VIEWER");
        prepareTransfer(owner, target, admin(TARGET_ID, 1), tenantMember(TARGET_ID, "VIEWER"),
                List.of(monitorAdminResource()));

        ResponseStatusException tenantViewer = assertThrows(ResponseStatusException.class,
                () -> service.transferOwner("project-a", TARGET_ID));
        assertEquals(HttpStatus.BAD_REQUEST, tenantViewer.getStatusCode());
        verify(memberMapper, never()).updateById(any(MonitorProjectMember.class));

        resetTargetTenantAndResources(tenantMember(TARGET_ID, "MEMBER"), List.of());
        ResponseStatusException missingRbac = assertThrows(ResponseStatusException.class,
                () -> service.transferOwner("project-a", TARGET_ID));
        assertEquals(HttpStatus.BAD_REQUEST, missingRbac.getStatusCode());
        verify(memberMapper, never()).updateById(any(MonitorProjectMember.class));
    }

    @Test
    void secondRoleUpdateFailureThrowsInsideTransactionalBoundary() throws Exception {
        MonitorProjectMember oldOwner = member(101L, OLD_OWNER_ID, "OWNER");
        MonitorProjectMember target = member(102L, TARGET_ID, "MEMBER");
        prepareTransfer(oldOwner, target, admin(TARGET_ID, 1), tenantMember(TARGET_ID, "MEMBER"),
                List.of(monitorAdminResource()));
        when(memberMapper.updateById(any(MonitorProjectMember.class))).thenAnswer(invocation -> {
            MonitorProjectMember update = invocation.getArgument(0);
            return "OWNER".equals(update.getRole()) ? 1 : 0;
        });

        ResponseStatusException failed = assertThrows(ResponseStatusException.class,
                () -> service.transferOwner("project-a", TARGET_ID));

        assertEquals(HttpStatus.CONFLICT, failed.getStatusCode());
        assertEquals("OWNER", target.getRole());
        assertEquals("MEMBER", oldOwner.getRole());
        verify(memberMapper).updateById(target);
        verify(memberMapper).updateById(oldOwner);
        verify(auditLogMapper, never()).insert(any(MonitorTenantAuditLog.class));
        assertNotNull(MonitorProjectAccessService.class.getMethod("transferOwner", String.class, Long.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void ownerIsRecheckedAfterWaitingForTenantLock() {
        MonitorProject project = project();
        MonitorProjectMember owner = member(101L, OLD_OWNER_ID, "OWNER");
        MonitorProjectMember nowMember = member(101L, OLD_OWNER_ID, "MEMBER");
        when(projectMapper.selectOne(any())).thenReturn(project);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(tenantMapper.lockTenant(TENANT_ID)).thenReturn(TENANT_ID);
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMember(OLD_OWNER_ID, "OWNER"));
        when(memberMapper.selectOne(any())).thenReturn(owner, owner, nowMember, nowMember);
        when(teamMemberMapper.selectOne(any())).thenReturn(null);

        ResponseStatusException lostOwnership = assertThrows(ResponseStatusException.class,
                () -> service.transferOwner("project-a", TARGET_ID));

        assertEquals(HttpStatus.FORBIDDEN, lostOwnership.getStatusCode());
        verify(tenantMapper).lockTenant(TENANT_ID);
        verify(memberMapper, never()).selectList(any());
        verify(memberMapper, never()).updateById(any(MonitorProjectMember.class));
        verify(auditLogMapper, never()).insert(any(MonitorTenantAuditLog.class));
    }

    private void prepareTransfer(MonitorProjectMember owner, MonitorProjectMember target,
                                 UmsAdmin targetAdmin, MonitorTenantMember targetTenant,
                                 List<UmsResource> targetResources) {
        prepareBase(owner);
        List<MonitorProjectMember> rows = new ArrayList<>();
        rows.add(owner);
        if (target != null) rows.add(target);
        when(memberMapper.selectList(any())).thenReturn(rows);
        when(tenantMemberMapper.selectOne(any())).thenReturn(
                tenantMember(OLD_OWNER_ID, "OWNER"),
                tenantMember(OLD_OWNER_ID, "OWNER"),
                tenantMember(OLD_OWNER_ID, "OWNER"),
                tenantMember(OLD_OWNER_ID, "OWNER"),
                targetTenant
        );
        when(adminService.getById(TARGET_ID)).thenReturn(targetAdmin);
        when(adminService.getResourceList(TARGET_ID)).thenReturn(targetResources);
    }

    private void prepareBase(MonitorProjectMember actorMembership) {
        MonitorProject project = project();
        when(projectMapper.selectOne(any())).thenReturn(project);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(tenantMapper.lockTenant(TENANT_ID)).thenReturn(TENANT_ID);
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMember(OLD_OWNER_ID, "OWNER"));
        when(memberMapper.selectOne(any())).thenReturn(actorMembership);
        when(teamMemberMapper.selectOne(any())).thenReturn(null);
    }

    private void resetTargetAdmin(UmsAdmin targetAdmin) {
        org.mockito.Mockito.reset(adminService);
        when(adminService.getAdminByUsername("owner")).thenReturn(admin(OLD_OWNER_ID, 1));
        when(adminService.getById(TARGET_ID)).thenReturn(targetAdmin);
        when(adminService.getResourceList(TARGET_ID)).thenReturn(List.of(monitorAdminResource()));
    }

    private void resetTargetTenantAndResources(MonitorTenantMember targetTenant, List<UmsResource> resources) {
        org.mockito.Mockito.reset(tenantMemberMapper, adminService);
        when(tenantMemberMapper.selectOne(any())).thenReturn(
                tenantMember(OLD_OWNER_ID, "OWNER"),
                tenantMember(OLD_OWNER_ID, "OWNER"),
                tenantMember(OLD_OWNER_ID, "OWNER"),
                tenantMember(OLD_OWNER_ID, "OWNER"),
                targetTenant
        );
        when(adminService.getAdminByUsername("owner")).thenReturn(admin(OLD_OWNER_ID, 1));
        when(adminService.getById(TARGET_ID)).thenReturn(admin(TARGET_ID, 1));
        when(adminService.getResourceList(TARGET_ID)).thenReturn(resources);
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setId(PROJECT_ID);
        project.setTenantId(TENANT_ID);
        project.setProjectKey("project-a");
        project.setStatus(1);
        return project;
    }

    private MonitorProjectMember member(Long id, Long adminId, String role) {
        MonitorProjectMember member = new MonitorProjectMember();
        member.setId(id);
        member.setProjectId(PROJECT_ID);
        member.setAdminId(adminId);
        member.setRole(role);
        return member;
    }

    private MonitorTenantMember tenantMember(Long adminId, String role) {
        MonitorTenantMember member = new MonitorTenantMember();
        member.setTenantId(TENANT_ID);
        member.setAdminId(adminId);
        member.setRole(role);
        return member;
    }

    private UmsAdmin admin(Long id, Integer status) {
        UmsAdmin admin = new UmsAdmin();
        admin.setId(id);
        admin.setStatus(status);
        return admin;
    }

    private UmsResource monitorAdminResource() {
        UmsResource resource = new UmsResource();
        resource.setUrl("/monitor/admin/**");
        return resource;
    }
}
