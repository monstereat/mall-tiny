package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionItem;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MonitorErrorHourlyDeletionReconcilerTest {
    private static final String INSERT_SQL = "INSERT INTO monitor.error_hourly_correction " +
            "(deletion_job_id, correction_id, bucket, project_id, fingerprint, event_count) VALUES (?, ?, toDateTime(?), ?, ?, ?)";

    @Test
    void retryAfterClickHouseAcceptedInsertButClientLostResponseReusesSameCorrectionKey() {
        JdbcTemplate clickHouse = mock(JdbcTemplate.class);
        MonitorErrorHourlyDeletionReconciler reconciler = new MonitorErrorHourlyDeletionReconciler(clickHouse, new ObjectMapper());
        MonitorDataDeletionJob job = new MonitorDataDeletionJob();
        job.setId(51L);
        job.setProjectKey("synthetic-project");
        MonitorDataDeletionItem item = new MonitorDataDeletionItem();
        item.setId(9007L);
        item.setItemValue("{\"eventId\":\"synthetic-event\",\"bucketEpoch\":1790931600,\"fingerprint\":\"synthetic-fingerprint\",\"eventCount\":3}");
        List<MonitorDataDeletionItem> snapshot = List.of(item);
        doThrow(new RuntimeException("simulated lost response after server insert"))
                .doReturn(new int[]{1}).when(clickHouse).batchUpdate(eq(INSERT_SQL), anyList());

        try {
            reconciler.reconcile(job, snapshot);
        } catch (RuntimeException expected) {
            assertEquals("simulated lost response after server insert", expected.getMessage());
        }
        reconciler.reconcile(job, snapshot);

        var calls = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(clickHouse, org.mockito.Mockito.times(2)).batchUpdate(eq(INSERT_SQL), calls.capture());
        List<?> firstBatch = calls.getAllValues().get(0);
        List<?> retriedBatch = calls.getAllValues().get(1);
        Object[] first = (Object[]) firstBatch.get(0);
        Object[] retry = (Object[]) retriedBatch.get(0);
        assertEquals(first.length, retry.length);
        for (int index = 0; index < first.length; index++) {
            assertEquals(first[index], retry[index]);
        }
        assertEquals(51L, first[0]);
        assertEquals(9007L, first[1]);
        assertEquals(1790931600L, first[2]);
        assertEquals("synthetic-project", first[3]);
        assertEquals("synthetic-fingerprint", first[4]);
        assertEquals(-3L, first[5]);
    }
}
