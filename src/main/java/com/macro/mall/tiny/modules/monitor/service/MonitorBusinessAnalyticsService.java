package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorBusinessAnalyticsResult;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class MonitorBusinessAnalyticsService {

    private static final String PAGE_KEY = "coalesce(nullIf(page_url,'')," +
            "nullIf(JSONExtractString(payload,'data','name'),''),'(unknown)')";
    private static final String BUSINESS_PAGE_KEY = "coalesce(" +
            "nullIf(page_url,''),nullIf(JSONExtractString(payload,'data','properties','page_name'),''),'(unknown)')";
    private static final Pattern EVENT_NAME = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");
    private static final int MAX_RANKED_ROWS = 100;

    private final JdbcTemplate clickHouse;

    public MonitorBusinessAnalyticsService(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse) {
        this.clickHouse = clickHouse;
    }

    public MonitorBusinessAnalyticsResult query(
            MonitorProject project,
            int hours,
            String environment,
            String release,
            String page,
            String event) {
        if (hours < 1 || hours > 168) {
            throw badRequest("hours must be between 1 and 168");
        }
        String safeEnvironment = boundedFilter(environment, 64, "environment");
        String safeRelease = boundedFilter(release, 128, "release");
        String safePage = boundedFilter(page, 512, "page");
        String safeEvent = boundedEvent(event);

        Instant end = Instant.now();
        Instant start = end.minus(hours, ChronoUnit.HOURS);
        Timestamp from = Timestamp.from(start);
        Timestamp to = Timestamp.from(end);

        SummaryData summary = loadSummary(project, from, to, safeEnvironment, safeRelease, safePage, safeEvent);
        List<Map<String, Object>> pageRows = loadPages(project, from, to, safeEnvironment, safeRelease, safePage);
        List<Map<String, Object>> eventRows = loadEvents(project, from, to, safeEnvironment, safeRelease,
                safePage, safeEvent);
        List<Map<String, Object>> hourlyRows = loadHourly(project, from, to, safeEnvironment, safeRelease,
                safePage, safeEvent);

        boolean pagesTruncated = pageRows.size() > MAX_RANKED_ROWS;
        boolean eventsTruncated = eventRows.size() > MAX_RANKED_ROWS;
        if (pagesTruncated) pageRows = pageRows.subList(0, MAX_RANKED_ROWS);
        if (eventsTruncated) eventRows = eventRows.subList(0, MAX_RANKED_ROWS);

        long pv = summary.pv();
        double visibleDwellMs = summary.visibleDwellMs();
        MonitorBusinessAnalyticsResult.Summary summaryView = new MonitorBusinessAnalyticsResult.Summary(
                pv,
                summary.knownUserUv(),
                summary.sessions(),
                summary.businessEventCount(),
                visibleDwellMs,
                pv == 0 ? 0 : visibleDwellMs / pv,
                summary.excludedSampled()
        );

        List<MonitorBusinessAnalyticsResult.PageBucket> pages = pageRows.stream()
                .map(row -> new MonitorBusinessAnalyticsResult.PageBucket(
                        text(row.get("page_url")),
                        count(row.get("page_views")),
                        count(row.get("unique_users")),
                        count(row.get("sessions")),
                        number(row.get("visible_dwell_ms")),
                        number(row.get("avg_visible_dwell_ms"))))
                .toList();
        List<MonitorBusinessAnalyticsResult.EventBucket> events = eventRows.stream()
                .map(row -> new MonitorBusinessAnalyticsResult.EventBucket(
                        text(row.get("event_name")),
                        count(row.get("event_count")),
                        count(row.get("unique_users")),
                        count(row.get("sessions"))))
                .toList();
        List<MonitorBusinessAnalyticsResult.HourlyBucket> hourly = hourlyRows.stream()
                .map(row -> new MonitorBusinessAnalyticsResult.HourlyBucket(
                        text(row.get("bucket")),
                        count(row.get("page_views")),
                        count(row.get("event_count"))))
                .toList();

        return new MonitorBusinessAnalyticsResult(
                summaryView,
                pages,
                events,
                hourly,
                pagesTruncated,
                eventsTruncated,
                "Page and event rankings are limited to 100 rows; summary values use the full filtered result set.");
    }

    private SummaryData loadSummary(
            MonitorProject project,
            Timestamp from,
            Timestamp to,
            String environment,
            String release,
            String page,
            String event) {
        Filter pageViews = filter(project, from, to, environment, release, "page", "page_view", true);
        addFilter(pageViews, PAGE_KEY, page);

        Filter dwell = filter(project, from, to, environment, release, "page", "page_dwell", true);

        Filter business = filter(project, from, to, environment, release, "business", null, true);
        addFilter(business, BUSINESS_PAGE_KEY, page);
        addFilter(business, "JSONExtractString(payload,'data','event')", event);

        Filter sampledBusiness = filter(project, from, to, environment, release, "business", null,
                false);
        addFilter(sampledBusiness, BUSINESS_PAGE_KEY, page);
        addFilter(sampledBusiness, "JSONExtractString(payload,'data','event')", event);
        Filter sampledPageViews = filter(project, from, to, environment, release, "page", "page_view",
                false);
        addFilter(sampledPageViews, PAGE_KEY, page);

        String sql = "WITH page_views AS (" + pageViewSelection(pageViews) + "), " +
                "dwell_events AS (SELECT event_id,any(JSONExtractString(payload,'data','pageViewId')) AS page_view_id," +
                "any(JSONExtractFloat(payload,'data','durationMs')) AS duration_ms FROM monitor.behavior_event WHERE " +
                dwell.where() + " GROUP BY event_id), " +
                "dwell_by_view AS (SELECT page_view_id,sum(duration_ms) AS visible_dwell_ms FROM dwell_events " +
                "GROUP BY page_view_id), " +
                "business_events AS (" + businessEventSelection(business) + "), " +
                "sampled_business AS (SELECT event_id FROM monitor.behavior_event WHERE " + sampledBusiness.where() +
                " GROUP BY event_id), " +
                "sampled_page_views AS (SELECT JSONExtractString(payload,'data','pageViewId') AS page_view_id " +
                "FROM monitor.behavior_event WHERE " + sampledPageViews.where() + " GROUP BY page_view_id) " +
                "SELECT (SELECT count() FROM page_views) AS pv," +
                "(SELECT uniqExactIf(user_key,user_key!='') FROM page_views) AS known_user_uv," +
                "(SELECT uniqExact(session_key) FROM (SELECT session_key FROM page_views WHERE session_key!='' " +
                "UNION ALL SELECT session_key FROM business_events WHERE session_key!='') AS session_rows) AS sessions," +
                "(SELECT count() FROM business_events) AS business_event_count," +
                "(SELECT ifNull(sum(ifNull(dwell_by_view.visible_dwell_ms,0)),0) FROM page_views " +
                "LEFT JOIN dwell_by_view ON page_views.page_view_id=dwell_by_view.page_view_id) AS visible_dwell_ms," +
                "(SELECT count() FROM sampled_business)+(SELECT count() FROM sampled_page_views) AS excluded_sampled";
        List<Object> args = new ArrayList<>();
        args.addAll(pageViews.args());
        args.addAll(dwell.args());
        args.addAll(business.args());
        args.addAll(sampledBusiness.args());
        args.addAll(sampledPageViews.args());
        Map<String, Object> row = clickHouse.queryForMap(sql, args.toArray());
        return new SummaryData(
                count(row.get("pv")),
                count(row.get("known_user_uv")),
                count(row.get("sessions")),
                count(row.get("business_event_count")),
                number(row.get("visible_dwell_ms")),
                count(row.get("excluded_sampled")));
    }

    private List<Map<String, Object>> loadPages(
            MonitorProject project,
            Timestamp from,
            Timestamp to,
            String environment,
            String release,
            String page) {
        Filter pageViews = filter(project, from, to, environment, release, "page", "page_view", true);
        addFilter(pageViews, PAGE_KEY, page);
        Filter dwell = filter(project, from, to, environment, release, "page", "page_dwell", true);
        String sql = "WITH page_views AS (" + pageViewSelection(pageViews) + "), " +
                "dwell_events AS (SELECT event_id,any(JSONExtractString(payload,'data','pageViewId')) AS page_view_id," +
                "any(JSONExtractFloat(payload,'data','durationMs')) AS duration_ms FROM monitor.behavior_event WHERE " +
                dwell.where() + " GROUP BY event_id), " +
                "dwell_by_view AS (SELECT page_view_id,sum(duration_ms) AS visible_dwell_ms FROM dwell_events " +
                "GROUP BY page_view_id) " +
                "SELECT page_views.page_key AS page_url,count() AS page_views," +
                "uniqExactIf(page_views.user_key,page_views.user_key!='') AS unique_users," +
                "uniqExactIf(page_views.session_key,page_views.session_key!='') AS sessions,"+
                "sum(ifNull(dwell_by_view.visible_dwell_ms,0)) AS visible_dwell_ms,"+
                "if(count()=0,0,sum(ifNull(dwell_by_view.visible_dwell_ms,0))/count()) AS avg_visible_dwell_ms " +
                "FROM page_views LEFT JOIN dwell_by_view ON page_views.page_view_id=dwell_by_view.page_view_id " +
                "GROUP BY page_views.page_key ORDER BY count() DESC,page_views.page_key ASC LIMIT 101";
        return clickHouse.queryForList(sql, combined(pageViews.args(), dwell.args()));
    }

    private List<Map<String, Object>> loadEvents(
            MonitorProject project,
            Timestamp from,
            Timestamp to,
            String environment,
            String release,
            String page,
            String event) {
        Filter business = filter(project, from, to, environment, release, "business", null, true);
        addFilter(business, BUSINESS_PAGE_KEY, page);
        addFilter(business, "JSONExtractString(payload,'data','event')", event);
        String sql = "WITH business_events AS (" + businessEventSelection(business) + ") " +
                "SELECT event_name,count() AS event_count,uniqExactIf(user_key,user_key!='') AS unique_users,"+
                "uniqExactIf(session_key,session_key!='') AS sessions FROM business_events " +
                "GROUP BY business_events.event_name ORDER BY count() DESC,business_events.event_name ASC LIMIT 101";
        return clickHouse.queryForList(sql, business.args().toArray());
    }

    private List<Map<String, Object>> loadHourly(
            MonitorProject project,
            Timestamp from,
            Timestamp to,
            String environment,
            String release,
            String page,
            String event) {
        Filter pageViews = filter(project, from, to, environment, release, "page", "page_view", true);
        addFilter(pageViews, PAGE_KEY, page);
        Filter business = filter(project, from, to, environment, release, "business", null, true);
        addFilter(business, BUSINESS_PAGE_KEY, page);
        addFilter(business, "JSONExtractString(payload,'data','event')", event);
        String sql = "WITH page_views AS (" + pageViewSelection(pageViews) + "), " +
                "business_events AS (" + businessEventSelection(business) + ") " +
                "SELECT hourly_rows.bucket,sum(hourly_rows.page_views) AS page_views,"+
                "sum(hourly_rows.event_count) AS event_count FROM ("+
                "SELECT toStartOfHour(occurred_at) AS bucket,count() AS page_views,toUInt64(0) AS event_count " +
                "FROM page_views GROUP BY bucket UNION ALL "+
                "SELECT toStartOfHour(occurred_at) AS bucket,toUInt64(0) AS page_views,count() AS event_count "+
                "FROM business_events GROUP BY bucket) AS hourly_rows GROUP BY hourly_rows.bucket ORDER BY hourly_rows.bucket";
        return clickHouse.queryForList(sql, combined(pageViews.args(), business.args()));
    }

    private String pageViewSelection(Filter filter) {
        return "SELECT JSONExtractString(payload,'data','pageViewId') AS page_view_id,any(" + PAGE_KEY + ")" +
                " AS page_key,any(session_id) AS session_key,any(user_id) AS user_key,min(event_time) AS occurred_at " +
                "FROM monitor.behavior_event WHERE " + filter.where() +
                " GROUP BY page_view_id";
    }

    private String businessEventSelection(Filter filter) {
        return "SELECT event_id,any(JSONExtractString(payload,'data','event')) AS event_name," +
                "any(session_id) AS session_key,any(user_id) AS user_key,min(event_time) AS occurred_at " +
                "FROM monitor.behavior_event WHERE " + filter.where() + " GROUP BY event_id";
    }

    private Filter filter(
            MonitorProject project,
            Timestamp from,
            Timestamp to,
            String environment,
            String release,
            String category,
            String event,
            boolean exactSampleOnly) {
        StringBuilder where = new StringBuilder("project_id=? AND event_time>=? AND event_time<=? " +
                "AND JSONExtractString(payload,'data','category')=?");
        List<Object> args = new ArrayList<>(List.of(project.getProjectKey(), from, to, category));
        if (StringUtils.hasText(environment)) {
            where.append(" AND environment=?");
            args.add(environment);
        }
        if (StringUtils.hasText(release)) {
            where.append(" AND release=?");
            args.add(release);
        }
        if (event != null) {
            where.append(" AND JSONExtractString(payload,'data','event')=?");
            args.add(event);
        }
        where.append(" AND JSONExtractFloat(payload,'data','analyticsSampleRate')")
                .append(exactSampleOnly ? "=1" : "!=1");
        return new Filter(where.toString(), args);
    }

    private void addFilter(Filter filter, String expression, String value) {
        if (!StringUtils.hasText(value)) return;
        filter.addEquality(expression, value);
    }

    private String boundedFilter(String value, int maxLength, String name) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw badRequest(name + " is invalid or too long");
        }
        return normalized;
    }

    private String boundedEvent(String value) {
        String event = boundedFilter(value, 64, "event");
        if (event != null && !EVENT_NAME.matcher(event).matches()) {
            throw badRequest("event must be a lowercase ASCII snake_case name");
        }
        return event;
    }

    private static Object[] combined(List<Object> first, List<Object> second) {
        List<Object> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all.toArray();
    }

    private static long count(Object raw) {
        if (raw == null) return 0;
        try {
            return new BigDecimal(raw.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "business analytics count exceeds supported limits");
        }
    }

    private static double number(Object raw) {
        if (raw == null) return 0;
        try {
            double value = raw instanceof Number number ? number.doubleValue() : Double.parseDouble(raw.toString());
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException ignored) {
            // Return the same bounded client error for malformed and non-finite aggregate values.
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "business analytics duration exceeds supported limits");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static final class Filter {
        private final StringBuilder where;
        private final List<Object> args;

        private Filter(String where, List<Object> args) {
            this.where = new StringBuilder(where);
            this.args = args;
        }

        private String where() { return where.toString(); }
        private List<Object> args() { return args; }

        private void addEquality(String expression, String value) {
            where.append(" AND ").append(expression).append("=?");
            args.add(value);
        }
    }

    private record SummaryData(long pv, long knownUserUv, long sessions, long businessEventCount,
                               double visibleDwellMs, long excludedSampled) {
    }
}
