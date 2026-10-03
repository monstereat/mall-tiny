package com.macro.mall.tiny.modules.monitor.mapper;

import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorCronCheckInMapperTest {

    @Test
    void retentionQueriesSeparateFinishedAndStaleInProgressCheckIns() throws NoSuchMethodException {
        String completed = String.join(" ", MonitorCronCheckInMapper.class
                .getMethod("deleteCompletedBefore", java.util.Date.class, int.class)
                .getAnnotation(Delete.class).value());
        String inProgress = String.join(" ", MonitorCronCheckInMapper.class
                .getMethod("deleteStaleInProgressBefore", java.util.Date.class, int.class)
                .getAnnotation(Delete.class).value());

        assertTrue(completed.contains("completed_at < #{cutoff}"));
        assertTrue(completed.contains("LIMIT #{limit}"));
        assertFalse(completed.contains("status = 'in_progress'"));
        assertTrue(inProgress.contains("status = 'in_progress' AND started_at < #{cutoff}"));
        assertTrue(inProgress.contains("LIMIT #{limit}"));
        assertFalse(inProgress.contains("completed_at < #{cutoff}"));
    }
}
