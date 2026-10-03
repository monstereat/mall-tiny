package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorCronCheckInRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorCronRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorCronCheckInMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorCronMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorCron;
import com.macro.mall.tiny.modules.monitor.model.MonitorCronCheckIn;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorCronServiceTest {

    @Mock private MonitorCronMapper cronMapper;
    @Mock private MonitorCronCheckInMapper checkInMapper;
    @Mock private MonitorProjectService projectService;
    @Mock private MonitorProjectAccessService projectAccessService;
    @Mock private MonitorRateLimiter rateLimiter;

    private MonitorCronService service() {
        return new MonitorCronService(cronMapper, checkInMapper, projectService, projectAccessService, rateLimiter);
    }

    @Test
    void acceptsIntervalAndFiveFieldCronSchedules() {
        MonitorProject project = new MonitorProject();
        project.setId(9L);
        when(projectAccessService.requireProject("demo", true)).thenReturn(project);
        when(cronMapper.selectOne(any())).thenReturn(null);
        when(cronMapper.insert(any(MonitorCron.class))).thenAnswer(invocation -> {
            ((MonitorCron) invocation.getArgument(0)).setId(11L);
            return 1;
        });

        MonitorCron interval = service().create("demo", request("interval", "10m"));
        MonitorCron crontab = service().create("demo", request("crontab", "*/5 * * * *"));

        assertTrue(interval.getNextCheckinAt().after(new Date()));
        assertEquals(0, crontab.getNextCheckinAt().getTime() % 60_000);
        assertEquals("active", crontab.getStatus());
    }

    @Test
    void rejectsInvalidOrUnboundedSchedules() {
        MonitorProject project = new MonitorProject();
        project.setId(9L);
        when(projectAccessService.requireProject("demo", true)).thenReturn(project);

        MonitorCronRequest request = request("interval", "999w");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().create("demo", request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void checkInRetriesAreIdempotentAndCompletionUpdatesHealth() {
        MonitorProject project = new MonitorProject();
        project.setId(9L);
        when(projectService.validateIngestKey("demo", "key")).thenReturn(project);
        when(rateLimiter.tryAcquire(any())).thenReturn(true);
        MonitorCron cron = cronFixture();
        when(cronMapper.selectOne(any())).thenReturn(cron);
        MonitorCronCheckIn existing = new MonitorCronCheckIn();
        existing.setId(21L);
        existing.setCronId(11L);
        existing.setCheckinId("run-1");
        existing.setStatus("in_progress");
        existing.setStartedAt(new Date(System.currentTimeMillis() - 25));
        when(checkInMapper.selectOne(any())).thenReturn(null, existing);
        when(checkInMapper.insert(any(MonitorCronCheckIn.class))).thenAnswer(invocation -> {
            ((MonitorCronCheckIn) invocation.getArgument(0)).setId(21L);
            return 1;
        });
        when(checkInMapper.update(any(MonitorCronCheckIn.class), any())).thenReturn(1);

        MonitorCronCheckInRequest start = new MonitorCronCheckInRequest();
        start.setStatus("in_progress");
        start.setCheckinId("run-1");
        MonitorCronCheckIn created = service().startCheckIn("demo", "key", "nightly", "run-1", start);
        MonitorCronCheckIn duplicate = service().startCheckIn("demo", "key", "nightly", "run-1", start);

        assertEquals(created.getId(), duplicate.getId());
        MonitorCronCheckInRequest finish = new MonitorCronCheckInRequest();
        finish.setStatus("ok");
        MonitorCronCheckIn result = service().finishCheckIn("demo", "key", "nightly", "run-1", finish);

        assertEquals("ok", result.getStatus());
        assertNotNull(result.getCompletedAt());
        assertEquals(1, cron.getConsecutiveSuccesses());
        assertEquals("ok", cron.getHealthStatus());
        verify(cronMapper, org.mockito.Mockito.times(2)).updateById(cron);
    }

    @Test
    void marksMissedCheckInsAndAdvancesTheNextSchedule() {
        MonitorCron cron = cronFixture();
        cron.setNextCheckinAt(new Date(System.currentTimeMillis() - 600_000));
        cron.setCheckinMarginSeconds(60);
        when(cronMapper.selectList(any())).thenReturn(List.of(cron));

        service().evaluateSchedules();

        assertEquals("missed", cron.getLastCheckinStatus());
        assertEquals(1, cron.getConsecutiveFailures());
        assertEquals("error", cron.getHealthStatus());
        assertTrue(cron.getNextCheckinAt().after(new Date(System.currentTimeMillis() - 60_000)));
        verify(cronMapper).update(org.mockito.ArgumentMatchers.eq(cron), any());
    }

    @Test
    void timesOutOverdueRunningCheckIns() {
        MonitorCron cron = cronFixture();
        cron.setMaxRuntimeSeconds(60);
        MonitorCronCheckIn checkIn = new MonitorCronCheckIn();
        checkIn.setId(21L);
        checkIn.setCronId(cron.getId());
        checkIn.setStatus("in_progress");
        checkIn.setStartedAt(new Date(System.currentTimeMillis() - 180_000));
        when(cronMapper.selectList(any())).thenReturn(List.of());
        when(checkInMapper.selectList(any())).thenReturn(List.of(checkIn));
        when(cronMapper.selectById(cron.getId())).thenReturn(cron);
        when(checkInMapper.update(any(MonitorCronCheckIn.class), any())).thenReturn(1);

        service().evaluateSchedules();

        assertEquals("timed_out", checkIn.getStatus());
        assertEquals("timed_out", cron.getLastCheckinStatus());
        assertEquals(1, cron.getConsecutiveFailures());
        assertEquals("error", cron.getHealthStatus());
        verify(cronMapper).updateById(cron);
    }

    private MonitorCronRequest request(String type, String schedule) {
        MonitorCronRequest request = new MonitorCronRequest();
        request.setName("Nightly job");
        request.setSlug("nightly");
        request.setScheduleType(type);
        request.setSchedule(schedule);
        request.setTimezone("UTC");
        return request;
    }

    private MonitorCron cronFixture() {
        MonitorCron cron = new MonitorCron();
        cron.setId(11L);
        cron.setProjectId(9L);
        cron.setSlug("nightly");
        cron.setScheduleType("interval");
        cron.setSchedule("10m");
        cron.setTimezone("UTC");
        cron.setCheckinMarginSeconds(60);
        cron.setMaxRuntimeSeconds(1800);
        cron.setFailureThreshold(1);
        cron.setRecoveryThreshold(1);
        cron.setConsecutiveFailures(0);
        cron.setConsecutiveSuccesses(0);
        cron.setStatus("active");
        cron.setHealthStatus("unknown");
        cron.setNextCheckinAt(new Date(System.currentTimeMillis() + 600_000));
        return cron;
    }
}
