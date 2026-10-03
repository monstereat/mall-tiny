package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.kafka.MonitorEventProducer;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MonitorIngestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorIngestService.class);
    private static final Pattern METRIC_NAME = Pattern.compile("[A-Za-z_:][A-Za-z0-9_.:-]{0,127}");
    private static final int MAX_PROFILE_SAMPLES = 200;
    private static final int MAX_PROFILE_STACK_DEPTH = 64;

    private final MonitorProjectService projectService;
    private final MonitorRateLimiter rateLimiter;
    private final MonitorEventProducer producer;
    private final MonitorMetricCardinalityService metricCardinalityService;

    public void ingest(String ingestKey, MonitorEventEnvelope event) {
        MonitorProject project = projectService.validateIngestKey(event.getProjectId(), ingestKey);
        validateMetric(event);
        String previousProject = MDC.get("monitor.project");
        String previousTraceId = MDC.get("monitor.trace_id");
        MDC.put("monitor.project", event.getProjectId());
        setMdc("monitor.trace_id", event.getTraceId());
        try {
            acquire(event.getProjectId(), 1);
            metricCardinalityService.reserve(project.getId(), List.of(event));
            producer.publish(event);
            LOGGER.info("accepted monitor telemetry event");
        } finally {
            restoreMdc("monitor.project", previousProject);
            restoreMdc("monitor.trace_id", previousTraceId);
        }
    }

    public int ingestBatch(String ingestKey, List<MonitorEventEnvelope> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        String projectId = events.get(0).getProjectId();
        MonitorProject project = projectService.validateIngestKey(projectId, ingestKey);

        boolean sameProject = events.stream().allMatch(event -> projectId.equals(event.getProjectId()));
        if (!sameProject) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "one batch can contain only one project");
        }
        events.forEach(this::validateMetric);

        String previousProject = MDC.get("monitor.project");
        String previousTraceId = MDC.get("monitor.trace_id");
        MDC.put("monitor.project", projectId);
        String traceId = events.get(0).getTraceId();
        if (StringUtils.hasText(traceId) && events.stream().allMatch(event -> Objects.equals(traceId, event.getTraceId()))) {
            MDC.put("monitor.trace_id", traceId);
        } else {
            MDC.remove("monitor.trace_id");
        }
        try {
            acquire(projectId, events.size());
            metricCardinalityService.reserve(project.getId(), events);
            events.forEach(producer::publish);
            LOGGER.info("accepted monitor telemetry batch size={}", events.size());
            return events.size();
        } finally {
            restoreMdc("monitor.project", previousProject);
            restoreMdc("monitor.trace_id", previousTraceId);
        }
    }

    private void setMdc(String key, String value) {
        if (StringUtils.hasText(value)) {
            MDC.put(key, value);
        } else {
            MDC.remove(key);
        }
    }

    private void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private void acquire(String projectId, long permits) {
        if (!rateLimiter.tryAcquire(projectId, permits)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "monitor ingest rate limit exceeded");
        }
    }

    private void validateMetric(MonitorEventEnvelope event) {
        if (event.getEventType() == com.macro.mall.tiny.modules.monitor.domain.MonitorEventType.PROFILE) {
            validateProfile(event);
            return;
        }
        if (event.getEventType() != com.macro.mall.tiny.modules.monitor.domain.MonitorEventType.METRIC) return;
        Map<String, Object> data = event.getData();
        Object name = data.get("name");
        Object metricType = data.get("metricType");
        Object value = data.get("value");
        if (!(name instanceof String metricName) || !METRIC_NAME.matcher(metricName).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metric name is invalid");
        }
        if (!(metricType instanceof String type)
                || !java.util.Set.of("counter", "gauge", "distribution").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metricType must be counter, gauge, or distribution");
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metric value must be a finite number");
        }
        Object unit = data.get("unit");
        if (unit != null && (!(unit instanceof String text) || text.length() > 32)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metric unit must be at most 32 characters");
        }
        Object tags = data.get("tags");
        if (tags == null) return;
        if (!(tags instanceof Map<?, ?> tagMap) || tagMap.size() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metric tags must contain at most 20 dimensions");
        }
        for (Map.Entry<?, ?> tag : tagMap.entrySet()) {
            Object tagValue = tag.getValue();
            boolean scalar = tagValue instanceof String || tagValue instanceof Number || tagValue instanceof Boolean;
            boolean finiteFloat = !(tagValue instanceof Double doubleValue) || Double.isFinite(doubleValue);
            finiteFloat &= !(tagValue instanceof Float floatValue) || Float.isFinite(floatValue);
            if (!(tag.getKey() instanceof String key) || key.isBlank() || key.length() > 64
                    || !scalar || !finiteFloat || String.valueOf(tagValue).length() > 128) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "metric tag keys and values must be scalar and within length limits");
            }
        }
    }

    private void validateProfile(MonitorEventEnvelope event) {
        Map<String, Object> data = event.getData();
        if (data == null || !"collapsed".equals(data.get("format"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile format must be collapsed");
        }
        Object name = data.get("name");
        Object unit = data.get("unit");
        Object rawSamples = data.get("samples");
        if (!(name instanceof String profileName) || profileName.isBlank() || profileName.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile name must be 1 to 128 characters");
        }
        if (!(unit instanceof String profileUnit) || profileUnit.isBlank() || profileUnit.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile unit must be 1 to 32 characters");
        }
        if (!(rawSamples instanceof List<?> samples) || samples.isEmpty() || samples.size() > MAX_PROFILE_SAMPLES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile samples must contain 1 to 1000 entries");
        }
        for (Object rawSample : samples) {
            if (!(rawSample instanceof Map<?, ?> sample)
                    || !(sample.get("stack") instanceof List<?> stack)
                    || stack.isEmpty() || stack.size() > MAX_PROFILE_STACK_DEPTH
                    || !(sample.get("value") instanceof Number value)
                    || !Double.isFinite(value.doubleValue()) || value.doubleValue() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile sample must have a non-empty stack and positive finite value");
            }
            for (Object frame : stack) {
                if (!(frame instanceof String text) || text.isBlank() || text.length() > 96
                        || text.chars().anyMatch(Character::isISOControl)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile frame must be 1 to 96 printable characters");
                }
            }
        }
    }
}
