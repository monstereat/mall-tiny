package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
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
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MonitorIngestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorIngestService.class);
    private static final Pattern METRIC_NAME = Pattern.compile("[A-Za-z_:][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern TRACE_ID = Pattern.compile("(?i)[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("(?i)[0-9a-f]{16}");
    private static final int MAX_PROFILE_SAMPLES = 200;
    private static final int MAX_PROFILE_STACK_DEPTH = 64;

    private final MonitorProjectService projectService;
    private final MonitorRateLimiter rateLimiter;
    private final MonitorEventProducer producer;
    private final MonitorMetricCardinalityService metricCardinalityService;
    private final MonitorEventScrubber eventScrubber;

    public void ingest(String ingestKey, MonitorEventEnvelope event) {
        MonitorProject project = projectService.validateIngestKey(event.getProjectId(), ingestKey);
        validateMetric(event);
        eventScrubber.scrub(event, Boolean.TRUE.equals(project.getScrubEmails()),
                Boolean.TRUE.equals(project.getScrubCreditCards()),
                Boolean.TRUE.equals(project.getScrubIpAddresses()),
                Boolean.TRUE.equals(project.getScrubPhoneNumbers()),
                Boolean.TRUE.equals(project.getScrubChineseIdNumbers()),
                MonitorSensitiveFieldNames.fromStorage(project.getCustomSensitiveFields()));
        String previousProject = MDC.get("monitor.project");
        String previousTraceId = MDC.get("monitor.trace_id");
        String previousEnvironment = MDC.get("monitor.environment");
        String previousRelease = MDC.get("monitor.release");
        String previousUserId = MDC.get("monitor.user_id");
        String previousTags = MDC.get("monitor.tags");
        MDC.put("monitor.project", event.getProjectId());
        setMdc("monitor.trace_id", event.getTraceId());
        setMdc("monitor.environment", event.getEnvironment());
        setMdc("monitor.release", event.getRelease());
        try {
            acquire(event.getProjectId(), 1);
            metricCardinalityService.reserve(project.getId(), List.of(event));
            producer.publish(event);
            setEventTags(event);
            setUserIdMdc(event.getUserId());
            LOGGER.info("accepted monitor telemetry event");
        } finally {
            restoreMdc("monitor.project", previousProject);
            restoreMdc("monitor.trace_id", previousTraceId);
            restoreMdc("monitor.environment", previousEnvironment);
            restoreMdc("monitor.release", previousRelease);
            restoreMdc("monitor.user_id", previousUserId);
            restoreMdc("monitor.tags", previousTags);
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
        events.forEach(event -> eventScrubber.scrub(event, Boolean.TRUE.equals(project.getScrubEmails()),
                Boolean.TRUE.equals(project.getScrubCreditCards()),
                Boolean.TRUE.equals(project.getScrubIpAddresses()),
                Boolean.TRUE.equals(project.getScrubPhoneNumbers()),
                Boolean.TRUE.equals(project.getScrubChineseIdNumbers()),
                MonitorSensitiveFieldNames.fromStorage(project.getCustomSensitiveFields())));

        String previousProject = MDC.get("monitor.project");
        String previousTraceId = MDC.get("monitor.trace_id");
        String previousEnvironment = MDC.get("monitor.environment");
        String previousRelease = MDC.get("monitor.release");
        String previousUserId = MDC.get("monitor.user_id");
        String previousTags = MDC.get("monitor.tags");
        MDC.put("monitor.project", projectId);
        MDC.remove("monitor.user_id");
        MDC.remove("monitor.tags");
        String traceId = events.get(0).getTraceId();
        if (StringUtils.hasText(traceId) && events.stream().allMatch(event -> Objects.equals(traceId, event.getTraceId()))) {
            MDC.put("monitor.trace_id", traceId);
        } else {
            MDC.remove("monitor.trace_id");
        }
        String environment = events.get(0).getEnvironment();
        String release = events.get(0).getRelease();
        setMdc("monitor.environment", events.stream().allMatch(event -> Objects.equals(environment, event.getEnvironment()))
                ? environment : null);
        setMdc("monitor.release", events.stream().allMatch(event -> Objects.equals(release, event.getRelease()))
                ? release : null);
        try {
            acquire(projectId, events.size());
            metricCardinalityService.reserve(project.getId(), events);
            for (MonitorEventEnvelope event : events) {
                String previousTrace = MDC.get("monitor.trace_id");
                String previousEventEnvironment = MDC.get("monitor.environment");
                String previousEventRelease = MDC.get("monitor.release");
                String eventPreviousUserId = MDC.get("monitor.user_id");
                String eventPreviousTags = MDC.get("monitor.tags");
                boolean indexedEvent = hasSafeUserId(event.getUserId()) || hasTags(event);
                try {
                    producer.publish(event);
                    if (indexedEvent) {
                        setEventTags(event);
                        setMdc("monitor.trace_id", event.getTraceId());
                        setMdc("monitor.environment", event.getEnvironment());
                        setMdc("monitor.release", event.getRelease());
                        setUserIdMdc(event.getUserId());
                        LOGGER.info("accepted monitor telemetry event");
                    }
                } finally {
                    restoreMdc("monitor.trace_id", previousTrace);
                    restoreMdc("monitor.environment", previousEventEnvironment);
                    restoreMdc("monitor.release", previousEventRelease);
                    restoreMdc("monitor.user_id", eventPreviousUserId);
                    restoreMdc("monitor.tags", eventPreviousTags);
                }
            }
            LOGGER.info("accepted monitor telemetry batch size={}", events.size());
            return events.size();
        } finally {
            restoreMdc("monitor.project", previousProject);
            restoreMdc("monitor.trace_id", previousTraceId);
            restoreMdc("monitor.environment", previousEnvironment);
            restoreMdc("monitor.release", previousRelease);
            restoreMdc("monitor.user_id", previousUserId);
            restoreMdc("monitor.tags", previousTags);
        }
    }

    private void setMdc(String key, String value) {
        if (StringUtils.hasText(value)) {
            MDC.put(key, value);
        } else {
            MDC.remove(key);
        }
    }

    private void setUserIdMdc(String userId) {
        setMdc("monitor.user_id", hasSafeUserId(userId) ? userId : null);
    }

    private boolean hasSafeUserId(String userId) {
        return StringUtils.hasText(userId) && userId.length() <= 128
                && userId.chars().noneMatch(Character::isISOControl);
    }

    private void setEventTags(MonitorEventEnvelope event) {
        Map<String, Object> data = event.getData();
        Object rawTags = data == null ? null : data.get("tags");
        if (!(rawTags instanceof Map<?, ?> tags)) {
            MDC.remove("monitor.tags");
            return;
        }
        String encodedTags = MonitorLogTagMetadata.encode(tags);
        setMdc("monitor.tags", encodedTags);
    }

    private boolean hasTags(MonitorEventEnvelope event) {
        Object rawTags = event.getData() == null ? null : event.getData().get("tags");
        return rawTags instanceof Map<?, ?> tags && MonitorLogTagMetadata.encode(tags) != null;
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
        if (event.getEventType() == MonitorEventType.SPAN) {
            validateSpan(event);
            return;
        }
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

    private void validateSpan(MonitorEventEnvelope event) {
        if (!validTraceId(event.getTraceId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span traceId must be a non-zero 32-character hex ID");
        }
        Map<String, Object> data = event.getData();
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span data is required");
        }
        Object spanIdValue = data.get("spanId");
        if (!(spanIdValue instanceof String spanId) || !validSpanId(spanId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span spanId must be a non-zero 16-character hex ID");
        }
        Object parentSpanIdValue = data.get("parentSpanId");
        if (parentSpanIdValue != null && (!(parentSpanIdValue instanceof String parentSpanId)
                || !parentSpanId.isEmpty() && !validSpanId(parentSpanId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span parentSpanId must be a non-zero 16-character hex ID");
        }
        Object opValue = data.get("op");
        if (!(opValue instanceof String op) || !StringUtils.hasText(op) || op.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span op must contain at most 128 characters");
        }
        Object descriptionValue = data.get("description");
        if (!(descriptionValue instanceof String description) || description.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span description must contain at most 512 characters");
        }
        Object startTimeValue = data.get("startTime");
        if (!(startTimeValue instanceof Number startTimeNumber)
                || !Double.isFinite(startTimeNumber.doubleValue())
                || startTimeNumber.doubleValue() != Math.rint(startTimeNumber.doubleValue())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span startTime must be a valid epoch millisecond");
        }
        long startTime = startTimeNumber.longValue();
        if (startTime < 0 || startTime > System.currentTimeMillis() + 300_000L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span startTime is outside the accepted time range");
        }
        Object durationValue = data.get("durationMs");
        if (!(durationValue instanceof Number duration) || !Double.isFinite(duration.doubleValue())
                || duration.doubleValue() < 0 || duration.doubleValue() > 86_400_000d) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span durationMs must be between 0 and 86400000");
        }
        Object statusValue = data.get("status");
        if (!(statusValue instanceof String status) || !("ok".equals(status) || "error".equals(status))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span status must be ok or error");
        }
        Object statusCodeValue = data.get("statusCode");
        if (statusCodeValue != null && (!(statusCodeValue instanceof Number statusCode)
                || !Double.isFinite(statusCode.doubleValue())
                || statusCode.doubleValue() != Math.rint(statusCode.doubleValue())
                || statusCode.longValue() < 0 || statusCode.longValue() > 599)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "span statusCode must be an integer from 0 to 599");
        }

        Map<String, Object> canonicalData = new LinkedHashMap<>(data);
        event.setTraceId(event.getTraceId().toLowerCase(java.util.Locale.ROOT));
        canonicalData.put("spanId", spanId.toLowerCase(java.util.Locale.ROOT));
        if (parentSpanIdValue instanceof String parentSpanId && !parentSpanId.isEmpty()) {
            canonicalData.put("parentSpanId", parentSpanId.toLowerCase(java.util.Locale.ROOT));
        }
        event.setData(canonicalData);
        event.setTimestamp(startTime);
    }

    private boolean validTraceId(String value) {
        return value != null && TRACE_ID.matcher(value).matches() && !value.matches("(?i)0{32}");
    }

    private boolean validSpanId(String value) {
        return SPAN_ID.matcher(value).matches() && !value.matches("(?i)0{16}");
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile samples must contain 1 to 200 entries");
        }
        double totalValue = 0;
        for (Object rawSample : samples) {
            if (!(rawSample instanceof Map<?, ?> sample)
                    || !(sample.get("stack") instanceof List<?> stack)
                    || stack.isEmpty() || stack.size() > MAX_PROFILE_STACK_DEPTH
                    || !(sample.get("value") instanceof Number value)
                    || !Double.isFinite(value.doubleValue()) || value.doubleValue() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile sample must have a non-empty stack and positive finite value");
            }
            totalValue += value.doubleValue();
            if (!Double.isFinite(totalValue)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile sample values must have a finite total");
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
