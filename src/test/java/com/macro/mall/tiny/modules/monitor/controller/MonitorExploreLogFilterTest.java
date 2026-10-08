package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreAggregationResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorLogSearchResult;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorExploreLogFilterTest {

    private final MonitorAdminService adminService = mock(MonitorAdminService.class);
    private final MonitorQueryService queryService = mock(MonitorQueryService.class);
    private final MonitorLogQueryService logQueryService = mock(MonitorLogQueryService.class);
    private final MonitorAdminController controller = new MonitorAdminController(
            adminService,
            queryService,
            mock(MonitorReplayService.class),
            mock(MonitorSourceMapService.class),
            mock(ObjectMapper.class),
            mock(MonitorProjectService.class),
            mock(MonitorProjectAccessService.class),
            mock(MonitorAlertSilenceService.class),
            mock(MonitorAlertDeliveryService.class),
            logQueryService,
            mock(MonitorReleaseHealthService.class),
            mock(MonitorSavedExploreQueryService.class),
            mock(MonitorDashboardService.class),
            mock(MonitorAlertNotificationRouteService.class)
    );

    @Test
    void appliesUserAndMultipleTagFiltersToLogsInsteadOfIgnoringThem() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(logQueryService.search(eq(project), eq(null), eq(24), eq(101), eq(""), eq(null), eq(null), eq(null),
                eq("user-1"), eq(Map.of("region", "cn-east", "tier", "gold"))))
                .thenReturn(new MonitorLogSearchResult(null, List.of()));

        controller.explore("store-a", 24, "logs", null, null, null,
                "tag.region:cn-east tag.tier:gold", "user-1", null, null, 100, 0);

        verify(logQueryService).search(eq(project), eq(null), eq(24), eq(101), eq(""), eq(null), eq(null), eq(null),
                eq("user-1"), eq(Map.of("region", "cn-east", "tier", "gold")));
    }

    @Test
    void mapsPerEntryTraceEnvironmentAndReleaseMetadataForLogsOnlyResults() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(logQueryService.search(project, null, 24, 101, "", null, null, null, null, Map.of()))
                .thenReturn(new MonitorLogSearchResult(null, List.of(new MonitorLogSearchResult.Entry(
                        "2026-10-03T10:00:00Z", "request finished", Map.of(), Map.of(
                        "monitor_trace_id", "0123456789abcdef0123456789abcdef",
                        "monitor_environment", "production",
                        "monitor_release", "web-2")))));

        var result = controller.explore("store-a", 24, "logs", null, null, null,
                null, null, null, null, 100, 0).getData();

        Map<String, Object> event = result.events().get(0);
        assertEquals("0123456789abcdef0123456789abcdef", event.get("trace_id"));
        assertEquals("production", event.get("environment"));
        assertEquals("web-2", event.get("release"));
    }

    @Test
    void mapsPerEntryTraceEnvironmentAndReleaseMetadataInMixedExploreResults() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.explore(eq(project), eq(24), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(101), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(), false, 100));
        when(logQueryService.search(project, null, 24, 101, "", null, null, null, null, Map.of()))
                .thenReturn(new MonitorLogSearchResult(null, List.of(new MonitorLogSearchResult.Entry(
                        "2026-10-03T10:00:00Z", "request finished", Map.of(), Map.of(
                        "monitor_trace_id", "0123456789abcdef0123456789abcdef",
                        "monitor_environment", "production",
                        "monitor_release", "web-2")))));

        var result = controller.explore("store-a", 24, null, null, null, null,
                null, null, null, null, 100, 0).getData();

        Map<String, Object> event = result.events().get(0);
        assertEquals("0123456789abcdef0123456789abcdef", event.get("trace_id"));
        assertEquals("production", event.get("environment"));
        assertEquals("web-2", event.get("release"));
    }

    @Test
    void rejectsGroupedTraceFiltersForLogsUntilMultipleTraceSearchIsSupported() {
        when(adminService.requireProject("store-a")).thenReturn(new MonitorProject());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.explore("store-a", 24, "logs", null, null, null,
                        "(trace:abc OR trace:def)", null, null, null, 100, 0));

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void includesLogsInDefaultExploreAndAppliesSupportedTraceLevelAndTextFilters() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.explore(eq(project), eq(24), eq(null), eq(null), eq(null), eq(null),
                eq("trace:0123456789abcdef0123456789abcdef level:error checkout failed"),
                eq(null), eq(null), eq(null), eq(101), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(Map.of(
                        "signal_type", "error", "event_id", "error-1", "event_time", "2026-10-03T10:00:00Z")), false, 101));
        when(logQueryService.search(eq(project), eq("0123456789abcdef0123456789abcdef"), eq(24), eq(101),
                eq("checkout failed"), eq("error"), eq(null), eq(null), eq(null), eq(Map.of())))
                .thenReturn(new MonitorLogSearchResult("0123456789abcdef0123456789abcdef", List.of(
                        new MonitorLogSearchResult.Entry("2026-10-03T10:01:00Z", "checkout failed", Map.of(), Map.of()))));

        var result = controller.explore("store-a", 24, null, null, null, null,
                "trace:0123456789abcdef0123456789abcdef level:error checkout failed",
                null, null, null, 100, 0).getData();

        assertEquals(2, result.events().size());
        assertEquals("logs", result.events().get(0).get("signal_type"));
        assertEquals("error", result.events().get(1).get("signal_type"));
        verify(logQueryService).search(eq(project), eq("0123456789abcdef0123456789abcdef"), eq(24), eq(101),
                eq("checkout failed"), eq("error"), eq(null), eq(null), eq(null), eq(Map.of()));
    }

    @Test
    void includesLogsInMixedResultsWhenUserFilterCanBeAppliedToLoki() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.explore(eq(project), eq(24), eq(null), eq("production"), eq(null), eq(null), eq(null),
                eq("user-1"), eq(null), eq(null), eq(101), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(), false, 100));
        when(logQueryService.search(project, null, 24, 101, "", null, "production", null, "user-1", Map.of()))
                .thenReturn(new MonitorLogSearchResult(null, List.of()));

        controller.explore("store-a", 24, null, "production", null, null, null,
                "user-1", null, null, 100, 0);

        verify(logQueryService).search(project, null, 24, 101, "", null, "production", null, "user-1", Map.of());
    }

    @Test
    void omitsLogsWhenQueryUsesUnsupportedFieldsOrGroupedExpressions() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.explore(any(), eq(24), eq(null), eq(null), eq(null), eq(null), any(),
                eq(null), eq(null), eq(null), eq(100), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(), false, 100));

        controller.explore("store-a", 24, null, null, null, null, "url:/checkout",
                null, null, null, 100, 0);
        controller.explore("store-a", 24, null, null, null, null, "(trace:abc OR trace:def)",
                null, null, null, 100, 0);

        verifyNoInteractions(logQueryService);
    }

    @Test
    void omitsLogsWhenRequestAndQueryTraceIdsConflict() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.explore(any(), eq(24), eq(null), eq(null), eq(null), eq("0123456789abcdef0123456789abcdef"),
                eq("trace:fedcba9876543210fedcba9876543210"), eq(null), eq(null), eq(null), eq(100), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(), false, 100));

        controller.explore("store-a", 24, null, null, null, "0123456789abcdef0123456789abcdef",
                "trace:fedcba9876543210fedcba9876543210", null, null, null, 100, 0);

        verifyNoInteractions(logQueryService);
    }

    @Test
    void appliesEnvironmentReleaseAndSeverityFiltersToLogsExplore() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(logQueryService.search(project, null, 24, 101, "checkout", "ERROR", "production", "web-2", null, Map.of()))
                .thenReturn(new MonitorLogSearchResult(null, List.of()));

        controller.explore("store-a", 24, "logs", "production", "web-2", null,
                "environment:production release:web-2 level:error checkout", null, null, null, 100, 0);

        verify(logQueryService).search(project, null, 24, 101, "checkout", "ERROR", "production", "web-2", null, Map.of());
    }

    @Test
    void includesEnvironmentAndReleaseWhenMergingLokiExploreResults() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.explore(eq(project), eq(24), eq(null), eq("production"), eq("web-2"), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(101), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(), false, 101));
        when(logQueryService.search(project, null, 24, 101, "", null, "production", "web-2", null, Map.of()))
                .thenReturn(new MonitorLogSearchResult(null, List.of()));

        controller.explore("store-a", 24, null, "production", "web-2", null, null,
                null, null, null, 100, 0);

        verify(logQueryService).search(project, null, 24, 101, "", null, "production", "web-2", null, Map.of());
    }

    @Test
    void stopsMixedPaginationAtFiveHundredRowsWithoutRepeatingTheLastPage() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        List<Map<String, Object>> events = new ArrayList<>();
        List<MonitorLogSearchResult.Entry> logs = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            events.add(Map.of("signal_type", "error", "event_id", "event-" + i,
                    "event_time", Instant.ofEpochSecond(1_000 - i).toString()));
            logs.add(new MonitorLogSearchResult.Entry(Instant.ofEpochSecond(999 - i).toString(),
                    "log-" + i, Map.of(), Map.of()));
        }
        when(queryService.explore(any(), eq(24), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(500), eq(0)))
                .thenReturn(new MonitorExploreResult(events, true, 500));
        when(logQueryService.search(any(), eq(null), eq(24), eq(500), eq(""), eq(null), eq(null), eq(null),
                eq(null), eq(Map.of())))
                .thenReturn(new MonitorLogSearchResult(null, logs));

        var lastPage = controller.explore("store-a", 24, null, null, null, null, null,
                null, null, null, 100, 400).getData();

        assertEquals(100, lastPage.events().size());
        assertEquals(100, lastPage.limit());
        org.junit.jupiter.api.Assertions.assertFalse(lastPage.hasMore());
        verify(queryService).explore(any(), eq(24), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(500), eq(0));

        clearInvocations(queryService, logQueryService);
        var beyondBound = controller.explore("store-a", 24, null, null, null, null, null,
                null, null, null, 100, 500).getData();

        assertEquals(0, beyondBound.events().size());
        org.junit.jupiter.api.Assertions.assertFalse(beyondBound.hasMore());
        verifyNoInteractions(queryService, logQueryService);
    }

    @Test
    void includesLokiLogCountInDefaultSignalAggregationWhenFiltersAreSupported() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.exploreAggregate(eq(project), eq(24), eq(null), eq(null), eq(null),
                eq("0123456789abcdef0123456789abcdef"), eq("level:error checkout"), eq(null), eq(null), eq(null),
                eq("signal"), eq("count"), eq("value")))
                .thenReturn(new MonitorExploreAggregationResult("signal", "count", "value", List.of(
                        new MonitorExploreAggregationResult.Bucket("error", 2, null))));
        when(logQueryService.aggregateForExplore(project, 24, "0123456789abcdef0123456789abcdef", "checkout",
                "ERROR", null, null, null, Map.of(), "signal"))
                .thenReturn(List.of(new MonitorExploreAggregationResult.Bucket("logs", 5, null)));

        var result = controller.exploreAggregate("store-a", 24, null, null, null,
                "0123456789abcdef0123456789abcdef", "level:error checkout", null, null, null,
                "signal", "count", "value").getData();

        assertEquals(List.of(
                new MonitorExploreAggregationResult.Bucket("logs", 5, null),
                new MonitorExploreAggregationResult.Bucket("error", 2, null)), result.buckets());
        verify(logQueryService).aggregateForExplore(project, 24, "0123456789abcdef0123456789abcdef", "checkout",
                "ERROR", null, null, null, Map.of(), "signal");
    }

    @Test
    void mergesLogsIntoMixedExploreLevelBucketsCaseInsensitively() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.exploreAggregate(eq(project), eq(24), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq("level"), eq("count"), eq("value")))
                .thenReturn(new MonitorExploreAggregationResult("level", "count", "value", List.of(
                        new MonitorExploreAggregationResult.Bucket("error", 2, null))));
        when(logQueryService.aggregateForExplore(project, 24, null, "", null, null, null, null, Map.of(), "level"))
                .thenReturn(List.of(new MonitorExploreAggregationResult.Bucket("ERROR", 5, null)));

        var result = controller.exploreAggregate("store-a", 24, null, null, null,
                null, null, null, null, null, "level", "count", "value").getData();

        assertEquals(List.of(new MonitorExploreAggregationResult.Bucket("error", 7, null)), result.buckets());
        verify(logQueryService).aggregateForExplore(project, 24, null, "", null, null, null, null, Map.of(), "level");
    }

    @Test
    void rejectsUnsupportedFieldFiltersForLogOnlyAggregationInsteadOfIgnoringThem() {
        when(adminService.requireProject("store-a")).thenReturn(new MonitorProject());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.exploreAggregate("store-a", 24, "logs", null, null,
                        null, "url:/checkout", null, null, null, "signal", "count", "value"));

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(logQueryService, queryService);
    }

    @Test
    void appliesUserAndMultipleTagsToLogsAggregation() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(logQueryService.aggregateForExplore(project, 24, null, "", null, null, null, "user-a",
                Map.of("region", "cn-east", "tier", "gold"), "signal"))
                .thenReturn(List.of(new MonitorExploreAggregationResult.Bucket("logs", 2, null)));

        var result = controller.exploreAggregate("store-a", 24, "logs", null, null, null,
                "tag.tier:gold", "user-a", "region", "cn-east", "signal", "count", "value").getData();

        assertEquals(List.of(new MonitorExploreAggregationResult.Bucket("logs", 2, null)), result.buckets());
        verify(logQueryService).aggregateForExplore(project, 24, null, "", null, null, null, "user-a",
                Map.of("region", "cn-east", "tier", "gold"), "signal");
    }

    @Test
    void includesEnvironmentAndReleaseInLogCountAggregation() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(logQueryService.aggregateForExplore(project, 24, null, "", null, "production", "web-2", null, Map.of(), "signal"))
                .thenReturn(List.of(new MonitorExploreAggregationResult.Bucket("logs", 3, null)));

        var result = controller.exploreAggregate("store-a", 24, "logs", "production", "web-2",
                null, "environment:production release:web-2", null, null, null,
                "signal", "count", "value").getData();

        assertEquals(List.of(new MonitorExploreAggregationResult.Bucket("logs", 3, null)), result.buckets());
        verify(logQueryService).aggregateForExplore(project, 24, null, "", null, "production", "web-2", null, Map.of(), "signal");
    }

    @Test
    void supportsLogAggregationGroupedByLevelAndEnvironment() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(logQueryService.aggregateForExplore(project, 24, null, "", "ERROR", null, null, null, Map.of(), "level"))
                .thenReturn(List.of(new MonitorExploreAggregationResult.Bucket("ERROR", 3, null)));

        var result = controller.exploreAggregate("store-a", 24, "logs", null, null, null, "level:error",
                null, null, null, "level", "count", "value").getData();

        assertEquals("level", result.groupBy());
        assertEquals(List.of(new MonitorExploreAggregationResult.Bucket("ERROR", 3, null)), result.buckets());
        verify(logQueryService).aggregateForExplore(project, 24, null, "", "ERROR", null, null, null, Map.of(), "level");
    }

    @Test
    void supportsUniqueUserCountForLogsWithUserField() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(logQueryService.aggregateUniqueUsersForExplore(project, 24, null, "", null,
                null, null, null, Map.of(), "environment"))
                .thenReturn(List.of(new MonitorExploreAggregationResult.Bucket("production", 2, null)));

        var result = controller.exploreAggregate("store-a", 24, "logs", null, null, null, null,
                null, null, null, "environment", "count_unique", "user").getData();

        assertEquals("count_unique", result.aggregation());
        assertEquals("user", result.field());
        assertEquals(List.of(new MonitorExploreAggregationResult.Bucket("production", 2, null)), result.buckets());
        verify(logQueryService).aggregateUniqueUsersForExplore(project, 24, null, "", null,
                null, null, null, Map.of(), "environment");
        verifyNoInteractions(queryService);
    }

    @Test
    void rejectsUnsupportedLogsPercentileAndUniqueField() {
        when(adminService.requireProject("store-a")).thenReturn(new MonitorProject());

        ResponseStatusException unsupportedFunction = assertThrows(ResponseStatusException.class,
                () -> controller.exploreAggregate("store-a", 24, "logs", null, null, null, null,
                        null, null, null, "signal", "p95", "value"));
        ResponseStatusException unsupportedUniqueField = assertThrows(ResponseStatusException.class,
                () -> controller.exploreAggregate("store-a", 24, "logs", null, null, null, null,
                        null, null, null, "signal", "count_unique", "event"));

        assertEquals(400, unsupportedFunction.getStatusCode().value());
        assertEquals(400, unsupportedUniqueField.getStatusCode().value());
        verifyNoInteractions(logQueryService, queryService);
    }

    @Test
    void mergesMixedUniqueUserSetsBySignalWithoutDoubleCountingWithinEachSignal() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.exploreUniqueUsersBySignal(project, 24, null, null, null, null, null, null, null))
                .thenReturn(Map.of("error", new HashSet<>(Set.of("u1", "u2")),
                        "performance", new HashSet<>(Set.of("u3"))));
        when(logQueryService.uniqueUserIdsForExplore(project, 24, null, "", null, null, null, null, Map.of()))
                .thenReturn(Set.of("u1", "u4"));

        var result = controller.exploreAggregate("store-a", 24, null, null, null, null, null,
                null, null, null, "signal", "count_unique", "user").getData();

        assertEquals(List.of(
                new MonitorExploreAggregationResult.Bucket("error", 2, null),
                new MonitorExploreAggregationResult.Bucket("logs", 2, null),
                new MonitorExploreAggregationResult.Bucket("performance", 1, null)), result.buckets());
        verify(queryService).exploreUniqueUsersBySignal(project, 24, null, null, null, null, null, null, null);
        verify(logQueryService).uniqueUserIdsForExplore(project, 24, null, "", null, null, null, null, Map.of());
    }

    @Test
    void returnsEmptyBucketsWhenNeitherStoreHasUserIds() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.exploreUniqueUsersBySignal(project, 24, null, null, null, null, null, null, null))
                .thenReturn(Map.of());
        when(logQueryService.uniqueUserIdsForExplore(project, 24, null, "", null, null, null, null, Map.of()))
                .thenReturn(Set.of());

        var result = controller.exploreAggregate("store-a", 24, null, null, null, null, null,
                null, null, null, "signal", "count_unique", "user").getData();

        assertTrue(result.buckets().isEmpty());
    }

    @Test
    void returnsClickHouseOnlyUsersWhenLokiHasNoMatchingUserIds() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.exploreUniqueUsersBySignal(project, 24, null, null, null, null, null, null, null))
                .thenReturn(Map.of("behavior", Set.of("u1")));
        when(logQueryService.uniqueUserIdsForExplore(project, 24, null, "", null, null, null, null, Map.of()))
                .thenReturn(Set.of());

        var result = controller.exploreAggregate("store-a", 24, null, null, null, null, null,
                null, null, null, "signal", "count_unique", "user").getData();

        assertEquals(List.of(new MonitorExploreAggregationResult.Bucket("behavior", 1, null)), result.buckets());
    }

    @Test
    void rejectsMixedUniqueUserCountWhenSignalUserPairsExceedLimit() {
        when(adminService.requireProject("store-a")).thenReturn(new MonitorProject());
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        Set<String> users = new HashSet<>();
        IntStream.range(0, MonitorQueryService.EXPLORE_UNIQUE_USER_LIMIT).forEach(i -> users.add("user-" + i));
        when(queryService.exploreUniqueUsersBySignal(project, 24, null, null, null, null, null, null, null))
                .thenReturn(Map.of("error", users));
        when(logQueryService.uniqueUserIdsForExplore(project, 24, null, "", null, null, null, null, Map.of()))
                .thenReturn(Set.of("outside-limit"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.exploreAggregate("store-a", 24, null, null, null, null, null,
                        null, null, null, "signal", "count_unique", "user"));

        assertEquals(422, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("10,000 signal-user limit"));
    }

    @Test
    void mixedUniqueUserCountOnlyAllowsSignalGrouping() {
        when(adminService.requireProject("store-a")).thenReturn(new MonitorProject());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.exploreAggregate("store-a", 24, null, null, null, null, null,
                        null, null, null, "environment", "count_unique", "user"));

        assertEquals(400, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("signal grouping only"));
        verifyNoInteractions(logQueryService, queryService);
    }

    @Test
    void allowsUniqueUsersAcrossClickHouseSignalsWhenLogsAreOutsideTheTimeWindow() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(queryService.exploreAggregate(project, 720, null, null, null, null,
                null, null, null, null, "signal", "count_unique", "user"))
                .thenReturn(new MonitorExploreAggregationResult("signal", "count_unique", "user", List.of()));

        var result = controller.exploreAggregate("store-a", 720, null, null, null, null, null,
                null, null, null, "signal", "count_unique", "user").getData();

        assertEquals("count_unique", result.aggregation());
        verify(queryService).exploreAggregate(project, 720, null, null, null, null,
                null, null, null, null, "signal", "count_unique", "user");
        verifyNoInteractions(logQueryService);
    }

    @Test
    void rejectsLogAggregationWindowsLongerThanSevenDays() {
        when(adminService.requireProject("store-a")).thenReturn(new MonitorProject());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.exploreAggregate("store-a", 720, "logs", null, null, null, null,
                        null, null, null, "signal", "count", "value"));

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(logQueryService, queryService);
    }

    @Test
    void rejectsReleaseHealthWindowsLongerThanThirtyDays() {
        when(adminService.requireProject("store-a")).thenReturn(new MonitorProject());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.releaseHealth("store-a", 721));

        assertEquals(400, exception.getStatusCode().value());
    }
}
