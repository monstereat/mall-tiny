package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionItemMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionJobMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionItem;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorDataDeletionLokiStageTest {

    private final MonitorProjectAccessService access = mock(MonitorProjectAccessService.class);
    private final MonitorProjectMapper projectMapper = mock(MonitorProjectMapper.class);
    private final MonitorDataDeletionJobMapper jobMapper = mock(MonitorDataDeletionJobMapper.class);
    private final MonitorDataDeletionItemMapper itemMapper = mock(MonitorDataDeletionItemMapper.class);
    private final MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);
    private final MonitorIssueMapper issueMapper = mock(MonitorIssueMapper.class);
    private final MonitorDataDeletionIssueReconciler issueReconciler = mock(MonitorDataDeletionIssueReconciler.class);
    private final MonitorErrorHourlyDeletionReconciler hourlyReconciler = mock(MonitorErrorHourlyDeletionReconciler.class);
    private final MonitorDataDeletionAuditService auditService = mock(MonitorDataDeletionAuditService.class);
    private final MonitorLokiDeletionClient loki = mock(MonitorLokiDeletionClient.class);
    private final org.springframework.data.redis.core.StringRedisTemplate redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    private final MonitorReplayQuotaService quotaService = mock(MonitorReplayQuotaService.class);
    private final org.springframework.jdbc.core.JdbcTemplate clickHouse = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final MonitorDataDeletionService service = new MonitorDataDeletionService(
            access, projectMapper, jobMapper, itemMapper, replayMapper, issueMapper, issueReconciler,
            hourlyReconciler, auditService, loki, redis, quotaService, new com.fasterxml.jackson.databind.ObjectMapper(), clickHouse);

    private final Instant start = Instant.parse("2026-10-01T00:00:00Z");
    private final Instant end = Instant.parse("2026-10-03T00:00:00Z");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "workerEnabled", true);
        ReflectionTestUtils.setField(service, "lokiPollDelayMs", 30_000L);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        MonitorProject project = new MonitorProject();
        project.setId(1L);
        project.setProjectKey("demo");
        when(access.requireProjectOwner("demo")).thenReturn(project);
    }

    @Test
    void acceptedRequestIsNotCompleteAndWorkerUsesDelayedDiscovery() {
        MonitorDataDeletionJob job = job("LOKI_DELETE_SUBMIT", "user-7");
        workerSelects(job);
        when(loki.listRequests()).thenReturn(List.of());

        service.processOneBatch();

        verify(loki).submitDelete(
                userQuery(),
                start, end);
        assertEquals("LOKI_DELETE_DISCOVER", job.getStage());
        assertEquals("RUNNING", job.getStatus());
        assertTrue(job.getLeaseUntil().after(new Date()));
        assertTrue(job.getLeaseUntil().getTime() - System.currentTimeMillis() >= 20_000L);
        assertTrue(job.getLeaseUntil().getTime() - System.currentTimeMillis() < 120_000L);
        verify(auditService, never()).complete(any());
    }

    @Test
    void requestEndIsCappedAtSnapshotStart() {
        MonitorDataDeletionJob job = job("LOKI_DELETE_SUBMIT", null);
        Instant snapshotStart = Instant.parse("2026-10-03T01:00:00Z");
        job.setStartedAt(Date.from(snapshotStart));
        job.setRangeEnd(Date.from(Instant.parse("2026-10-04T00:00:00Z")));
        workerSelects(job);
        when(loki.listRequests()).thenReturn(List.of());

        service.processOneBatch();

        verify(loki).submitDelete(projectQuery(), start, snapshotStart);
    }

    @Test
    void emptyEffectiveLokiRangeSkipsSubmission() {
        MonitorDataDeletionJob job = job("LOKI_DELETE_BASELINE", null);
        Instant snapshotStart = Instant.parse("2026-10-03T01:00:00Z");
        job.setStartedAt(Date.from(snapshotStart));
        job.setRangeStart(Date.from(snapshotStart.plusMillis(1)));
        workerSelects(job);

        service.processOneBatch();

        assertEquals("CLEAN_REDIS_DEDUP", job.getStage());
        verifyNoInteractions(loki);
    }

    @Test
    void submitStageRecoversAcceptedRequestFromLokiBeforePostingAgain() {
        MonitorDataDeletionJob job = job("LOKI_DELETE_SUBMIT", "user-7");
        workerSelects(job);
        Instant split = start.plusSeconds(86_400);
        when(loki.listRequests()).thenReturn(List.of(
                request("request-a", userQuery(), start, split, "received"),
                request("request-b", userQuery(), split, end, "received")));

        service.processOneBatch();

        verify(loki, never()).submitDelete(anyString(), any(), any());
        assertEquals("LOKI_DELETE_POLL", job.getStage());
        verify(itemMapper, times(2)).insert(argThat((MonitorDataDeletionItem item) -> "LOKI_DELETE_REQUEST_ID".equals(item.getItemType())));
    }

    @Test
    void partialMatchingRequestSubmitsOnlyTheUncoveredIntervals() {
        MonitorDataDeletionJob job = job("LOKI_DELETE_SUBMIT", "user-7");
        workerSelects(job);
        Instant partialStart = start.plusSeconds(43_200);
        Instant partialEnd = start.plusSeconds(129_600);
        when(loki.listRequests()).thenReturn(List.of(
                request("partial", userQuery(), partialStart, partialEnd, "received")));

        service.processOneBatch();

        verify(loki).submitDelete(userQuery(), start, partialStart);
        verify(loki).submitDelete(userQuery(), partialEnd, end);
        verify(loki, times(2)).submitDelete(anyString(), any(), any());
        assertEquals("LOKI_DELETE_DISCOVER", job.getStage());
        assertEquals("RUNNING", job.getStatus());
    }

    @Test
    void discoveryStageSubmitsMissingIntervalWhenOnlyPartialRequestIsVisible() {
        MonitorDataDeletionJob job = job("LOKI_DELETE_DISCOVER", "user-7");
        workerSelects(job);
        Instant partialStart = start.plusSeconds(43_200);
        Instant partialEnd = start.plusSeconds(129_600);
        when(loki.listRequests()).thenReturn(List.of(
                request("partial", userQuery(), partialStart, partialEnd, "received")));

        service.processOneBatch();

        verify(loki).submitDelete(userQuery(), start, partialStart);
        verify(loki).submitDelete(userQuery(), partialEnd, end);
        assertEquals("LOKI_DELETE_DISCOVER", job.getStage());
        assertTrue(job.getLeaseUntil().after(new Date()));
    }

    @Test
    void allSplitRequestsMustBeProcessedBeforeLeavingLokiStage() {
        MonitorDataDeletionJob job = job("LOKI_DELETE_POLL", null);
        List<MonitorDataDeletionItem> stored = List.of(item("LOKI_DELETE_REQUEST_ID", "request-a"),
                item("LOKI_DELETE_REQUEST_ID", "request-b"));
        when(itemMapper.selectList(any())).thenReturn(stored);
        Instant split = start.plusSeconds(86_400);
        workerSelects(job);
        when(loki.listRequests()).thenReturn(List.of(
                request("request-a", projectQuery(), start, split, "processed"),
                request("request-b", projectQuery(), split, end, "received")));

        service.processOneBatch();

        assertEquals("LOKI_DELETE_POLL", job.getStage());
        assertEquals("RUNNING", job.getStatus());
        assertTrue(job.getLeaseUntil().after(new Date()));
        verify(auditService, never()).complete(any());

        job.setLeaseUntil(Date.from(Instant.now().minusSeconds(1)));
        when(loki.listRequests()).thenReturn(List.of(
                request("request-a", projectQuery(), start, split, "processed"),
                request("request-b", projectQuery(), split, end, "processed")));
        service.processOneBatch();

        assertEquals("CLEAN_REDIS_DEDUP", job.getStage());
        assertEquals("QUEUED", job.getStatus());
        verify(auditService, never()).complete(any());
    }

    @Test
    void listingFailureMarksJobFailedAndRetryResumesSameStage() {
        MonitorDataDeletionJob job = job("LOKI_DELETE_POLL", null);
        workerSelects(job);
        when(loki.listRequests()).thenThrow(new IllegalStateException("Loki unavailable"));

        service.processOneBatch();

        assertEquals("FAILED", job.getStatus());
        assertEquals("LOKI_DELETE_POLL", job.getStage());
        assertEquals("Loki unavailable", job.getErrorMessage());

        when(jobMapper.selectById(9L)).thenReturn(job);
        service.retry("demo", 9L);
        assertEquals("QUEUED", job.getStatus());
        assertEquals("LOKI_DELETE_POLL", job.getStage());
        assertNull(job.getErrorMessage());
    }

    @Test
    void baselineExcludesOlderMatchingRequestsFromTheCurrentJob() {
        MonitorDataDeletionJob job = job("LOKI_DELETE_BASELINE", null);
        workerSelects(job);
        when(loki.listRequests()).thenReturn(List.of(request("old", projectQuery(), start, end, "processed")));

        service.processOneBatch();

        verify(itemMapper).insert(argThat((MonitorDataDeletionItem item) -> "LOKI_BASELINE_ID".equals(item.getItemType())
                && "old".equals(item.getItemValue())));
        assertEquals("LOKI_DELETE_SUBMIT", job.getStage());
    }

    @Test
    void executeRejectsConcurrentActiveDeletionForSameProject() {
        when(jobMapper.countActiveForProject(1L)).thenReturn(1L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.execute("demo", 3L, "preview-token"));

        assertEquals(409, error.getStatusCode().value());
        verify(jobMapper, never()).selectById(anyLong());
    }

    @Test
    void finalCleanupCannotCompleteAJobWithoutLokiCompletionMarker() {
        MonitorDataDeletionJob job = job("CLEAN_REDIS_DEDUP", null);
        when(itemMapper.selectCount(any())).thenReturn(0L);

        ReflectionTestUtils.invokeMethod(service, "cleanRedisDedupBatch", job);

        assertEquals("LOKI_DELETE_BASELINE", job.getStage());
        verify(auditService, never()).complete(any());
        verifyNoInteractions(redis);
    }

    private void workerSelects(MonitorDataDeletionJob job) {
        job.setId(9L);
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.claim(9L)).thenReturn(1);
    }

    private MonitorDataDeletionJob job(String stage, String userId) {
        MonitorDataDeletionJob job = new MonitorDataDeletionJob();
        job.setId(9L);
        job.setProjectId(1L);
        job.setProjectKey("demo");
        job.setUserId(userId);
        job.setRangeStart(Date.from(start));
        job.setRangeEnd(Date.from(end));
        job.setStartedAt(Date.from(Instant.parse("2026-10-03T01:00:00Z")));
        job.setStage(stage);
        job.setStatus("RUNNING");
        job.setLeaseUntil(Date.from(Instant.now().minusSeconds(1)));
        return job;
    }

    private MonitorDataDeletionItem item(String type, String value) {
        MonitorDataDeletionItem item = new MonitorDataDeletionItem();
        item.setId((long) value.hashCode());
        item.setJobId(9L);
        item.setItemType(type);
        item.setItemValue(value);
        return item;
    }

    private MonitorLokiDeleteRequest request(String id, String query, Instant start, Instant end, String status) {
        return new MonitorLokiDeleteRequest(id, query, start, end, Instant.now(), status,
                "processed".equals(status) ? 100 : 0);
    }

    private String projectQuery() {
        return "{service_name=\"observability-platform\"} | monitor_project=\"demo\"";
    }

    private String userQuery() {
        return projectQuery() + " | monitor_user_id=\"user-7\"";
    }
}
