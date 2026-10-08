package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionItemMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionJobMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitorDataDeletionAuditServiceTest {

    @Test
    void completionRemovesPerRecordSnapshotsAndPersonalIdentifierButKeepsAuditSummary() {
        MonitorDataDeletionItemMapper itemMapper = mock(MonitorDataDeletionItemMapper.class);
        MonitorDataDeletionJobMapper jobMapper = mock(MonitorDataDeletionJobMapper.class);
        MonitorDataDeletionAuditService service = new MonitorDataDeletionAuditService(itemMapper, jobMapper);
        MonitorDataDeletionJob job = new MonitorDataDeletionJob();
        job.setId(17L);
        job.setUserId("synthetic-user");
        job.setPreviewCountsJson("{\"error_event\":2}");
        job.setDeletedCountsJson("{\"error_event\":2}");
        job.setStatus("RUNNING");
        job.setStage("CLEAN_REDIS_DEDUP");

        service.complete(job);

        assertNull(job.getUserId());
        assertNull(job.getCursorValue());
        assertEquals("COMPLETED", job.getStatus());
        assertEquals("COMPLETE", job.getStage());
        assertNotNull(job.getFinishedAt());
        assertEquals("{\"error_event\":2}", job.getDeletedCountsJson());
        verify(itemMapper).deleteByJobId(17L);
        verify(jobMapper).completeAudit(17L, job.getFinishedAt());
        verifyNoMoreInteractions(jobMapper);
    }
}
