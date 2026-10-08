package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreNumericBucket;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Manual synthetic storage acceptance; not a JUnit test. */
public class MonitorLogNumericStorageHarness {
    public static void main(String[] args) {
        try {
            MonitorLogQueryService service = new MonitorLogQueryService(null, args[0]);
            MonitorProject project = new MonitorProject();
            project.setProjectKey(args[1]);
            Instant end = Instant.ofEpochSecond(Long.parseLong(args[2]));
            for (String group : List.of("signal", "environment", "release", "level")) {
                var buckets = service.aggregateNumericForExplore(project, 1, null, null, "ERROR",
                        "production", "numeric-v1", "synthetic-user", Map.of("flow", "numeric"), group, end);
                if (buckets.size() != 1) throw new IllegalStateException();
                MonitorExploreNumericBucket bucket = buckets.get(0);
                String expectedGroup = switch (group) {
                    case "signal" -> "logs";
                    case "environment" -> "production";
                    case "release" -> "numeric-v1";
                    default -> "ERROR";
                };
                if (bucket.count() != 4 || bucket.sum() != 10.75 || bucket.min() != -2.5 || bucket.max() != 10) {
                    throw new IllegalStateException();
                }
                if (!expectedGroup.equalsIgnoreCase(bucket.value())) throw new IllegalStateException();
            }
            var missing = service.aggregateNumericForExplore(project, 1, null, null, null,
                    "absent-environment", null, null, Map.of(), "signal", end);
            if (!missing.isEmpty()) throw new IllegalStateException();
            System.out.println("LOKI_NUMERIC_STORAGE_ACCEPTANCE=passed groups=4 count=4 sum=10.75 min=-2.5 max=10 empty=passed");
        } catch (Exception error) {
            System.out.println("LOKI_NUMERIC_STORAGE_ACCEPTANCE=failed category=" + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
