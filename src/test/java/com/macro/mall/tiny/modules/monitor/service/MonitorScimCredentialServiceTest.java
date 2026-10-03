package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorScimTokenMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorScimToken;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorScimCredentialServiceTest {

    @Mock private MonitorScimTokenMapper tokenMapper;
    @Mock private MonitorTenantMemberMapper memberMapper;
    @Mock private MonitorTenantAuditLogMapper auditLogMapper;
    @Mock private MonitorProjectAccessService accessService;
    @Mock private MonitorTenantAuthorizationService authorization;

    @BeforeEach
    void initializeMyBatisPlusLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "scim-token-test"),
                MonitorScimToken.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "scim-member-test"),
                MonitorTenantMember.class);
    }

    @Test
    void revokeIsTenantScopedAndIdempotent() {
        Long tenantId = 17L;
        Long tokenId = 81L;
        when(authorization.requirePermission(tenantId, "SCIM_MANAGE")).thenReturn(owner(tenantId, 9L));
        MonitorScimToken token = token(tenantId, tokenId, null);
        when(tokenMapper.selectOne(any())).thenReturn(token);

        service().revokeToken(tenantId, tokenId);
        service().revokeToken(tenantId, tokenId);

        assertNotNull(token.getRevokedAt());
        verify(tokenMapper, times(1)).updateById(token);
        verify(auditLogMapper, times(1)).insert(any(MonitorTenantAuditLog.class));
    }

    @Test
    void cannotRevokeTokenOwnedByAnotherTenant() {
        Long tenantId = 17L;
        Long tokenId = 81L;
        when(authorization.requirePermission(tenantId, "SCIM_MANAGE")).thenReturn(owner(tenantId, 9L));
        when(tokenMapper.selectOne(any())).thenReturn(null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().revokeToken(tenantId, tokenId));

        assertEquals(404, error.getStatusCode().value());
        ArgumentCaptor<LambdaQueryWrapper<MonitorScimToken>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tokenMapper).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(query.getValue().getSqlSegment().contains("id"));
        verify(tokenMapper, never()).updateById(any(MonitorScimToken.class));
        verify(auditLogMapper, never()).insert(any(MonitorTenantAuditLog.class));
    }

    private MonitorScimCredentialService service() {
        return new MonitorScimCredentialService(tokenMapper, auditLogMapper, accessService, authorization, new ObjectMapper());
    }

    private MonitorTenantMember owner(Long tenantId, Long adminId) {
        MonitorTenantMember member = new MonitorTenantMember();
        member.setTenantId(tenantId);
        member.setAdminId(adminId);
        member.setRole("OWNER");
        return member;
    }

    private MonitorScimToken token(Long tenantId, Long tokenId, java.util.Date revokedAt) {
        MonitorScimToken token = new MonitorScimToken();
        token.setId(tokenId);
        token.setTenantId(tenantId);
        token.setName("directory sync");
        token.setRevokedAt(revokedAt);
        return token;
    }
}
