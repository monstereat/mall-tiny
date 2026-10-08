package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorSavedExploreQueryRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorSavedExploreQueryView;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorSavedExploreQueryMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorSavedExploreQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MonitorSavedExploreQueryService {
    private static final Set<String> TYPES = Set.of("error", "performance", "behavior", "replay", "metric", "profile", "logs");
    private final MonitorSavedExploreQueryMapper mapper;
    private final MonitorProjectAccessService accessService;
    private final ObjectMapper objectMapper;

    public List<MonitorSavedExploreQueryView> list(MonitorProject project) {
        Long adminId = accessService.currentAdminId();
        return mapper.selectList(Wrappers.<MonitorSavedExploreQuery>lambdaQuery()
                        .eq(MonitorSavedExploreQuery::getProjectId, project.getId())
                        .orderByAsc(MonitorSavedExploreQuery::getName)
                        .orderByAsc(MonitorSavedExploreQuery::getId))
                .stream().map(saved -> view(saved, adminId)).toList();
    }

    public MonitorSavedExploreQueryView create(MonitorProject project, MonitorSavedExploreQueryRequest request) {
        validate(request);
        MonitorSavedExploreQuery saved = new MonitorSavedExploreQuery();
        saved.setProjectId(project.getId());
        saved.setName(request.getName().trim());
        saved.setCriteriaJson(writeCriteria(request));
        saved.setCreatedBy(accessService.currentAdminId());
        try {
            mapper.insert(saved);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a saved query with this name already exists");
        }
        return view(saved, accessService.currentAdminId());
    }

    public MonitorSavedExploreQueryView update(MonitorProject project, Long id, MonitorSavedExploreQueryRequest request) {
        validate(request);
        MonitorSavedExploreQuery saved = require(project, id);
        requireCreator(saved);
        saved.setName(request.getName().trim());
        saved.setCriteriaJson(writeCriteria(request));
        try {
            mapper.updateById(saved);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a saved query with this name already exists");
        }
        return view(saved, accessService.currentAdminId());
    }

    public void delete(MonitorProject project, Long id) {
        MonitorSavedExploreQuery saved = require(project, id);
        requireCreator(saved);
        mapper.deleteById(id);
    }

    private MonitorSavedExploreQuery require(MonitorProject project, Long id) {
        MonitorSavedExploreQuery saved = mapper.selectOne(Wrappers.<MonitorSavedExploreQuery>lambdaQuery()
                .eq(MonitorSavedExploreQuery::getProjectId, project.getId())
                .eq(MonitorSavedExploreQuery::getId, id).last("LIMIT 1"));
        if (saved == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "saved query not found");
        return saved;
    }

    private void requireCreator(MonitorSavedExploreQuery saved) {
        if (!java.util.Objects.equals(saved.getCreatedBy(), accessService.currentAdminId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the saved query creator can change it");
        }
    }

    private void validate(MonitorSavedExploreQueryRequest request) {
        request.setName(request.getName().trim());
        if (StringUtils.hasText(request.getType())) {
            request.setType(request.getType().trim().toLowerCase());
            if (!TYPES.contains(request.getType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported Explore signal type");
            }
        }
        MonitorExploreQueryParser.Parsed parsed = MonitorExploreQueryParser.parse(request.getQuery());
        boolean hasTagKey = StringUtils.hasText(request.getTagKey());
        boolean hasTagValue = StringUtils.hasText(request.getTagValue());
        if (hasTagKey != hasTagValue) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tagKey and tagValue must be supplied together");
        }
        if (StringUtils.hasText(request.getUserId()) && request.getUserId().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be at most 128 characters");
        }
        if (hasTagKey && (request.getTagKey().length() > 64 || request.getTagValue().length() > 128
                || !request.getTagKey().matches("[A-Za-z0-9_.-]{1,64}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tagKey must be at most 64 characters and tagValue at most 128 characters");
        }
        String groupBy = request.getGroupBy() == null ? "signal" : request.getGroupBy().trim();
        if (!Set.of("signal", "environment", "release", "url", "level").contains(groupBy.toLowerCase())
                && !groupBy.matches("(?i)tag\\.[A-Za-z0-9_.-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported Explore aggregation dimension");
        }
        request.setGroupBy(groupBy);
        if ("logs".equalsIgnoreCase(request.getType())
                && !Set.of("signal", "environment", "release", "level").contains(groupBy.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Logs saved queries support signal, environment, release or level aggregation");
        }
        String aggregation = request.getAggregation() == null ? "count" : request.getAggregation().trim().toLowerCase();
        if (!Set.of("count", "count_unique", "sum", "avg", "min", "max", "p50", "p75", "p95")
                .contains(aggregation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported Explore aggregation");
        }
        String field = request.getField() == null ? "value" : request.getField().trim();
        if ("count_unique".equals(aggregation)) {
            if (!Set.of("user", "event", "trace", "url").contains(field.toLowerCase())
                    && !field.matches("(?i)tag\\.[A-Za-z0-9_.-]{1,64}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported Explore unique-count field");
            }
        } else if (Set.of("sum", "avg", "min", "max", "p50", "p75", "p95").contains(aggregation)) {
            if (!"value".equalsIgnoreCase(field)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "numeric aggregations support the value field only");
            }
            boolean percentile = Set.of("p50", "p75", "p95").contains(aggregation);
            String type = StringUtils.hasText(request.getType())
                    ? request.getType().trim().toLowerCase(Locale.ROOT) : "";
            if (percentile && !Set.of("performance", "metric").contains(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "percentile aggregations require Performance or Metrics signal type");
            }
            if (!percentile && !Set.of("performance", "metric", "logs", "").contains(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "numeric aggregations require Performance, Metrics, Logs or mixed signal type");
            }
        }
        request.setAggregation(aggregation);
        request.setField(field);
        boolean logs = "logs".equalsIgnoreCase(request.getType());
        boolean mixed = !StringUtils.hasText(request.getType());
        boolean basicNumeric = Set.of("sum", "avg", "min", "max").contains(aggregation);
        if (logs && (!Set.of("signal", "environment", "release", "level").contains(groupBy.toLowerCase(Locale.ROOT))
                || request.getHours() > 168
                || !("count".equals(aggregation) && "value".equalsIgnoreCase(field)
                    || "count_unique".equals(aggregation) && "user".equalsIgnoreCase(field)
                    || basicNumeric && "value".equalsIgnoreCase(field)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Logs saved queries support count, count_unique(user) or numeric value aggregation by signal, environment, release or level for up to 7 days");
        }
        boolean mixedNumeric = mixed && basicNumeric;
        boolean mixedUniqueUsers = mixed && "count_unique".equals(aggregation)
                && "user".equalsIgnoreCase(field) && request.getHours() <= 168;
        if (mixedNumeric && (request.getHours() > 168
                || !Set.of("signal", "environment", "release", "level").contains(groupBy.toLowerCase(Locale.ROOT)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mixed numeric saved queries support signal, environment, release or level grouping for up to 7 days");
        }
        if (logs || mixedNumeric || mixedUniqueUsers) {
            validateLokiCompatibleFilters(request, parsed, groupBy,
                    mixedUniqueUsers ? "signal" : null);
        }
        boolean hasFormula = StringUtils.hasText(request.getFormula());
        boolean hasFormulaMetrics = request.getFormulaMetrics() != null && !request.getFormulaMetrics().isEmpty();
        if (hasFormula != hasFormulaMetrics) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "formula and formulaMetrics must be supplied together");
        }
        if (hasFormula) {
            List<String> metricNames = request.getFormulaMetrics().stream().map(String::trim).distinct().toList();
            if (!"metric".equals(request.getType()) || metricNames.size() < 2 || metricNames.size() > 5
                    || metricNames.size() != request.getFormulaMetrics().size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "saved metric formula requires 2 to 5 distinct Metrics names");
            }
            try {
                MonitorMetricFormulaEvaluator.validate(request.getFormula(), metricNames.size());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            request.setFormula(request.getFormula().trim());
            request.setFormulaMetrics(metricNames);
        }
    }

    private void validateLokiCompatibleFilters(
            MonitorSavedExploreQueryRequest request, MonitorExploreQueryParser.Parsed parsed,
            String groupBy, String requiredGroupBy) {
        if (requiredGroupBy != null && !requiredGroupBy.equalsIgnoreCase(groupBy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mixed count_unique(user) saved queries support signal grouping only");
        }
        if (parsed.expression() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Logs aggregation supports simple Loki-compatible filters only");
        }
        String traceId = request.getTraceId();
        String environment = request.getEnvironment();
        String release = request.getRelease();
        String userId = request.getUserId();
        String severity = null;
        Map<String, String> tags = new HashMap<>();
        if (StringUtils.hasText(request.getTagKey())) tags.put(request.getTagKey(), request.getTagValue());
        for (MonitorExploreQueryParser.Term term : parsed.terms()) {
            if (term.negated() || !Set.of("trace", "level", "environment", "release", "user", "tag")
                    .contains(term.field())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Logs aggregation supports environment, release, trace, severity, user, tag and free-text filters only");
            }
            switch (term.field()) {
                case "trace" -> traceId = requireMatchingFilter(traceId, term.value(), true, "trace filters must match");
                case "environment" -> environment = requireMatchingFilter(environment, term.value(), false,
                        "environment filters must match");
                case "release" -> release = requireMatchingFilter(release, term.value(), false,
                        "release filters must match");
                case "user" -> userId = requireMatchingFilter(userId, term.value(), false,
                        "user filters must match");
                case "level" -> {
                    String value = term.value().trim().toUpperCase(Locale.ROOT);
                    if (!Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL").contains(value)
                            || severity != null && !severity.equals(value)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "unsupported or conflicting log severity filter");
                    }
                    severity = value;
                }
                case "tag" -> {
                    String previous = tags.putIfAbsent(term.tagKey(), term.value());
                    if (previous != null && !previous.equals(term.value())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate tag filters must match");
                    }
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "unsupported Logs aggregation filter");
            }
        }
    }

    private String requireMatchingFilter(String existing, String value, boolean ignoreCase, String message) {
        if (StringUtils.hasText(existing) && (ignoreCase
                ? !existing.equalsIgnoreCase(value) : !existing.equals(value))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return StringUtils.hasText(existing) ? existing : value;
    }

    private String writeCriteria(MonitorSavedExploreQueryRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalArgumentException("saved Explore query serialization failed", e);
        }
    }

    private MonitorSavedExploreQueryView view(MonitorSavedExploreQuery saved, Long adminId) {
        try {
            return new MonitorSavedExploreQueryView(saved.getId(), saved.getName(),
                    objectMapper.readValue(saved.getCriteriaJson(), MonitorSavedExploreQueryRequest.class),
                    saved.getCreatedBy(), java.util.Objects.equals(saved.getCreatedBy(), adminId), saved.getCreateTime(), saved.getUpdateTime());
        } catch (Exception e) {
            throw new IllegalStateException("stored Explore query is invalid", e);
        }
    }
}
