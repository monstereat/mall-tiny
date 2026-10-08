package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorMetricCardinalityMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorMetricCardinalityDimension;
import com.macro.mall.tiny.modules.monitor.model.MonitorMetricCardinalityDimensionKey;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class MonitorMetricCardinalityService {

    static final int MAX_METRICS_PER_PROJECT = 200;
    static final int MAX_DIMENSION_KEYS_PER_METRIC = 20;
    static final int MAX_VALUES_PER_DIMENSION = 100;
    private static final int RETENTION_DAYS = 90;
    private static final int CLEANUP_BATCH_SIZE = 500;

    private final MonitorMetricCardinalityMapper mapper;

    @Transactional
    public void reserve(Long projectId, List<MonitorEventEnvelope> events) {
        TreeMap<String, TreeMap<String, TreeSet<String>>> dimensions = new TreeMap<>();
        for (MonitorEventEnvelope event : events) {
            if (event.getEventType() != MonitorEventType.METRIC) continue;

            String metricName = (String) event.getData().get("name");
            TreeMap<String, TreeSet<String>> metricDimensions = dimensions.computeIfAbsent(metricName, ignored -> new TreeMap<>());
            Object tags = event.getData().get("tags");
            if (tags instanceof Map<?, ?> tagMap) {
                for (Map.Entry<?, ?> tag : tagMap.entrySet()) {
                    String key = (String) tag.getKey();
                    String hash = hash(String.valueOf(tag.getValue()));
                    metricDimensions.computeIfAbsent(key, ignored -> new TreeSet<>()).add(hash);
                }
            }
        }
        if (dimensions.isEmpty()) return;

        Date cutoff = cutoff();
        mapper.ensureProject(projectId);
        Integer metricCount = mapper.lockProject(projectId);
        if (metricCount == null) {
            throw new IllegalStateException("metric cardinality project scope is missing");
        }
        int expiredMetrics = mapper.deleteExpiredMetricsForProject(projectId, cutoff);
        if (expiredMetrics > 0) {
            mapper.adjustProjectMetricCount(projectId, -expiredMetrics);
            metricCount -= expiredMetrics;
        }

        for (Map.Entry<String, TreeMap<String, TreeSet<String>>> metric : dimensions.entrySet()) {
            String metricName = metric.getKey();
            if (mapper.ensureMetric(projectId, metricName) == 1) {
                if (metricCount >= MAX_METRICS_PER_PROJECT) {
                    throw quotaExceeded("project metric-name cardinality limit exceeded");
                }
                mapper.adjustProjectMetricCount(projectId, 1);
                metricCount++;
            }
            mapper.touchMetric(projectId, metricName);
            if (metric.getValue().isEmpty()) continue;

            for (Map.Entry<String, TreeSet<String>> dimension : metric.getValue().entrySet()) {
                String dimensionKey = dimension.getKey();
                if (mapper.ensureDimension(projectId, metricName, dimensionKey) == 1) {
                    Integer keyCount = mapper.lockMetric(projectId, metricName);
                    if (keyCount == null) {
                        throw new IllegalStateException("metric cardinality metric scope is missing");
                    }
                    int expiredKeys = mapper.deleteExpiredDimensionsForMetric(projectId, metricName, cutoff);
                    if (expiredKeys > 0) {
                        mapper.adjustDimensionKeyCount(projectId, metricName, -expiredKeys);
                    }
                    if (keyCount - expiredKeys >= MAX_DIMENSION_KEYS_PER_METRIC) {
                        throw quotaExceeded("metric dimension-key cardinality limit exceeded");
                    }
                    mapper.adjustDimensionKeyCount(projectId, metricName, 1);
                }

                MonitorMetricCardinalityDimension scope = mapper.lockDimension(projectId, metricName, dimensionKey);
                if (scope == null || scope.getDistinctValueCount() == null) {
                    throw new IllegalStateException("metric cardinality dimension scope is missing");
                }
                int expiredValues = mapper.deleteExpiredValues(projectId, metricName, dimensionKey, cutoff);
                int valueCount = Math.max(0, scope.getDistinctValueCount() - expiredValues);
                if (expiredValues > 0) {
                    mapper.adjustDistinctValueCount(projectId, metricName, dimensionKey, -expiredValues);
                }

                for (String valueHash : dimension.getValue()) {
                    byte[] hash = java.util.HexFormat.of().parseHex(valueHash);
                    if (mapper.insertValueIfAbsent(projectId, metricName, dimensionKey, hash) == 1) {
                        if (valueCount >= MAX_VALUES_PER_DIMENSION) {
                            throw quotaExceeded("metric dimension-value cardinality limit exceeded");
                        }
                        mapper.adjustDistinctValueCount(projectId, metricName, dimensionKey, 1);
                        valueCount++;
                    }
                    mapper.touchValue(projectId, metricName, dimensionKey, hash);
                }
                mapper.touchDimension(projectId, metricName, dimensionKey);
            }
        }
    }

    @Scheduled(fixedDelayString = "${monitor.metrics.cardinality-cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanupExpiredDimensions() {
        Date cutoff = cutoff();
        List<Long> expiredProjects = mapper.findProjectsWithExpiredMetrics(cutoff, CLEANUP_BATCH_SIZE);
        for (Long projectId : expiredProjects) {
            Integer metricCount = mapper.lockProject(projectId);
            if (metricCount == null) continue;
            int deletedMetrics = mapper.deleteExpiredMetricsForProject(projectId, cutoff);
            if (deletedMetrics > 0) {
                mapper.adjustProjectMetricCount(projectId, -deletedMetrics);
            }
        }

        List<MonitorMetricCardinalityDimensionKey> expired = mapper.findExpiredDimensions(cutoff, CLEANUP_BATCH_SIZE);
        for (MonitorMetricCardinalityDimensionKey key : expired) {
            Integer keyCount = mapper.lockMetric(key.getProjectId(), key.getMetricName());
            if (keyCount == null) continue;
            MonitorMetricCardinalityDimension current = mapper.lockDimension(
                    key.getProjectId(), key.getMetricName(), key.getDimensionKey());
            if (current == null || current.getLastSeen() == null || !current.getLastSeen().before(cutoff)) continue;
            if (mapper.deleteDimension(key.getProjectId(), key.getMetricName(), key.getDimensionKey()) == 1) {
                mapper.adjustDimensionKeyCount(key.getProjectId(), key.getMetricName(), -1);
            }
        }
    }

    private Date cutoff() {
        return Date.from(Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS));
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private ResponseStatusException quotaExceeded(String message) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
