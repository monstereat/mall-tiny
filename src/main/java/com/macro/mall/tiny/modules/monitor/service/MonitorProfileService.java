package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class MonitorProfileService {
    private final JdbcTemplate clickHouse;
    private final ObjectMapper objectMapper;

    public MonitorProfileService(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse, ObjectMapper objectMapper) {
        this.clickHouse = clickHouse;
        this.objectMapper = objectMapper;
    }

    public record ProfileSummary(String eventId, String eventTime, String name, String unit,
                                 String release, String environment, int sampleCount, double totalValue) {}
    public record FlameNode(String name, double value, List<FlameNode> children) {}
    public record ProfileDetail(ProfileSummary profile, List<FlameNode> flamegraph) {}

    public List<ProfileSummary> list(MonitorProject project, int hours, String environment, String release) {
        int safeHours = Math.max(1, Math.min(720, hours));
        StringBuilder sql = new StringBuilder("SELECT event_id, toString(event_time) AS event_time_text, payload, release, environment " +
                "FROM monitor.profile_event WHERE project_id=? AND event_time>=? ");
        List<Object> args = new ArrayList<>(List.of(project.getProjectKey(), Timestamp.from(Instant.now().minusSeconds(safeHours * 3600L))));
        appendFilter(sql, args, "environment", environment);
        appendFilter(sql, args, "release", release);
        sql.append(" GROUP BY event_id,event_time,payload,release,environment ORDER BY event_time DESC LIMIT 100");
        return clickHouse.query(sql.toString(), (rs, rowNum) -> {
            JsonNode data = readData(rs.getString("payload"));
            return summary(rs.getString("event_id"), rs.getString("event_time_text"), data,
                    rs.getString("release"), rs.getString("environment"));
        }, args.toArray());
    }

    public ProfileDetail get(MonitorProject project, String eventId) {
        List<Map<String, Object>> rows = clickHouse.queryForList(
                "SELECT event_id,toString(event_time) AS event_time_text,payload,release,environment " +
                        "FROM monitor.profile_event WHERE project_id=? AND event_id=? GROUP BY event_id,event_time,payload,release,environment LIMIT 1",
                project.getProjectKey(), eventId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found");
        Map<String, Object> row = rows.get(0);
        JsonNode data = readData(String.valueOf(row.get("payload")));
        ProfileSummary summary = summary(eventId, String.valueOf(row.get("event_time_text")), data,
                String.valueOf(row.get("release")), String.valueOf(row.get("environment")));
        return new ProfileDetail(summary, aggregate(data.path("samples")));
    }

    private void appendFilter(StringBuilder sql, List<Object> args, String column, String value) {
        if (StringUtils.hasText(value)) {
            sql.append(" AND ").append(column).append("=?");
            args.add(value.trim());
        }
    }

    private JsonNode readData(String payload) {
        try {
            return objectMapper.readTree(payload).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("stored profile payload is invalid", e);
        }
    }

    private ProfileSummary summary(String eventId, String eventTime, JsonNode data, String release, String environment) {
        JsonNode samples = data.path("samples");
        double total = 0;
        if (samples.isArray()) for (JsonNode sample : samples) total += sample.path("value").asDouble();
        return new ProfileSummary(eventId, eventTime, data.path("name").asText("CPU"),
                data.path("unit").asText("samples"), release, environment, samples.size(), total);
    }

    private List<FlameNode> aggregate(JsonNode samples) {
        Map<String, Node> roots = new TreeMap<>();
        if (samples.isArray()) {
            for (JsonNode sample : samples) {
                JsonNode stack = sample.path("stack");
                double value = sample.path("value").asDouble();
                Map<String, Node> level = roots;
                for (JsonNode frame : stack) {
                    String name = frame.asText();
                    Node node = level.computeIfAbsent(name, Node::new);
                    node.value += value;
                    level = node.children;
                }
            }
        }
        return roots.values().stream().map(Node::toView).toList();
    }

    private static final class Node {
        private final String name;
        private double value;
        private final Map<String, Node> children = new TreeMap<>();
        private Node(String name) { this.name = name; }
        private FlameNode toView() {
            return new FlameNode(name, value, children.values().stream().map(Node::toView).toList());
        }
    }
}
