package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Manual read-only acceptance of production SQL with synthetic inline ClickHouse tables. */
public class MonitorAnalyticsStorageHarness {
    public static void main(String[] args) {
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(
                    "jdbc:clickhouse://clickhouse:8123/monitor", "default", System.getenv("CLICKHOUSE_PASSWORD"));
            JdbcTemplate database = new JdbcTemplate(source);
            ObjectMapper mapper = new ObjectMapper();
            String now = Timestamp.from(Instant.now().minusSeconds(10)).toString();
            List<String> behavior = new ArrayList<>();
            behavior.add(row(mapper, "pv-a", now, "s1", "known-1", "production", page("page-a")));
            behavior.add(behavior.get(0));
            behavior.add(row(mapper, "pv-b", now, "s2", "", "production", page("page-b")));
            behavior.add(row(mapper, "dwell-a1", now, "s1", "known-1", "production", dwell("page-a", 1000)));
            behavior.add(behavior.get(3));
            behavior.add(row(mapper, "dwell-a2", now, "s1", "known-1", "production", dwell("page-a", 2000)));
            behavior.add(row(mapper, "register", now, "s1", "known-1", "production",
                    Map.of("category", "business", "event", "register_success", "analyticsSampleRate", 1)));
            behavior.add(behavior.get(6));
            behavior.add(row(mapper, "sampled", now, "s3", "known-2", "production",
                    Map.of("category", "business", "event", "register_success", "analyticsSampleRate", 0.5)));
            behavior.add(row(mapper, "staging", now, "s4", "known-3", "staging", page("page-stage")));
            List<String> performance = List.of(
                    row(mapper, "lcp-a1", now, "s1", "known-1", "production",
                            Map.of("metric", "LCP", "value", 100, "metricId", "m1", "metricUpdate", 1)),
                    row(mapper, "lcp-a2", now, "s1", "known-1", "production",
                            Map.of("metric", "LCP", "value", 200, "metricId", "m1", "metricUpdate", 2)),
                    row(mapper, "lcp-legacy", now, "s2", "", "production", Map.of("metric", "LCP", "value", 300)),
                    row(mapper, "resource", now, "s1", "known-1", "production",
                            Map.of("metric", "ResourceDuration", "value", 50, "resource", "https://app.example/a.js",
                                    "initiatorType", "script", "transferSize", 1000, "dnsMs", 2, "cacheStatus", "unknown")));
            String behaviorTable = table(behavior);
            String performanceTable = table(performance);
            JdbcTemplate fixtures = new JdbcTemplate() {
                private String replace(String sql) {
                    return sql.replace("monitor.behavior_event", behaviorTable)
                            .replace("monitor.performance_event", performanceTable);
                }
                @Override public Map<String, Object> queryForMap(String sql, Object... parameters) {
                    return database.queryForMap(replace(sql), parameters);
                }
                @Override public List<Map<String, Object>> queryForList(String sql, Object... parameters) {
                    return database.queryForList(replace(sql), parameters);
                }
            };
            MonitorProject project = new MonitorProject();
            project.setProjectKey("analytics-storage-fixture");
            var result = new MonitorBusinessAnalyticsService(fixtures).query(project, 24, "production", "web-1", null, null);
            var summary = result.summary();
            if (summary.pv() != 2 || summary.knownUserUv() != 1 || summary.sessions() != 2
                    || summary.businessEventCount() != 1 || summary.visibleDwellMs() != 3000
                    || summary.avgVisibleDwellMs() != 1500 || summary.excludedSampled() != 1) throw new IllegalStateException();
            if (result.pages().size() != 1 || result.events().size() != 1 || result.hourly().size() != 1) {
                throw new IllegalStateException();
            }
            var filtered = new MonitorBusinessAnalyticsService(fixtures).query(project, 24, "production", "web-1",
                    "https://app.example/register", "register_success");
            if (filtered.summary().pv() != 2 || filtered.summary().businessEventCount() != 1) throw new IllegalStateException();
            var metrics = new MonitorQueryService(fixtures, null, null, null, null, null, null)
                    .performance(project, 24, "production", "web-1");
            var summaries = (List<Map<String, Object>>) metrics.get("summary");
            var lcp = summaries.stream().filter(row -> "LCP".equals(row.get("metric"))).findFirst().orElseThrow();
            if (((Number) lcp.get("samples")).longValue() != 2 || ((Number) lcp.get("avgValue")).doubleValue() != 250) {
                throw new IllegalStateException();
            }
            var resources = (List<Map<String, Object>>) metrics.get("resources");
            if (resources.size() != 1 || resources.get(0).get("p75TlsMs") != null) throw new IllegalStateException();
            System.out.println("ANALYTICS_STORAGE_ACCEPTANCE=passed pv=2 knownUv=1 events=1 dwellMs=3000 lcpSamples=2 lcpAvg=250");
        } catch (Exception error) {
            System.out.println("ANALYTICS_STORAGE_ACCEPTANCE=failed category=" + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static Map<String, Object> page(String id) {
        return Map.of("category", "page", "event", "page_view", "pageViewId", id, "analyticsSampleRate", 1);
    }
    private static Map<String, Object> dwell(String id, int duration) {
        return Map.of("category", "page", "event", "page_dwell", "pageViewId", id,
                "durationMs", duration, "analyticsSampleRate", 1);
    }
    private static String row(ObjectMapper mapper, String eventId, String time, String session, String user,
                              String environment, Map<String, Object> data) throws Exception {
        return "(" + String.join(",", List.of(quote(eventId), quote("analytics-storage-fixture"), quote(time),
                quote(session), quote(user), quote("web-1"), quote(environment), quote("https://app.example/register"),
                quote(""), quote(mapper.writeValueAsString(Map.of("data", data))))) + ")";
    }
    private static String table(List<String> rows) {
        return "(SELECT * FROM VALUES('event_id String,project_id String,event_time DateTime64(3)," +
                "session_id String,user_id String,release String,environment String,page_url String," +
                "trace_id String,payload String'," + String.join(",", rows) + "))";
    }
    private static String quote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
