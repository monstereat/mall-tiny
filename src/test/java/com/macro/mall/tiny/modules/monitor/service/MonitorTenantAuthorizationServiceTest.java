package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantRoleMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantRole;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorTenantAuthorizationServiceTest {
    private final MonitorTenantMemberMapper memberMapper = mock(MonitorTenantMemberMapper.class);
    private final MonitorTenantRoleMapper roleMapper = mock(MonitorTenantRoleMapper.class);
    private final MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
    private final MonitorTenantAuthorizationService service = new MonitorTenantAuthorizationService(
            memberMapper, roleMapper, accessService, new ObjectMapper());

    @BeforeEach
    void initializeMyBatisPlusLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "tenant-auth-member-test"),
                MonitorTenantMember.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "tenant-auth-role-test"),
                MonitorTenantRole.class);
    }

    @Test
    void customRoleGrantsOnlyItsFixedCapabilities() {
        when(accessService.currentAdminId()).thenReturn(84L);
        MonitorTenantMember member = membership(84L, "MEMBER", 12L);
        when(memberMapper.selectOne(any())).thenReturn(member);
        MonitorTenantRole role = new MonitorTenantRole();
        role.setTenantId(7L);
        role.setId(12L);
        role.setPermissionsJson("[\"TEAM_MANAGE\",\"TENANT_MEMBER_MANAGE\"]");
        when(roleMapper.selectOne(any())).thenReturn(role);

        assertEquals(member, service.requirePermission(7L, "TEAM_MANAGE"));
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> service.requirePermission(7L, "SCIM_MANAGE"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> service.requireOwner(7L));
    }

    @Test
    void builtInOwnerHasPermissionsButCustomRoleCanNeverBeOwner() {
        when(accessService.currentAdminId()).thenReturn(42L);
        when(memberMapper.selectOne(any())).thenReturn(membership(42L, "OWNER", null));

        assertEquals("OWNER", service.requirePermission(7L, "SCIM_MANAGE").getRole());
        assertEquals("OWNER", service.requireOwner(7L).getRole());
        verify(roleMapper, never()).selectOne(any());
    }

    private MonitorTenantMember membership(Long adminId, String role, Long customRoleId) {
        MonitorTenantMember member = new MonitorTenantMember();
        member.setTenantId(7L);
        member.setAdminId(adminId);
        member.setRole(role);
        member.setCustomRoleId(customRoleId);
        return member;
    }
}
