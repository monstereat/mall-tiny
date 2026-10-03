package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorCronCheckInRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorCronRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorCronCheckInMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorCronMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorCron;
import com.macro.mall.tiny.modules.monitor.model.MonitorCronCheckIn;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MonitorCronService {

    private static final Pattern INTERVAL = Pattern.compile("([1-9][0-9]*)(s|m|h|d|w)");
    private static final long MAX_INTERVAL_SECONDS = 28L * 24 * 60 * 60;
    private static final int CHECK_IN_RETENTION_DAYS = 90;
    private static final int CLEANUP_BATCH_SIZE = 1000;
    private static final int MAX_CLEANUP_BATCHES = 10;

    private final MonitorCronMapper cronMapper;
    private final MonitorCronCheckInMapper checkInMapper;
    private final MonitorProjectService projectService;
    private final MonitorProjectAccessService projectAccessService;
    private final MonitorRateLimiter rateLimiter;

    public List<MonitorCron> list(String projectKey) {
        MonitorProject project = projectAccessService.requireProject(projectKey, false);
        return cronMapper.selectList(Wrappers.<MonitorCron>lambdaQuery()
                .eq(MonitorCron::getProjectId, project.getId())
                .orderByAsc(MonitorCron::getName));
    }

    @Transactional
    public MonitorCron create(String projectKey, MonitorCronRequest request) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        validateSchedule(request.getScheduleType(), request.getSchedule(), request.getTimezone());
        MonitorCron existing = cronMapper.selectOne(Wrappers.<MonitorCron>lambdaQuery()
                .eq(MonitorCron::getProjectId, project.getId())
                .eq(MonitorCron::getSlug, request.getSlug())
                .last("LIMIT 1"));
        if (existing != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cron monitor slug already exists");
        }
        MonitorCron cron = new MonitorCron();
        apply(cron, request);
        cron.setProjectId(project.getId());
        cron.setHealthStatus("unknown");
        cron.setConsecutiveFailures(0);
        cron.setConsecutiveSuccesses(0);
        cron.setNextCheckinAt(nextCheckIn(cron, new Date()));
        cronMapper.insert(cron);
        return cron;
    }

    @Transactional
    public MonitorCron update(String projectKey, Long cronId, MonitorCronRequest request) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        validateSchedule(request.getScheduleType(), request.getSchedule(), request.getTimezone());
        MonitorCron cron = requireCron(project.getId(), cronId);
        MonitorCron duplicate = cronMapper.selectOne(Wrappers.<MonitorCron>lambdaQuery()
                .eq(MonitorCron::getProjectId, project.getId())
                .eq(MonitorCron::getSlug, request.getSlug())
                .ne(MonitorCron::getId, cronId)
                .last("LIMIT 1"));
        if (duplicate != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cron monitor slug already exists");
        }
        apply(cron, request);
        cron.setNextCheckinAt(nextCheckIn(cron, cron.getLastCheckinAt() == null
                ? new Date() : cron.getLastCheckinAt()));
        cronMapper.updateById(cron);
        return cron;
    }

    @Transactional
    public void delete(String projectKey, Long cronId) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        cronMapper.delete(Wrappers.<MonitorCron>lambdaQuery()
                .eq(MonitorCron::getProjectId, project.getId())
                .eq(MonitorCron::getId, cronId));
    }

    public List<MonitorCronCheckIn> checkIns(String projectKey, Long cronId, int limit) {
        MonitorProject project = projectAccessService.requireProject(projectKey, false);
        requireCron(project.getId(), cronId);
        int safeLimit = Math.max(1, Math.min(200, limit));
        return checkInMapper.selectList(Wrappers.<MonitorCronCheckIn>lambdaQuery()
                .eq(MonitorCronCheckIn::getCronId, cronId)
                .orderByDesc(MonitorCronCheckIn::getId)
                .last("LIMIT " + safeLimit));
    }

    @Transactional
    public MonitorCronCheckIn startCheckIn(String projectKey, String ingestKey, String slug,
                                           String requestedCheckInId, MonitorCronCheckInRequest request) {
        if (!"in_progress".equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start check-in status must be in_progress");
        }
        MonitorProject project = projectService.validateIngestKey(projectKey, ingestKey);
        acquireRateLimit(projectKey);
        MonitorCron cron = requireActiveCron(project.getId(), slug);
        String checkInId = requestedCheckInId == null || requestedCheckInId.isBlank()
                ? UUID.randomUUID().toString() : requestedCheckInId;
        if (checkInId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "check-in ID is too long");
        }
        MonitorCronCheckIn existing = findCheckIn(cron.getId(), checkInId);
        if (existing != null) {
            return existing;
        }
        Date now = new Date();
        MonitorCronCheckIn checkIn = new MonitorCronCheckIn();
        checkIn.setCronId(cron.getId());
        checkIn.setCheckinId(checkInId);
        checkIn.setStatus("in_progress");
        checkIn.setEnvironment(request.getEnvironment() == null || request.getEnvironment().isBlank()
                ? "production" : request.getEnvironment());
        checkIn.setStartedAt(now);
        checkIn.setMessage(truncate(request.getMessage(), 512));
        try {
            checkInMapper.insert(checkIn);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            MonitorCronCheckIn alreadyCreated = findCheckIn(cron.getId(), checkInId);
            if (alreadyCreated != null) return alreadyCreated;
            throw duplicate;
        }
        cron.setLastCheckinAt(now);
        cron.setLastCheckinStatus("in_progress");
        if (!"error".equals(cron.getHealthStatus()) && !"missed".equals(cron.getHealthStatus())) {
            cron.setHealthStatus("in_progress");
        }
        cron.setNextCheckinAt(nextCheckIn(cron, now));
        cronMapper.updateById(cron);
        return checkIn;
    }

    @Transactional
    public MonitorCronCheckIn finishCheckIn(String projectKey, String ingestKey, String slug,
                                            String checkInId, MonitorCronCheckInRequest request) {
        MonitorProject project = projectService.validateIngestKey(projectKey, ingestKey);
        acquireRateLimit(projectKey);
        MonitorCron cron = requireActiveCron(project.getId(), slug);
        MonitorCronCheckIn checkIn = findCheckIn(cron.getId(), checkInId);
        if (checkIn == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "check-in not found");
        }
        String status = request.getStatus();
        if (!"ok".equals(status) && !"error".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "finished check-in status must be ok or error");
        }
        if (!"in_progress".equals(checkIn.getStatus())) {
            if (status.equals(checkIn.getStatus())) return checkIn;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "check-in is already finished");
        }
        Date now = new Date();
        checkIn.setStatus(status);
        checkIn.setCompletedAt(now);
        checkIn.setDurationMs(Math.max(0, now.getTime() - checkIn.getStartedAt().getTime()));
        checkIn.setMessage(truncate(request.getMessage(), 512));
        UpdateWrapper<MonitorCronCheckIn> inProgress = new UpdateWrapper<MonitorCronCheckIn>()
                .eq("id", checkIn.getId()).eq("status", "in_progress");
        if (checkInMapper.update(checkIn, inProgress) == 0) {
            MonitorCronCheckIn latest = checkInMapper.selectById(checkIn.getId());
            if (latest != null && status.equals(latest.getStatus())) return latest;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "check-in is already finished");
        }

        cron.setLastCheckinAt(now);
        cron.setLastCheckinStatus(status);
        cron.setNextCheckinAt(nextCheckIn(cron, now));
        if ("error".equals(status)) {
            cron.setConsecutiveFailures(cron.getConsecutiveFailures() + 1);
            cron.setConsecutiveSuccesses(0);
            if (cron.getConsecutiveFailures() >= cron.getFailureThreshold()) {
                cron.setHealthStatus("error");
            } else if (!"error".equals(cron.getHealthStatus())) {
                cron.setHealthStatus("warning");
            }
        } else {
            cron.setConsecutiveSuccesses(cron.getConsecutiveSuccesses() + 1);
            if (cron.getConsecutiveSuccesses() >= cron.getRecoveryThreshold()) {
                cron.setConsecutiveFailures(0);
                cron.setHealthStatus("ok");
            } else if (!"error".equals(cron.getHealthStatus())) {
                cron.setHealthStatus("warning");
            }
        }
        cronMapper.updateById(cron);
        return checkIn;
    }

    @Scheduled(fixedDelayString = "${monitor.cron.evaluation-interval-ms:15000}")
    @Transactional
    public void evaluateSchedules() {
        Date now = new Date();
        List<MonitorCron> active = cronMapper.selectList(Wrappers.<MonitorCron>lambdaQuery()
                .eq(MonitorCron::getStatus, "active")
                .le(MonitorCron::getNextCheckinAt, now));
        for (MonitorCron cron : active) {
            if (hasOpenCheckIn(cron.getId())) continue;
            if (cron.getNextCheckinAt().getTime() + cron.getCheckinMarginSeconds() * 1000L > now.getTime()) {
                continue;
            }
            Date dueAt = cron.getNextCheckinAt();
            Date expected = dueAt;
            int missed = 0;
            while (expected.getTime() + cron.getCheckinMarginSeconds() * 1000L <= now.getTime() && missed < 100) {
                missed++;
                expected = nextCheckIn(cron, expected);
            }
            cron.setLastCheckinStatus("missed");
            cron.setConsecutiveFailures(cron.getConsecutiveFailures() + missed);
            cron.setConsecutiveSuccesses(0);
            cron.setHealthStatus(cron.getConsecutiveFailures() >= cron.getFailureThreshold() ? "error" : "warning");
            cron.setNextCheckinAt(expected);
            UpdateWrapper<MonitorCron> optimistic = new UpdateWrapper<MonitorCron>()
                    .eq("id", cron.getId())
                    .eq("next_checkin_at", dueAt);
            cronMapper.update(cron, optimistic);
        }

        List<MonitorCronCheckIn> openCheckIns = checkInMapper.selectList(Wrappers.<MonitorCronCheckIn>lambdaQuery()
                .eq(MonitorCronCheckIn::getStatus, "in_progress")
                .le(MonitorCronCheckIn::getStartedAt, new Date(now.getTime() - 60_000L)));
        for (MonitorCronCheckIn checkIn : openCheckIns) {
            MonitorCron cron = cronMapper.selectById(checkIn.getCronId());
            if (cron == null || !"active".equals(cron.getStatus())
                    || checkIn.getStartedAt().getTime() + cron.getMaxRuntimeSeconds() * 1000L > now.getTime()) {
                continue;
            }
            UpdateWrapper<MonitorCronCheckIn> inProgress = new UpdateWrapper<MonitorCronCheckIn>()
                    .eq("id", checkIn.getId()).eq("status", "in_progress");
            checkIn.setStatus("timed_out");
            checkIn.setCompletedAt(now);
            checkIn.setDurationMs(Math.max(0, now.getTime() - checkIn.getStartedAt().getTime()));
            checkIn.setMessage("Check-in exceeded the configured maximum runtime");
            if (checkInMapper.update(checkIn, inProgress) == 0) continue;
            cron.setLastCheckinAt(now);
            cron.setLastCheckinStatus("timed_out");
            cron.setConsecutiveFailures(cron.getConsecutiveFailures() + 1);
            cron.setConsecutiveSuccesses(0);
            cron.setHealthStatus(cron.getConsecutiveFailures() >= cron.getFailureThreshold() ? "error" : "warning");
            cron.setNextCheckinAt(nextCheckIn(cron, now));
            cronMapper.updateById(cron);
        }
    }

    @Scheduled(fixedDelayString = "${monitor.cron.checkin-cleanup-interval-ms:3600000}")
    public void pruneExpiredCheckIns() {
        Date cutoff = Date.from(Instant.now().minus(CHECK_IN_RETENTION_DAYS, ChronoUnit.DAYS));
        for (int batch = 0; batch < MAX_CLEANUP_BATCHES; batch++) {
            int deleted = checkInMapper.deleteCompletedBefore(cutoff, CLEANUP_BATCH_SIZE);
            if (deleted < CLEANUP_BATCH_SIZE) break;
        }
        for (int batch = 0; batch < MAX_CLEANUP_BATCHES; batch++) {
            int deleted = checkInMapper.deleteStaleInProgressBefore(cutoff, CLEANUP_BATCH_SIZE);
            if (deleted < CLEANUP_BATCH_SIZE) return;
        }
    }

    private void validateSchedule(String type, String schedule, String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timezone is required");
        }
        try {
            nextSchedule(type, schedule, timezone, new Date());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cron schedule or timezone is invalid");
        }
    }

    private Date nextCheckIn(MonitorCron cron, Date after) {
        return nextSchedule(cron.getScheduleType(), cron.getSchedule(), cron.getTimezone(), after);
    }

    private Date nextSchedule(String type, String schedule, String timezone, Date after) {
        ZoneId zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        if ("interval".equals(type)) {
            Matcher matcher = INTERVAL.matcher(schedule == null ? "" : schedule.trim());
            if (!matcher.matches()) throw new IllegalArgumentException("invalid interval");
            long count = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2)) {
                case "s" -> 1;
                case "m" -> 60;
                case "h" -> 3600;
                case "d" -> 86400;
                case "w" -> 604800;
                default -> throw new IllegalArgumentException("invalid interval unit");
            };
            long seconds = Math.multiplyExact(count, multiplier);
            if (seconds < 60 || seconds > MAX_INTERVAL_SECONDS) throw new IllegalArgumentException("interval out of range");
            return new Date(Math.addExact(after.getTime(), seconds * 1000));
        }
        if (!"crontab".equals(type)) throw new IllegalArgumentException("invalid schedule type");
        String expression = schedule == null ? "" : schedule.trim();
        if (expression.split("\\s+").length == 5) expression = "0 " + expression;
        CronExpression cronExpression = CronExpression.parse(expression);
        ZonedDateTime reference = after.toInstant().atZone(zone);
        ZonedDateTime next = cronExpression.next(reference);
        if (next == null) throw new IllegalArgumentException("cron has no next execution");
        return Date.from(next.toInstant());
    }

    private MonitorCron requireCron(Long projectId, Long cronId) {
        MonitorCron cron = cronMapper.selectOne(Wrappers.<MonitorCron>lambdaQuery()
                .eq(MonitorCron::getProjectId, projectId)
                .eq(MonitorCron::getId, cronId)
                .last("LIMIT 1"));
        if (cron == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cron monitor not found");
        return cron;
    }

    private MonitorCron requireActiveCron(Long projectId, String slug) {
        MonitorCron cron = cronMapper.selectOne(Wrappers.<MonitorCron>lambdaQuery()
                .eq(MonitorCron::getProjectId, projectId)
                .eq(MonitorCron::getSlug, slug)
                .last("LIMIT 1"));
        if (cron == null || !"active".equals(cron.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "active cron monitor not found");
        }
        return cron;
    }

    private MonitorCronCheckIn findCheckIn(Long cronId, String checkInId) {
        return checkInMapper.selectOne(Wrappers.<MonitorCronCheckIn>lambdaQuery()
                .eq(MonitorCronCheckIn::getCronId, cronId)
                .eq(MonitorCronCheckIn::getCheckinId, checkInId)
                .last("LIMIT 1"));
    }

    private boolean hasOpenCheckIn(Long cronId) {
        return checkInMapper.selectCount(Wrappers.<MonitorCronCheckIn>lambdaQuery()
                .eq(MonitorCronCheckIn::getCronId, cronId)
                .eq(MonitorCronCheckIn::getStatus, "in_progress")) > 0;
    }

    private void apply(MonitorCron cron, MonitorCronRequest request) {
        cron.setName(request.getName().trim());
        cron.setSlug(request.getSlug().trim());
        cron.setScheduleType(request.getScheduleType());
        cron.setSchedule(request.getSchedule().trim());
        cron.setTimezone(request.getTimezone() == null || request.getTimezone().isBlank() ? "UTC" : request.getTimezone());
        cron.setCheckinMarginSeconds(request.getCheckinMarginSeconds() == null ? 60 : request.getCheckinMarginSeconds());
        cron.setMaxRuntimeSeconds(request.getMaxRuntimeSeconds() == null ? 1800 : request.getMaxRuntimeSeconds());
        cron.setFailureThreshold(request.getFailureThreshold() == null ? 1 : request.getFailureThreshold());
        cron.setRecoveryThreshold(request.getRecoveryThreshold() == null ? 1 : request.getRecoveryThreshold());
        cron.setStatus(request.getStatus() == null ? "active" : request.getStatus());
    }

    private void acquireRateLimit(String projectKey) {
        if (!rateLimiter.tryAcquire("cron:" + projectKey)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "cron check-in rate limit exceeded");
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

}
