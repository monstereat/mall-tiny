package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.dto.MonitorUptimeCheckRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorUptimeCheckMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorUptimeHistoryMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorUptimeCheck;
import com.macro.mall.tiny.modules.monitor.model.MonitorUptimeHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MonitorUptimeService {

    private static final int MAX_CHECKS_PER_CYCLE = 10;
    private static final int MAX_ENABLED_PER_PROJECT = 50;
    private static final int MAX_HISTORY_LIMIT = 200;
    private static final long HISTORY_RETENTION_MS = 90L * 24 * 60 * 60 * 1000;

    private final MonitorUptimeCheckMapper checkMapper;
    private final MonitorUptimeHistoryMapper historyMapper;
    private final MonitorProjectAccessService projectAccessService;
    private final MonitorUptimeProbe probe;
    private final ThreadPoolTaskExecutor monitorUptimeExecutor;

    public List<MonitorUptimeCheck> list(String projectKey) {
        MonitorProject project = projectAccessService.requireProject(projectKey, false);
        return checkMapper.selectList(Wrappers.<MonitorUptimeCheck>lambdaQuery()
                .eq(MonitorUptimeCheck::getProjectId, project.getId())
                .orderByAsc(MonitorUptimeCheck::getName));
    }

    @Transactional
    public MonitorUptimeCheck create(String projectKey, MonitorUptimeCheckRequest request) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        validate(request);
        ensureUniqueSlug(project.getId(), request.getSlug(), null);
        if ("active".equals(request.getStatus()) && enabledCount(project.getId()) >= MAX_ENABLED_PER_PROJECT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "project uptime monitor limit reached");
        }

        MonitorUptimeCheck monitor = new MonitorUptimeCheck();
        apply(monitor, request);
        monitor.setProjectId(project.getId());
        monitor.setCurrentStatus("unknown");
        monitor.setConsecutiveFailures(0);
        monitor.setConsecutiveSuccesses(0);
        monitor.setNextCheckAt(new Date());
        try {
            checkMapper.insert(monitor);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uptime monitor slug already exists");
        }
        return monitor;
    }

    @Transactional
    public MonitorUptimeCheck update(String projectKey, Long checkId, MonitorUptimeCheckRequest request) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        validate(request);
        MonitorUptimeCheck monitor = requireMonitor(project.getId(), checkId);
        ensureUniqueSlug(project.getId(), request.getSlug(), checkId);
        if ("active".equals(request.getStatus()) && !"active".equals(monitor.getStatus())
                && enabledCount(project.getId()) >= MAX_ENABLED_PER_PROJECT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "project uptime monitor limit reached");
        }
        apply(monitor, request);
        monitor.setNextCheckAt(new Date());
        try {
            checkMapper.updateById(monitor);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uptime monitor slug already exists");
        }
        return monitor;
    }

    @Transactional
    public void delete(String projectKey, Long checkId) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        checkMapper.delete(Wrappers.<MonitorUptimeCheck>lambdaQuery()
                .eq(MonitorUptimeCheck::getProjectId, project.getId())
                .eq(MonitorUptimeCheck::getId, checkId));
    }

    public List<MonitorUptimeHistory> history(String projectKey, Long checkId, int limit) {
        MonitorProject project = projectAccessService.requireProject(projectKey, false);
        requireMonitor(project.getId(), checkId);
        int safeLimit = Math.max(1, Math.min(MAX_HISTORY_LIMIT, limit));
        return historyMapper.selectList(Wrappers.<MonitorUptimeHistory>lambdaQuery()
                .eq(MonitorUptimeHistory::getUptimeCheckId, checkId)
                .orderByDesc(MonitorUptimeHistory::getId)
                .last("LIMIT " + safeLimit));
    }

    @Scheduled(fixedDelayString = "${monitor.uptime.evaluation-interval-ms:15000}")
    public void evaluateDueChecks() {
        Date now = new Date();
        List<MonitorUptimeCheck> dueMonitors = checkMapper.selectList(Wrappers.<MonitorUptimeCheck>lambdaQuery()
                .eq(MonitorUptimeCheck::getStatus, "active")
                .le(MonitorUptimeCheck::getNextCheckAt, now)
                .orderByAsc(MonitorUptimeCheck::getNextCheckAt)
                .last("LIMIT " + MAX_CHECKS_PER_CYCLE));
        for (MonitorUptimeCheck monitor : dueMonitors) {
            monitorUptimeExecutor.execute(() -> runClaimedCheck(monitor, now));
        }
    }

    @Scheduled(cron = "0 17 * * * *")
    public void pruneHistory() {
        Date cutoff = new Date(System.currentTimeMillis() - HISTORY_RETENTION_MS);
        List<MonitorUptimeHistory> expired = historyMapper.selectList(Wrappers.<MonitorUptimeHistory>lambdaQuery()
                .lt(MonitorUptimeHistory::getCheckedAt, cutoff)
                .orderByAsc(MonitorUptimeHistory::getId)
                .last("LIMIT 1000"));
        if (!expired.isEmpty()) {
            historyMapper.deleteBatchIds(expired.stream().map(MonitorUptimeHistory::getId).toList());
        }
    }

    private void runClaimedCheck(MonitorUptimeCheck monitor, Date now) {
        Date dueAt = monitor.getNextCheckAt();
        Date nextCheckAt = new Date(now.getTime() + monitor.getIntervalSeconds() * 1000L);
        UpdateWrapper<MonitorUptimeCheck> claim = new UpdateWrapper<MonitorUptimeCheck>()
                .eq("id", monitor.getId())
                .eq("status", "active")
                .eq("next_check_at", dueAt)
                .set("next_check_at", nextCheckAt);
        // Compare-and-set ensures replicas claim a due monitor only once.
        if (checkMapper.update(null, claim) == 0) return;

        MonitorUptimeProbe.Result result = probe.check(
                monitor.getUrl(), monitor.getMethod(), monitor.getExpectedStatusCode(), monitor.getTimeoutMs());
        Date checkedAt = new Date();
        boolean success = result.successful();
        int failures = success ? valueOrZero(monitor.getConsecutiveFailures())
                : valueOrZero(monitor.getConsecutiveFailures()) + 1;
        int successes = success ? valueOrZero(monitor.getConsecutiveSuccesses()) + 1 : 0;
        String currentStatus;
        if (success) {
            if (successes >= monitor.getRecoveryThreshold()) {
                failures = 0;
                currentStatus = "up";
            } else if ("down".equals(monitor.getCurrentStatus())) {
                currentStatus = "down";
            } else {
                currentStatus = "warning";
            }
        } else {
            currentStatus = failures >= monitor.getFailureThreshold() ? "down" : "warning";
        }

        MonitorUptimeHistory history = new MonitorUptimeHistory();
        history.setUptimeCheckId(monitor.getId());
        history.setStatus(success ? "up" : "down");
        history.setResponseStatus(result.responseStatus());
        history.setDurationMs((long) result.durationMs());
        history.setMessage(truncate(result.error(), 512));
        history.setCheckedAt(checkedAt);
        historyMapper.insert(history);

        UpdateWrapper<MonitorUptimeCheck> update = new UpdateWrapper<MonitorUptimeCheck>()
                .eq("id", monitor.getId())
                .set("current_status", currentStatus)
                .set("consecutive_failures", failures)
                .set("consecutive_successes", successes)
                .set("checked_at", checkedAt)
                .set("last_status_code", result.responseStatus())
                .set("last_duration_ms", result.durationMs())
                .set("last_error", success ? null : truncate(result.error(), 512));
        checkMapper.update(null, update);
    }

    private void validate(MonitorUptimeCheckRequest request) {
        if (request == null
                || request.getSlug() == null || !request.getSlug().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
                || request.getName() == null || request.getName().isBlank() || request.getName().length() > 128
                || request.getUrl() == null || request.getUrl().isBlank() || request.getUrl().length() > 2048
                || request.getIntervalSeconds() == null || request.getIntervalSeconds() < 30 || request.getIntervalSeconds() > 86400
                || request.getTimeoutMs() == null || request.getTimeoutMs() < 500 || request.getTimeoutMs() > 30000
                || request.getExpectedStatusCode() == null || request.getExpectedStatusCode() < 100 || request.getExpectedStatusCode() > 599
                || request.getFailureThreshold() == null || request.getFailureThreshold() < 1 || request.getFailureThreshold() > 100
                || request.getRecoveryThreshold() == null || request.getRecoveryThreshold() < 1 || request.getRecoveryThreshold() > 100
                || request.getMethod() == null || !List.of("GET", "HEAD").contains(request.getMethod())
                || request.getStatus() == null || !List.of("active", "disabled").contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid uptime monitor configuration");
        }
        probe.validateUrl(request.getUrl().trim());
    }

    private void apply(MonitorUptimeCheck monitor, MonitorUptimeCheckRequest request) {
        monitor.setSlug(request.getSlug().trim());
        monitor.setName(request.getName().trim());
        monitor.setUrl(request.getUrl().trim());
        monitor.setMethod(request.getMethod());
        monitor.setIntervalSeconds(request.getIntervalSeconds());
        monitor.setTimeoutMs(request.getTimeoutMs());
        monitor.setExpectedStatusCode(request.getExpectedStatusCode());
        monitor.setFailureThreshold(request.getFailureThreshold());
        monitor.setRecoveryThreshold(request.getRecoveryThreshold());
        monitor.setStatus(request.getStatus());
    }

    private void ensureUniqueSlug(Long projectId, String slug, Long exceptId) {
        var query = Wrappers.<MonitorUptimeCheck>lambdaQuery()
                .eq(MonitorUptimeCheck::getProjectId, projectId)
                .eq(MonitorUptimeCheck::getSlug, slug);
        if (exceptId != null) query.ne(MonitorUptimeCheck::getId, exceptId);
        if (checkMapper.selectCount(query) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uptime monitor slug already exists");
        }
    }

    private long enabledCount(Long projectId) {
        return checkMapper.selectCount(Wrappers.<MonitorUptimeCheck>lambdaQuery()
                .eq(MonitorUptimeCheck::getProjectId, projectId)
                .eq(MonitorUptimeCheck::getStatus, "active"));
    }

    private MonitorUptimeCheck requireMonitor(Long projectId, Long checkId) {
        MonitorUptimeCheck monitor = checkMapper.selectOne(Wrappers.<MonitorUptimeCheck>lambdaQuery()
                .eq(MonitorUptimeCheck::getProjectId, projectId)
                .eq(MonitorUptimeCheck::getId, checkId)
                .last("LIMIT 1"));
        if (monitor == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "uptime monitor not found");
        return monitor;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
