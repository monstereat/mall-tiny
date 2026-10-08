package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorReleaseHealth;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MonitorReleaseHealthService {

    private record ReleaseKey(String release, String environment) {
    }

    private static final List<String> EVENT_TABLES = List.of(
            "error_event", "performance_event", "behavior_event", "replay_event", "metric_event", "profile_event");

    private final JdbcTemplate clickHouse;

    public MonitorReleaseHealthService(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse) {
        this.clickHouse = clickHouse;
    }

    public List<MonitorReleaseHealth> list(String projectKey, int hours) {
        Instant now = Instant.now();
        Instant from = now.minus(hours, ChronoUnit.HOURS);
        Timestamp fromTimestamp = Timestamp.from(from);
        Timestamp toTimestamp = Timestamp.from(now);

        String sessionSql = "WITH started_sessions AS (" +
                "SELECT release,environment,session_id FROM monitor.behavior_event " +
                "WHERE project_id=? AND event_time>=? AND event_time<=? AND release!='' AND session_id!='' " +
                "AND JSONExtractString(payload,'data','category')='session' " +
                "AND JSONExtractString(payload,'data','action')='start' " +
                "GROUP BY release,environment,session_id), " +
                "crashed_sessions AS (" +
                "SELECT release,environment,session_id,count() AS error_count FROM monitor.error_event " +
                "WHERE project_id=? AND event_time>=? AND event_time<=? AND session_id!='' " +
                "AND JSONExtractBool(payload,'data','unhandled') GROUP BY release,environment,session_id) " +
                "SELECT started_sessions.release,started_sessions.environment,count() AS sessions, " +
                "countIf(crashed_sessions.session_id!='') AS crashed_sessions, " +
                "sum(ifNull(crashed_sessions.error_count,0)) AS unhandled_errors " +
                "FROM started_sessions LEFT JOIN crashed_sessions USING(release,environment,session_id) " +
                "GROUP BY started_sessions.release,started_sessions.environment " +
                "ORDER BY started_sessions.release DESC,started_sessions.environment";
        List<Map<String, Object>> sessionRows = clickHouse.queryForList(sessionSql,
                projectKey, fromTimestamp, toTimestamp, projectKey, fromTimestamp, toTimestamp);

        StringBuilder userEvents = new StringBuilder();
        List<Object> userArgs = new ArrayList<>();
        for (String table : EVENT_TABLES) {
            if (!userEvents.isEmpty()) userEvents.append(" UNION ALL ");
            userEvents.append("SELECT release,environment,session_id,user_id FROM monitor.").append(table)
                    .append(" WHERE project_id=? AND event_time>=? AND event_time<=? AND session_id!='' AND user_id!=''");
            userArgs.add(projectKey);
            userArgs.add(fromTimestamp);
            userArgs.add(toTimestamp);
        }
        String userSql = "WITH started_sessions AS (" +
                "SELECT release,environment,session_id FROM monitor.behavior_event " +
                "WHERE project_id=? AND event_time>=? AND event_time<=? AND release!='' AND session_id!='' " +
                "AND JSONExtractString(payload,'data','category')='session' " +
                "AND JSONExtractString(payload,'data','action')='start' GROUP BY release,environment,session_id), " +
                "crashed_sessions AS (SELECT release,environment,session_id FROM monitor.error_event " +
                "WHERE project_id=? AND event_time>=? AND event_time<=? AND session_id!='' " +
                "AND JSONExtractBool(payload,'data','unhandled') GROUP BY release,environment,session_id), " +
                "user_sessions AS (SELECT release,environment,session_id,user_id FROM (" + userEvents +
                ") AS all_user_events GROUP BY release,environment,session_id,user_id), " +
                "user_health AS (SELECT users.release AS release,users.environment AS environment,users.user_id AS user_id, " +
                "max(toUInt8(crashes.session_id!='')) AS crashed FROM user_sessions AS users " +
                "INNER JOIN started_sessions AS starts ON users.release=starts.release " +
                "AND users.environment=starts.environment AND users.session_id=starts.session_id " +
                "LEFT JOIN crashed_sessions AS crashes ON users.release=crashes.release " +
                "AND users.environment=crashes.environment AND users.session_id=crashes.session_id " +
                "GROUP BY users.release,users.environment,users.user_id) " +
                "SELECT user_health.release AS release,user_health.environment AS environment, " +
                "uniqExact(user_health.user_id) AS users, " +
                "uniqExactIf(user_health.user_id,user_health.crashed=1) AS crashed_users FROM user_health " +
                "GROUP BY user_health.release,user_health.environment";
        List<Object> args = new ArrayList<>();
        args.add(projectKey);
        args.add(fromTimestamp);
        args.add(toTimestamp);
        args.add(projectKey);
        args.add(fromTimestamp);
        args.add(toTimestamp);
        args.addAll(userArgs);
        List<Map<String, Object>> userRows = clickHouse.queryForList(userSql, args.toArray());

        Map<ReleaseKey, Map<String, Object>> usersByRelease = new LinkedHashMap<>();
        for (Map<String, Object> row : userRows) {
            usersByRelease.put(new ReleaseKey(text(row.get("release")), text(row.get("environment"))), row);
        }

        List<MonitorReleaseHealth> results = new ArrayList<>();
        for (Map<String, Object> row : sessionRows) {
            String release = text(row.get("release"));
            String environment = text(row.get("environment"));
            long sessions = number(row.get("sessions"));
            long crashedSessions = number(row.get("crashed_sessions"));
            Map<String, Object> userRow = usersByRelease.get(new ReleaseKey(release, environment));
            long users = userRow == null ? 0 : number(userRow.get("users"));
            long crashedUsers = userRow == null ? 0 : number(userRow.get("crashed_users"));
            results.add(new MonitorReleaseHealth(
                    release,
                    environment,
                    sessions,
                    crashedSessions,
                    rate(sessions - crashedSessions, sessions),
                    users,
                    crashedUsers,
                    rate(users - crashedUsers, users),
                    number(row.get("unhandled_errors"))
            ));
        }
        return results;
    }

    private static double rate(long healthy, long total) {
        return total == 0 ? 0 : Math.round((healthy * 10000.0) / total) / 100.0;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
