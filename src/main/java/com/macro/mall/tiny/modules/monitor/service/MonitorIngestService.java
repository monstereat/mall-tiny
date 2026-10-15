package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 安排一条事件从 HTTP 接口进入后台处理的顺序：Controller 负责读请求，本类负责验项目、验内容、脱敏、限流和排队。
 * {@code data} 是一组运行时才知道内容的键值对，所以这里要按事件类型逐项检查；通过后再交给后台消费者处理。
 */
@Service
@RequiredArgsConstructor
public class MonitorIngestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorIngestService.class);
    private static final Pattern METRIC_NAME = Pattern.compile("[A-Za-z_:][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern TRACE_ID = Pattern.compile("(?i)[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("(?i)[0-9a-f]{16}");
    private static final Pattern BUSINESS_EVENT_NAME = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");
    private static final int MAX_PROFILE_SAMPLES = 200;
    private static final int MAX_PROFILE_STACK_DEPTH = 64;
    private static final int MAX_BUSINESS_PROPERTY_KEYS = 32;
    private static final int MAX_BUSINESS_PROPERTY_BYTES = 16 * 1024;
    private static final int MAX_BUSINESS_PROPERTY_DEPTH = 3;
    private static final ObjectMapper BUSINESS_PROPERTY_MAPPER = new ObjectMapper();

    // final 表示这些依赖创建后不会再更换；Lombok 自动生成构造函数，Spring 启动时会把对应服务传进来。
    private final MonitorProjectService projectService;
    private final MonitorRateLimiter rateLimiter;
    private final MonitorEventProducer producer;
    private final MonitorMetricCardinalityService metricCardinalityService;
    private final MonitorEventScrubber eventScrubber;

    public void ingest(String ingestKey, MonitorEventEnvelope event) {
        // 1) 按项目编号查项目并核对请求头里的 Key，同时取回该项目的脱敏设置。
        MonitorProject project = projectService.validateIngestKey(event.getProjectId(), ingestKey);
        // 2) data 是运行时才解析的 JSON 键值对象，编译器无法检查每个字段；这里按事件类型检查内容和类型。
        validateMetric(event);
        // 3) 先把敏感信息替换掉，再排队；后面的后台处理、数据库和日志只接触处理后的事件。
        eventScrubber.scrub(event, Boolean.TRUE.equals(project.getScrubEmails()),
                Boolean.TRUE.equals(project.getScrubCreditCards()),
                Boolean.TRUE.equals(project.getScrubIpAddresses()),
                Boolean.TRUE.equals(project.getScrubPhoneNumbers()),
                Boolean.TRUE.equals(project.getScrubChineseIdNumbers()),
                MonitorSensitiveFieldNames.fromStorage(project.getCustomSensitiveFields()));
        // MDC 是“本线程当前日志信息”；Web 线程会重复使用，所以处理结束时要恢复进入方法前的值。
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
            // 4) 先检查项目每分钟额度和自定义指标数量上限，再按事件类型把消息送入 Kafka 队列。
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
        // 用第一条事件的项目编号核对 Key；下面再确认这一批的其他事件也都属于同一项目。
        MonitorProject project = projectService.validateIngestKey(projectId, ingestKey);

        // 一批只能属于同一个项目，才能安全共用同一个上报 Key 和该项目的额度。
        boolean sameProject = events.stream().allMatch(event -> projectId.equals(event.getProjectId()));
        if (!sameProject) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "one batch can contain only one project");
        }
        // 先对整批完成校验和脱敏，再开始逐条发送，避免单纯的格式错误导致前半批已经进入队列。
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
        // 一批事件的用户、链路编号、环境和版本可能不同；只有整批相同时才写入这批共用的日志信息。
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
            // 每条事件占用一个额度；指标名称和标签数量先整批检查，然后仍逐条送入 Kafka。
            acquire(projectId, events.size());
            // 发布每条消息时只把适合写入日志检索条件的字段放进日志上下文。
            metricCardinalityService.reserve(project.getId(), events);
            for (MonitorEventEnvelope event : events) {
                // 暂存并恢复每条事件自己的日志信息，防止上一条的值串到下一条日志里。
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
            // 没有值时要彻底删除，而不是写空字符串，否则这个复用的线程可能还带着上一次的日志信息。
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
        // 超过项目额度时返回 HTTP 429（请求过多）；统一异常处理会把它转换成接口响应。
        if (!rateLimiter.tryAcquire(projectId, permits)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "monitor ingest rate limit exceeded");
        }
    }

    private void validateMetric(MonitorEventEnvelope event) {
        // 根据事件类型进入不同的内容检查；其他通用必填规则已由接口层先检查。
        if (event.getEventType() == MonitorEventType.SPAN) {
            validateSpan(event);
            return;
        }
        if (event.getEventType() == com.macro.mall.tiny.modules.monitor.domain.MonitorEventType.PROFILE) {
            validateProfile(event);
            return;
        }
        if (event.getEventType() == MonitorEventType.BEHAVIOR) {
            validateBusinessBehavior(event);
            return;
        }
        if (event.getEventType() != com.macro.mall.tiny.modules.monitor.domain.MonitorEventType.METRIC) return;
        Map<String, Object> data = event.getData();
        Object name = data.get("name");
        Object metricType = data.get("metricType");
        Object value = data.get("value");
        // 自定义指标要限制名称、数据类型、数值和标签格式，防止错误数据或标签种类过多。
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

    private void validateBusinessBehavior(MonitorEventEnvelope event) {
        // BEHAVIOR 既能表示点击/路由行为，也能表示业务分析和页面事件；这里只检查后两类专用字段。
        Map<String, Object> data = event.getData();
        if (data == null) return;
        Object rawCategory = data.get("category");
        if (!(rawCategory instanceof String category)) return;
        if ("business".equals(category)) {
            validateBusinessEvent(data);
        } else if ("page".equals(category)) {
            validatePageEvent(data);
        }
    }

    private void validateBusinessEvent(Map<String, Object> data) {
        // 业务事件名必须是小写下划线格式；采样率和自定义属性对象也必须符合约定。
        validateEventName(data.get("event"));
        validateAnalyticsSampleRate(data.get("analyticsSampleRate"));
        Object properties = data.get("properties");
        if (!(properties instanceof Map<?, ?>)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "business properties must be an object");
        }
        validateProperties(properties);
    }

    private void validatePageEvent(Map<String, Object> data) {
        // 页面事件只接受“页面打开”和“页面停留”；停留时间以毫秒计，且不能超过一天。
        Object rawEvent = data.get("event");
        if (!(rawEvent instanceof String event) || !("page_view".equals(event) || "page_dwell".equals(event))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page event must be page_view or page_dwell");
        }
        validatePageViewId(data.get("pageViewId"));
        validateAnalyticsSampleRate(data.get("analyticsSampleRate"));
        validateOptionalText(data.get("name"), 128, "page name");
        validateOptionalText(data.get("referrer"), 2048, "page referrer");
        validateProperties(data.get("properties"));
        if ("page_dwell".equals(event)) {
            Object rawDuration = data.get("durationMs");
            if (!(rawDuration instanceof Number duration) || !Double.isFinite(duration.doubleValue())
                    || duration.doubleValue() < 0 || duration.doubleValue() > 86_400_000d) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "page dwell durationMs must be between 0 and 86400000");
            }
        }
    }

    private void validateEventName(Object value) {
        if (!(value instanceof String event) || event.length() > 64
                || !BUSINESS_EVENT_NAME.matcher(event).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "business event must be a lowercase ASCII snake_case name of at most 64 characters");
        }
    }

    private void validatePageViewId(Object value) {
        if (!(value instanceof String pageViewId) || pageViewId.isBlank() || pageViewId.length() > 128
                || pageViewId.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pageViewId must contain 1 to 128 printable characters");
        }
    }

    private void validateAnalyticsSampleRate(Object value) {
        if (!(value instanceof Number sampleRate) || !Double.isFinite(sampleRate.doubleValue())
                || sampleRate.doubleValue() < 0 || sampleRate.doubleValue() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "analyticsSampleRate must be a finite number from 0 to 1");
        }
    }

    private void validateOptionalText(Object value, int maxLength, String field) {
        if (value == null) return;
        if (!(value instanceof String text) || text.length() > maxLength
                || text.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is invalid or too long");
        }
    }

    private void validateProperties(Object value) {
        // 限制自定义属性的字段数、JSON 总大小和嵌套层数，避免单条事件过大挤占队列和存储空间。
        if (value == null) return;
        if (!(value instanceof Map<?, ?> properties) || properties.size() > MAX_BUSINESS_PROPERTY_KEYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "business properties must be an object with at most 32 keys");
        }
        properties.forEach((key, property) -> {
            if (!(key instanceof String name) || name.isBlank() || name.length() > 128
                    || name.chars().anyMatch(Character::isISOControl)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "business property keys must be printable strings of at most 128 characters");
            }
            validatePropertyValue(property, 1);
        });
        try {
            if (BUSINESS_PROPERTY_MAPPER.writeValueAsBytes(properties).length > MAX_BUSINESS_PROPERTY_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "business properties must be at most 16384 serialized bytes");
            }
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "business properties are invalid");
        }
    }

    private void validatePropertyValue(Object value, int depth) {
        // 逐层检查属性里的对象和数组；只允许 JSON 能表达的值，并从最外层开始计算嵌套层数。
        if (depth > MAX_BUSINESS_PROPERTY_DEPTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "business properties nesting depth must be at most 3");
        }
        if (value == null || value instanceof String || value instanceof Boolean) return;
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "business property numbers must be finite");
            }
            return;
        }
        if (value instanceof Map<?, ?> nested) {
            if (nested.size() > MAX_BUSINESS_PROPERTY_KEYS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "business property objects must contain at most 32 keys");
            }
            nested.forEach((key, nestedValue) -> {
                if (!(key instanceof String name) || name.isBlank() || name.length() > 128
                        || name.chars().anyMatch(Character::isISOControl)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "business property keys must be printable strings of at most 128 characters");
                }
                validatePropertyValue(nestedValue, depth + 1);
            });
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> validatePropertyValue(item, depth + 1));
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "business property values must be JSON values");
    }

    private void validateSpan(MonitorEventEnvelope event) {
        // 调用链编号和单次调用编号负责把调用连起来，必须是指定长度、非全零的十六进制字符串。
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

        // 统一编号大小写，避免同一条调用链被拆成两条；事件时间改为该次调用的开始时间。
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
        // Profile 每个样本只保存一条调用栈及其权重，避免把整份浏览器原始对象放进事件。
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
