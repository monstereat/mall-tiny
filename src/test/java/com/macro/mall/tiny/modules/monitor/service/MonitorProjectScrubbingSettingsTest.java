package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDataScrubbingRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantAuditLogMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorProjectScrubbingSettingsTest {

    private static final String CACHE_KEY = "monitor:project:auth:v2:project-a";

    @Test
    void cachesProjectPiiSettingsAlongsideIngestCredentials() {
        MonitorProjectMapper projectMapper = mock(MonitorProjectMapper.class);
        MonitorTenantAuditLogMapper auditMapper = mock(MonitorTenantAuditLogMapper.class);
        MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        MonitorProject project = project();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(CACHE_KEY)).thenReturn(null, "17|ingest-hash|release-hash|1|1|1|0|0|");
        when(projectMapper.selectOne(any())).thenReturn(project);
        doNothing().when(values).set(eq(CACHE_KEY), anyString(), eq(Duration.ofMinutes(10)));

        MonitorProjectService service = new MonitorProjectService(projectMapper, auditMapper, accessService, redis,
                new ObjectMapper());

        service.getActiveProject("project-a");
        MonitorProject cached = service.getActiveProject("project-a");

        assertTrue(Boolean.TRUE.equals(cached.getScrubEmails()));
        assertTrue(Boolean.TRUE.equals(cached.getScrubCreditCards()));
        assertTrue(Boolean.TRUE.equals(cached.getScrubIpAddresses()));
        assertEquals(false, cached.getScrubPhoneNumbers());
        assertEquals(false, cached.getScrubChineseIdNumbers());
        assertEquals("", cached.getCustomSensitiveFields());
        assertEquals("ingest-hash", cached.getIngestKeyHash());
        verify(projectMapper).selectOne(any());
    }

    @Test
    void readsCustomSensitiveNamesFromExtendedProjectCacheFormat() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(CACHE_KEY)).thenReturn("17|ingest-hash|release-hash|1|0|1|0|0|phoneNumber,bank-account");

        MonitorProject cached = new MonitorProjectService(mock(MonitorProjectMapper.class),
                mock(MonitorTenantAuditLogMapper.class), mock(MonitorProjectAccessService.class), redis,
                new ObjectMapper()).getActiveProject("project-a");

        assertEquals("phoneNumber,bank-account", cached.getCustomSensitiveFields());
    }

    @Test
    void rejectsInvalidOrNormalizationDuplicateFieldNamesBeforePersisting() {
        MonitorProjectMapper projectMapper = mock(MonitorProjectMapper.class);
        MonitorProject project = project();
        when(projectMapper.selectOne(any())).thenReturn(project);
        MonitorDataScrubbingRequest request = request(true, false);
        request.setCustomSensitiveFields(java.util.List.of("phoneNumber", "PHONE-NUMBER"));

        assertThrows(ResponseStatusException.class, () -> new MonitorProjectService(projectMapper,
                mock(MonitorTenantAuditLogMapper.class), mock(MonitorProjectAccessService.class),
                mock(StringRedisTemplate.class), new ObjectMapper()).updateScrubbingSettings("project-a", request));
        verify(projectMapper, org.mockito.Mockito.never()).updateById(any(MonitorProject.class));
    }

    @Test
    void updatingSettingsEvictsTheIngestProjectCache() throws Exception {
        MonitorProjectMapper projectMapper = mock(MonitorProjectMapper.class);
        MonitorTenantAuditLogMapper auditMapper = mock(MonitorTenantAuditLogMapper.class);
        MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MonitorProject project = project();
        project.setScrubIpAddresses(false);
        when(projectMapper.selectOne(any())).thenReturn(project);
        when(projectMapper.updateById(project)).thenReturn(1);
        when(redis.delete(CACHE_KEY)).thenReturn(true);
        MonitorDataScrubbingRequest request = new MonitorDataScrubbingRequest();
        request.setScrubEmails(true);
        request.setScrubCreditCards(false);
        request.setScrubIpAddresses(true);
        request.setScrubPhoneNumbers(true);
        request.setScrubChineseIdNumbers(true);
        request.setCustomSensitiveFields(java.util.List.of("phoneNumber", "national-id"));

        when(accessService.currentAdminId()).thenReturn(29L);
        MonitorProject updated = new MonitorProjectService(projectMapper, auditMapper, accessService, redis,
                new ObjectMapper())
                .updateScrubbingSettings("project-a", request);

        assertTrue(Boolean.TRUE.equals(updated.getScrubEmails()));
        assertEquals(false, updated.getScrubCreditCards());
        assertTrue(Boolean.TRUE.equals(updated.getScrubIpAddresses()));
        assertTrue(Boolean.TRUE.equals(updated.getScrubPhoneNumbers()));
        assertTrue(Boolean.TRUE.equals(updated.getScrubChineseIdNumbers()));
        assertEquals("phoneNumber,national-id", updated.getCustomSensitiveFields());
        verify(redis).delete(CACHE_KEY);
        var auditCaptor = forClass(MonitorTenantAuditLog.class);
        verify(auditMapper).insert(auditCaptor.capture());
        MonitorTenantAuditLog audit = auditCaptor.getValue();
        assertEquals(5L, audit.getTenantId());
        assertEquals(29L, audit.getActorAdminId());
        assertEquals("project.data_scrubbing_updated", audit.getAction());
        assertEquals("monitor_project", audit.getResourceType());
        assertEquals("17", audit.getResourceId());
        var details = new ObjectMapper().readTree(audit.getDetailJson());
        assertEquals(false, details.path("scrubIpAddresses").path("before").asBoolean());
        assertEquals(true, details.path("scrubIpAddresses").path("after").asBoolean());
        assertEquals(true, details.path("scrubPhoneNumbers").path("after").asBoolean());
        assertEquals(true, details.path("scrubChineseIdNumbers").path("after").asBoolean());
        assertEquals("phoneNumber", details.path("customSensitiveFields").path("after").get(0).asText());
        assertEquals("national-id", details.path("customSensitiveFields").path("after").get(1).asText());
    }

    @Test
    void cacheIsEvictedOnlyAfterScrubbingSettingsCommit() {
        MonitorProjectMapper projectMapper = mock(MonitorProjectMapper.class);
        MonitorTenantAuditLogMapper auditMapper = mock(MonitorTenantAuditLogMapper.class);
        MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MonitorProject project = project();
        when(projectMapper.selectOne(any())).thenReturn(project);
        when(accessService.currentAdminId()).thenReturn(29L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            new MonitorProjectService(projectMapper, auditMapper, accessService, redis, new ObjectMapper())
                    .updateScrubbingSettings("project-a", request(true, false));

            verify(redis, org.mockito.Mockito.never()).delete(CACHE_KEY);
            TransactionSynchronization synchronization = TransactionSynchronizationManager
                    .getSynchronizations().get(0);
            synchronization.afterCommit();
            verify(redis).delete(CACHE_KEY);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private MonitorDataScrubbingRequest request(boolean scrubEmails, boolean scrubCreditCards) {
        MonitorDataScrubbingRequest request = new MonitorDataScrubbingRequest();
        request.setScrubEmails(scrubEmails);
        request.setScrubCreditCards(scrubCreditCards);
        request.setScrubIpAddresses(false);
        request.setScrubPhoneNumbers(false);
        request.setScrubChineseIdNumbers(false);
        request.setCustomSensitiveFields(java.util.List.of());
        return request;
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setId(17L);
        project.setTenantId(5L);
        project.setProjectKey("project-a");
        project.setStatus(1);
        project.setIngestKeyHash("ingest-hash");
        project.setReleaseKeyHash("release-hash");
        project.setScrubEmails(true);
        project.setScrubCreditCards(true);
        project.setScrubIpAddresses(true);
        return project;
    }
}
