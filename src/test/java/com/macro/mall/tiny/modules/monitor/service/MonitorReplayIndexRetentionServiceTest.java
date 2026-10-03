package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorReplayIndexRetentionServiceTest {

    private final MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);
    private final MonitorReplayQuotaService quotaService = mock(MonitorReplayQuotaService.class);
    private final MonitorReplayIndexRetentionService service =
            new MonitorReplayIndexRetentionService(replayMapper, quotaService);

    @Test
    void removesObjectsAndUpdatesQuotaBeforeDeletingExpiredIndexes() throws Exception {
        configure(30, 500, 10);
        List<Map<String, Object>> expired = List.of(replay(12L, "project-a", "project-a/r/s/12.json"));
        when(replayMapper.selectExpiredBatch(org.mockito.ArgumentMatchers.any(Date.class), org.mockito.ArgumentMatchers.eq(500)))
                .thenReturn(expired, List.of());
        when(replayMapper.deleteById(12L)).thenReturn(1);

        Instant now = Instant.parse("2026-10-02T12:00:00Z");
        int deleted = service.cleanupExpiredIndexes(now);

        assertEquals(1, deleted);
        Date expectedCutoff = Date.from(Instant.parse("2026-09-02T12:00:00Z"));
        verify(replayMapper).selectExpiredBatch(expectedCutoff, 500);
        InOrder order = inOrder(quotaService, replayMapper);
        order.verify(quotaService).removeObject("monitor-replays", "project-a", "project-a/r/s/12.json");
        order.verify(replayMapper).deleteById(12L);
    }

    @Test
    void keepsIndexForRetryWhenObjectDeletionFails() throws Exception {
        configure(30, 500, 10);
        when(replayMapper.selectExpiredBatch(org.mockito.ArgumentMatchers.any(Date.class), org.mockito.ArgumentMatchers.eq(500)))
                .thenReturn(List.of(replay(12L, "project-a", "project-a/r/s/12.json")));
        org.mockito.Mockito.doThrow(new IllegalStateException("object store unavailable"))
                .when(quotaService).removeObject("monitor-replays", "project-a", "project-a/r/s/12.json");

        assertThrows(IllegalStateException.class,
                () -> service.cleanupExpiredIndexes(Instant.parse("2026-10-02T12:00:00Z")));

        verify(replayMapper, never()).deleteById(12L);
    }

    @Test
    void retriesIndexDeletionAfterAnObjectWasAlreadyRemoved() throws Exception {
        configure(30, 500, 10);
        Map<String, Object> replay = replay(12L, "project-a", "project-a/r/s/12.json");
        when(replayMapper.selectExpiredBatch(org.mockito.ArgumentMatchers.any(Date.class), org.mockito.ArgumentMatchers.eq(500)))
                .thenReturn(List.of(replay), List.of(replay), List.of());
        when(replayMapper.deleteById(12L)).thenThrow(new IllegalStateException("database unavailable")).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.cleanupExpiredIndexes(Instant.parse("2026-10-02T12:00:00Z")));
        assertEquals(1, service.cleanupExpiredIndexes(Instant.parse("2026-10-02T12:00:00Z")));

        verify(quotaService, org.mockito.Mockito.times(2))
                .removeObject("monitor-replays", "project-a", "project-a/r/s/12.json");
        verify(replayMapper, org.mockito.Mockito.times(2)).deleteById(12L);
    }

    @Test
    void capsWorkPerRunWhenEveryBatchIsFull() throws Exception {
        configure(30, 2, 2);
        List<Map<String, Object>> batch = List.of(
                replay(1L, "project-a", "project-a/r/s/1.json"),
                replay(2L, "project-a", "project-a/r/s/2.json"));
        when(replayMapper.selectExpiredBatch(org.mockito.ArgumentMatchers.any(Date.class), org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(batch, batch);
        when(replayMapper.deleteById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        int deleted = service.cleanupExpiredIndexes(Instant.parse("2026-10-02T12:00:00Z"));

        assertEquals(4, deleted);
        verify(replayMapper, org.mockito.Mockito.times(2))
                .selectExpiredBatch(org.mockito.ArgumentMatchers.any(Date.class), org.mockito.ArgumentMatchers.eq(2));
    }

    private void configure(int retentionDays, int batchSize, int maxBatchesPerRun) {
        ReflectionTestUtils.setField(service, "replayBucket", "monitor-replays");
        ReflectionTestUtils.setField(service, "retentionDays", retentionDays);
        ReflectionTestUtils.setField(service, "batchSize", batchSize);
        ReflectionTestUtils.setField(service, "maxBatchesPerRun", maxBatchesPerRun);
    }

    private Map<String, Object> replay(long id, String projectKey, String objectKey) {
        return Map.of("id", id, "projectKey", projectKey, "objectKey", objectKey);
    }
}
