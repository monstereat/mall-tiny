package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreNumericBucket;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorExploreNumericAggregateControllerTest {

    private final MonitorAdminService adminService = mock(MonitorAdminService.class);
    private final MonitorQueryService queryService = mock(MonitorQueryService.class);
    private final MonitorLogQueryService logQueryService = mock(MonitorLogQueryService.class);
    private final MonitorProject project = new MonitorProject();
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

    MonitorExploreNumericAggregateControllerTest() {
        project.setId(17L);
        project.setProjectKey("demo");
        when(adminService.requireProject("demo")).thenReturn(project);
    }

    @Test
    void computesMixedAverageUsingWeightedSumAndSharedTimeAnchor() {
        stubNumeric(List.of(bucket("production", 1, 0, 0, 0)),
                List.of(bucket("production", 3, 30, 5, 15)));

        var result = aggregate(null, 24, "environment", "avg", "level:error checkout").getData();

        assertEquals("environment", result.groupBy());
        assertEquals("avg", result.aggregation());
        assertEquals("value", result.field());
        assertEquals(4, result.buckets().get(0).count());
        assertEquals(7.5, result.buckets().get(0).aggregateValue());
        ArgumentCaptor<Instant> signalEnd = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> logEnd = ArgumentCaptor.forClass(Instant.class);
        verify(queryService).exploreNumericBuckets(eq(project), eq(24), any(), any(), any(), any(), any(),
                any(), any(), eq("environment"), signalEnd.capture());
        verify(logQueryService).aggregateNumericForExplore(eq(project), eq(24), any(), any(), any(), any(), any(),
                any(), anyMap(), eq("environment"), logEnd.capture());
        assertEquals(signalEnd.getValue(), logEnd.getValue());
    }

    @Test
    void supportsSumMinMaxAndAverageForNegativeAndZeroValues() {
        stubNumeric(List.of(bucket("metric", 2, 0, -5, 5)), List.of());

        assertEquals(0.0, aggregate(null, 24, "signal", "sum", null).getData().buckets().get(0).aggregateValue());
        assertEquals(0.0, aggregate(null, 24, "signal", "avg", null).getData().buckets().get(0).aggregateValue());
        assertEquals(-5.0, aggregate(null, 24, "signal", "min", null).getData().buckets().get(0).aggregateValue());
        assertEquals(5.0, aggregate(null, 24, "signal", "max", null).getData().buckets().get(0).aggregateValue());
    }

    @Test
    void supportsLogsOnlyNumericAggregationWithoutCallingSignalStore() {
        stubLogs(List.of(bucket("logs", 2, -6, -4, -2)));

        var result = aggregate("logs", 24, "signal", "sum", null).getData();

        assertEquals(-6.0, result.buckets().get(0).aggregateValue());
        assertEquals(2, result.buckets().get(0).count());
        verifyNoInteractions(queryService);
    }

    @Test
    void normalizesEmptyValuesAndLowercasesLevelsBeforeMerging() {
        stubNumeric(List.of(bucket("ERROR", 1, 2, 2, 2), bucket("(EMPTY)", 1, 0, 0, 0)),
                List.of(bucket("error", 1, 3, 3, 3), bucket("", 1, -1, -1, -1)));

        var result = aggregate(null, 24, "level", "sum", null).getData();

        assertEquals(List.of("error", "(empty)"), result.buckets().stream()
                .map(bucket -> bucket.value()).toList());
        assertEquals(5.0, result.buckets().get(0).aggregateValue());
        assertEquals(-1.0, result.buckets().get(1).aggregateValue());
    }

    @Test
    void mergesAllGroupsBeforeSortingAndApplyingTopOneHundred() {
        List<MonitorExploreNumericBucket> signalBuckets = new ArrayList<>();
        List<MonitorExploreNumericBucket> logBuckets = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            signalBuckets.add(bucket(String.format("env-%03d", i), 1, 1000, 1000, 1000));
            logBuckets.add(bucket(String.format("env-%03d", i + 100), 1, 1000, 1000, 1000));
        }
        signalBuckets.add(bucket("combined-winner", 1, 800, 800, 800));
        logBuckets.add(bucket("combined-winner", 1, 800, 800, 800));
        stubNumeric(signalBuckets, logBuckets);

        var result = aggregate(null, 24, "environment", "sum", null).getData();

        assertEquals(100, result.buckets().size());
        assertEquals("combined-winner", result.buckets().get(0).value());
        assertEquals(1600.0, result.buckets().get(0).aggregateValue());
    }

    @Test
    void returnsBucketsWhenOneSourceIsEmptyAndEmptyWhenBothAreEmpty() {
        stubNumeric(List.of(), List.of(bucket("logs", 2, 8, 3, 5)));

        var oneSource = aggregate(null, 24, "signal", "sum", null).getData();
        assertEquals(List.of("logs"), oneSource.buckets().stream().map(bucket -> bucket.value()).toList());

        stubNumeric(List.of(), List.of());
        assertTrue(aggregate(null, 24, "signal", "sum", null).getData().buckets().isEmpty());
    }

    @Test
    void rejectsUnsupportedFiltersWindowsAndGroupsBeforeQueryingEitherStore() {
        assertBadRequest(() -> aggregate(null, 24, "signal", "sum", "url:/checkout"));
        assertBadRequest(() -> aggregate(null, 169, "signal", "sum", null));
        assertBadRequest(() -> aggregate("logs", 24, "url", "sum", null));
        assertBadRequest(() -> aggregate("logs", 24, "signal", "sum", "(trace:abc OR trace:def)"));

        verifyNoInteractions(queryService, logQueryService);
    }

    @Test
    void rejectsMoreThanOneThousandMergedGroupsWith422() {
        List<MonitorExploreNumericBucket> oversizedSource = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            oversizedSource.add(bucket("source-" + i, 1, 1, 1, 1));
        }
        stubNumeric(oversizedSource, List.of());
        assertUnprocessable(() -> aggregate(null, 24, "environment", "sum", null));

        List<MonitorExploreNumericBucket> signalBuckets = new ArrayList<>();
        List<MonitorExploreNumericBucket> logBuckets = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            signalBuckets.add(bucket("signal-" + i, 1, 1, 1, 1));
            logBuckets.add(bucket("log-" + i, 1, 1, 1, 1));
        }
        stubNumeric(signalBuckets, logBuckets);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> aggregate(null, 24, "environment", "sum", null));

        assertEquals(422, exception.getStatusCode().value());
    }

    @Test
    void rejectsNonFiniteValuesAndCountOverflowWith422() {
        stubNumeric(List.of(bucket("production", 1, Double.NaN, 0, 1)), List.of());
        assertUnprocessable(() -> aggregate(null, 24, "environment", "sum", null));

        stubNumeric(List.of(bucket("production", Long.MAX_VALUE, 0, 0, 0)),
                List.of(bucket("production", 1, 0, 0, 0)));
        assertUnprocessable(() -> aggregate(null, 24, "environment", "sum", null));
    }

    private void stubNumeric(List<MonitorExploreNumericBucket> signalBuckets,
                             List<MonitorExploreNumericBucket> logBuckets) {
        when(queryService.exploreNumericBuckets(eq(project), anyInt(), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), anyString(), any(Instant.class))).thenReturn(signalBuckets);
        stubLogs(logBuckets);
    }

    private void stubLogs(List<MonitorExploreNumericBucket> buckets) {
        when(logQueryService.aggregateNumericForExplore(eq(project), anyInt(), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), anyMap(), anyString(), any(Instant.class))).thenReturn(buckets);
    }

    private com.macro.mall.tiny.common.api.CommonResult<com.macro.mall.tiny.modules.monitor.dto.MonitorExploreAggregationResult>
    aggregate(String type, int hours, String groupBy, String aggregation, String query) {
        return controller.exploreAggregate("demo", hours, type, null, null, null, query,
                null, null, null, groupBy, aggregation, "value");
    }

    private MonitorExploreNumericBucket bucket(String value, long count, double sum, double min, double max) {
        return new MonitorExploreNumericBucket(value, count, sum, min, max);
    }

    private void assertBadRequest(Runnable action) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(400, exception.getStatusCode().value());
    }

    private void assertUnprocessable(Runnable action) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(422, exception.getStatusCode().value());
    }
}
