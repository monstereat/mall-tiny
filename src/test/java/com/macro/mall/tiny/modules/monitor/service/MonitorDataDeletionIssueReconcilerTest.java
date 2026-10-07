package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionItemMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionJobMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionItem;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonitorDataDeletionIssueReconcilerTest {
    private final MonitorDataDeletionItemMapper itemMapper = mock(MonitorDataDeletionItemMapper.class);
    private final MonitorIssueMapper issueMapper = mock(MonitorIssueMapper.class);
    private final MonitorDataDeletionJobMapper jobMapper = mock(MonitorDataDeletionJobMapper.class);
    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorDataDeletionIssueReconciler reconciler = new MonitorDataDeletionIssueReconciler(
            itemMapper, issueMapper, jobMapper, clickHouse);

    @Test
    void subtractsSnapshotDeletionCountFromLifetimeIssueCountAndCheckpoints() {
        MonitorDataDeletionJob job = job();
        MonitorDataDeletionItem fingerprint = new MonitorDataDeletionItem();
        fingerprint.setId(7L);
        fingerprint.setItemValue("fingerprint-a");
        when(itemMapper.selectList(any())).thenReturn(List.of(fingerprint));
        when(clickHouse.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of(Map.of(
                "event_count", 2L,
                "affected_users", 1L,
                "first_seen", new Date(1_700_000_000_000L),
                "last_seen", new Date(1_700_000_001_000L),
                "latest_release", "1.2.3")));
        MonitorIssue issue = new MonitorIssue();
        issue.setId(9L);
        issue.setProjectId(2L);
        issue.setFingerprint("fingerprint-a");
        issue.setEventCount(10L);
        when(issueMapper.selectOne(any())).thenReturn(issue);
        when(itemMapper.selectCount(any())).thenReturn(3L);

        assertTrue(reconciler.reconcileNext(job));

        assertEquals(7L, issue.getEventCount());
        assertEquals("1.2.3", issue.getLatestRelease());
        assertEquals("7", job.getCursorValue());
        verify(issueMapper).updateById(issue);
        verify(jobMapper).updateById(job);
    }

    @Test
    void returnsFalseWhenNoFingerprintRemainsAfterCheckpoint() {
        when(itemMapper.selectList(any())).thenReturn(List.of());
        assertFalse(reconciler.reconcileNext(job()));
        verifyNoInteractions(issueMapper, jobMapper, clickHouse);
    }

    private MonitorDataDeletionJob job() {
        MonitorDataDeletionJob job = new MonitorDataDeletionJob();
        job.setId(1L);
        job.setProjectId(2L);
        job.setProjectKey("demo");
        job.setStatus("RUNNING");
        return job;
    }
}
