package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorReplayIndexRetentionServiceTest {

    private final MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);
    private final MonitorReplayIndexRetentionService service = new MonitorReplayIndexRetentionService(replayMapper);

    @Test
    void deletesExpiredRowsInBatchesUsingTheConfiguredRetentionCutoff() {
        ReflectionTestUtils.setField(service, "retentionDays", 30);
        ReflectionTestUtils.setField(service, "batchSize", 500);
        ReflectionTestUtils.setField(service, "maxBatchesPerRun", 10);
        when(replayMapper.deleteExpiredBatch(org.mockito.ArgumentMatchers.any(Date.class), org.mockito.ArgumentMatchers.eq(500)))
                .thenReturn(500, 125);

        Instant now = Instant.parse("2026-10-02T12:00:00Z");
        int deleted = service.cleanupExpiredIndexes(now);

        assertEquals(625, deleted);
        Date expectedCutoff = Date.from(Instant.parse("2026-09-02T12:00:00Z"));
        verify(replayMapper, org.mockito.Mockito.times(2)).deleteExpiredBatch(expectedCutoff, 500);
    }

    @Test
    void capsWorkPerRunWhenEveryBatchIsFull() {
        ReflectionTestUtils.setField(service, "retentionDays", 30);
        ReflectionTestUtils.setField(service, "batchSize", 100);
        ReflectionTestUtils.setField(service, "maxBatchesPerRun", 2);
        when(replayMapper.deleteExpiredBatch(org.mockito.ArgumentMatchers.any(Date.class), org.mockito.ArgumentMatchers.eq(100)))
                .thenReturn(100);

        int deleted = service.cleanupExpiredIndexes(Instant.parse("2026-10-02T12:00:00Z"));

        assertEquals(200, deleted);
        verify(replayMapper, org.mockito.Mockito.times(2))
                .deleteExpiredBatch(org.mockito.ArgumentMatchers.any(Date.class), org.mockito.ArgumentMatchers.eq(100));
    }
}
