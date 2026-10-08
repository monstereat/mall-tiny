package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTenantRoleRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantRoleMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantRole;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorTenantRoleServiceTest {
    private final MonitorTenantRoleMapper roleMapper = mock(MonitorTenantRoleMapper.class);
    private final MonitorTenantMemberMapper memberMapper = mock(MonitorTenantMemberMapper.class);
    private final MonitorTenantAuditLogMapper auditMapper = mock(MonitorTenantAuditLogMapper.class);
    private final MonitorTenantAuthorizationService authorization = mock(MonitorTenantAuthorizationService.class);
    private final MonitorTenantRoleService service = new MonitorTenantRoleService(
            roleMapper, memberMapper, auditMapper, authorization, new ObjectMapper());

    @BeforeEach
    void initializeMyBatisPlusLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "tenant-role-test"),
                MonitorTenantRole.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "tenant-member-test"),
                MonitorTenantMember.class);
    }

    @Test
    void ownerCanCreateOnlyFixedPermissionRolesAndChangesAreAudited() throws Exception {
        when(authorization.requireOwner(7L)).thenReturn(owner(42L));
        when(roleMapper.insert(any(MonitorTenantRole.class))).thenAnswer(invocation -> {
            ((MonitorTenantRole) invocation.getArgument(0)).setId(18L);
            return 1;
        });
        when(authorization.permissions(any(MonitorTenantRole.class)))
                .thenReturn(List.of("AUDIT_READ", "TEAM_MANAGE"));
        MonitorTenantRoleRequest request = request(List.of("TEAM_MANAGE", "AUDIT_READ"));

        var created = service.create(7L, request);

        assertEquals(18L, created.id());
        assertEquals(List.of("AUDIT_READ", "TEAM_MANAGE"), created.permissions());
        var audit = org.mockito.ArgumentCaptor.forClass(MonitorTenantAuditLog.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("tenant_role.created", audit.getValue().getAction());
        assertEquals(42L, audit.getValue().getActorAdminId());
    }

    @Test
    void rejectsUnknownPermissionsBeforePersisting() {
        when(authorization.requireOwner(7L)).thenReturn(owner(42L));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(7L, request(List.of("DATA_DELETE"))));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(roleMapper, never()).insert(any(MonitorTenantRole.class));
        verify(auditMapper, never()).insert(any(MonitorTenantAuditLog.class));
    }

    @Test
    void referencedRoleCannotBeDeleted() {
        when(authorization.requireOwner(7L)).thenReturn(owner(42L));
        MonitorTenantRole role = new MonitorTenantRole();
        role.setId(18L);
        role.setTenantId(7L);
        role.setRoleKey("ops");
        when(roleMapper.selectOne(any())).thenReturn(role);
        when(memberMapper.selectCount(any())).thenReturn(1L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.delete(7L, 18L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(roleMapper, never()).deleteById(18L);
    }

    @Test
    void roleManagementRequiresOwner() {
        when(authorization.requireOwner(7L)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(7L, request(List.of("TEAM_MANAGE"))));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(roleMapper, never()).insert(any(MonitorTenantRole.class));
    }

    private MonitorTenantRoleRequest request(List<String> permissions) {
        MonitorTenantRoleRequest request = new MonitorTenantRoleRequest();
        request.setRoleKey("ops");
        request.setName("Operations");
        request.setPermissions(permissions);
        return request;
    }

    private MonitorTenantMember owner(Long adminId) {
        MonitorTenantMember member = new MonitorTenantMember();
        member.setTenantId(7L);
        member.setAdminId(adminId);
        member.setRole("OWNER");
        return member;
    }
}
