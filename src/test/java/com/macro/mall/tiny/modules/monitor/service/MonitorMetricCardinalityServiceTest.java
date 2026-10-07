package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorMetricCardinalityMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorMetricCardinalityDimension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorMetricCardinalityServiceTest {

    private final MonitorMetricCardinalityMapper mapper = mock(MonitorMetricCardinalityMapper.class);
    private final MonitorMetricCardinalityService service = new MonitorMetricCardinalityService(mapper);

    @BeforeEach
    void setUpProjectScope() {
        when(mapper.lockProject(42L)).thenReturn(0);
        when(mapper.deleteExpiredMetricsForProject(eq(42L), any(Date.class))).thenReturn(0);
    }

    @Test
    void tracksOnlyTagDimensionsAndDeduplicatesRepeatedBatchValues() {
        when(mapper.ensureMetric(42L, "checkout.duration")).thenReturn(0);
        when(mapper.ensureDimension(42L, "checkout.duration", "region")).thenReturn(0);
        when(mapper.lockDimension(42L, "checkout.duration", "region")).thenReturn(scope(0, new Date()));
        when(mapper.deleteExpiredValues(eq(42L), eq("checkout.duration"), eq("region"), any(Date.class))).thenReturn(0);
        when(mapper.insertValueIfAbsent(eq(42L), eq("checkout.duration"), eq("region"), any(byte[].class))).thenReturn(1);

        MonitorEventEnvelope first = metric("checkout.duration", 42.5, Map.of("region", "cn"));
        first.setPageUrl("https://shop.example/orders/123");
        MonitorEventEnvelope second = metric("checkout.duration", 99.0, Map.of("region", "cn"));
        second.setPageUrl("https://shop.example/orders/456");

        service.reserve(42L, List.of(first, second));

        verify(mapper).ensureDimension(42L, "checkout.duration", "region");
        verify(mapper, times(1)).insertValueIfAbsent(eq(42L), eq("checkout.duration"), eq("region"), any(byte[].class));
        verify(mapper, never()).ensureDimension(42L, "checkout.duration", "value");
        verify(mapper, never()).ensureDimension(42L, "checkout.duration", "pageUrl");
    }

    @Test
    void rejectsThe101stDistinctDimensionValue() {
        when(mapper.ensureMetric(42L, "checkout.duration")).thenReturn(0);
        when(mapper.ensureDimension(42L, "checkout.duration", "region")).thenReturn(0);
        when(mapper.lockDimension(42L, "checkout.duration", "region"))
                .thenReturn(scope(MonitorMetricCardinalityService.MAX_VALUES_PER_DIMENSION, new Date()));
        when(mapper.deleteExpiredValues(eq(42L), eq("checkout.duration"), eq("region"), any(Date.class))).thenReturn(0);
        when(mapper.insertValueIfAbsent(eq(42L), eq("checkout.duration"), eq("region"), any(byte[].class))).thenReturn(1);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.reserve(42L, List.of(metric("checkout.duration", 42.5, Map.of("region", "region-101")))));

        assertEquals(429, exception.getStatusCode().value());
        verify(mapper, never()).adjustDistinctValueCount(42L, "checkout.duration", "region", 1);
        verify(mapper, never()).touchDimension(42L, "checkout.duration", "region");
    }

    @Test
    void existingValuesRemainAllowedAtTheCardinalityLimit() {
        when(mapper.ensureMetric(42L, "checkout.duration")).thenReturn(0);
        when(mapper.ensureDimension(42L, "checkout.duration", "region")).thenReturn(0);
        when(mapper.lockDimension(42L, "checkout.duration", "region"))
                .thenReturn(scope(MonitorMetricCardinalityService.MAX_VALUES_PER_DIMENSION, new Date()));
        when(mapper.deleteExpiredValues(eq(42L), eq("checkout.duration"), eq("region"), any(Date.class))).thenReturn(0);
        when(mapper.insertValueIfAbsent(eq(42L), eq("checkout.duration"), eq("region"), any(byte[].class))).thenReturn(0);

        service.reserve(42L, List.of(metric("checkout.duration", 42.5, Map.of("region", "cn"))));

        verify(mapper).touchValue(eq(42L), eq("checkout.duration"), eq("region"), any(byte[].class));
        verify(mapper).touchDimension(42L, "checkout.duration", "region");
    }

    @Test
    void valuesOlderThanThe90DayWindowFreeCapacity() {
        when(mapper.ensureMetric(42L, "checkout.duration")).thenReturn(0);
        when(mapper.ensureDimension(42L, "checkout.duration", "region")).thenReturn(0);
        when(mapper.lockDimension(42L, "checkout.duration", "region"))
                .thenReturn(scope(MonitorMetricCardinalityService.MAX_VALUES_PER_DIMENSION, new Date()));
        when(mapper.deleteExpiredValues(eq(42L), eq("checkout.duration"), eq("region"), any(Date.class))).thenReturn(100);
        when(mapper.insertValueIfAbsent(eq(42L), eq("checkout.duration"), eq("region"), any(byte[].class))).thenReturn(1);

        service.reserve(42L, List.of(metric("checkout.duration", 42.5, Map.of("region", "cn-new"))));

        verify(mapper).adjustDistinctValueCount(42L, "checkout.duration", "region", -100);
        verify(mapper).adjustDistinctValueCount(42L, "checkout.duration", "region", 1);
    }

    @Test
    void rejectsThe21stActiveDimensionKeyForAMetric() {
        when(mapper.ensureMetric(42L, "checkout.duration")).thenReturn(0);
        when(mapper.ensureDimension(42L, "checkout.duration", "browser")).thenReturn(1);
        when(mapper.lockMetric(42L, "checkout.duration")).thenReturn(MonitorMetricCardinalityService.MAX_DIMENSION_KEYS_PER_METRIC);
        when(mapper.deleteExpiredDimensionsForMetric(eq(42L), eq("checkout.duration"), any(Date.class))).thenReturn(0);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.reserve(42L, List.of(metric("checkout.duration", 42.5, Map.of("browser", "chrome")))));

        assertEquals(429, exception.getStatusCode().value());
        verify(mapper, never()).adjustDimensionKeyCount(42L, "checkout.duration", 1);
    }

    @Test
    void countsMetricNamesWithoutTagsAndRejectsThe201stActiveName() {
        when(mapper.lockProject(42L)).thenReturn(MonitorMetricCardinalityService.MAX_METRICS_PER_PROJECT);
        when(mapper.ensureMetric(42L, "checkout.duration")).thenReturn(1);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.reserve(42L, List.of(metric("checkout.duration", 42.5, Map.of()))));

        assertEquals(429, exception.getStatusCode().value());
        verify(mapper, never()).ensureDimension(eq(42L), eq("checkout.duration"), anyString());
        verify(mapper, never()).touchMetric(42L, "checkout.duration");
    }

    @Test
    void expiredMetricNamesFreeProjectCapacityForUntaggedMetrics() {
        when(mapper.lockProject(42L)).thenReturn(MonitorMetricCardinalityService.MAX_METRICS_PER_PROJECT);
        when(mapper.deleteExpiredMetricsForProject(eq(42L), any(Date.class))).thenReturn(1);
        when(mapper.ensureMetric(42L, "checkout.duration")).thenReturn(1);

        service.reserve(42L, List.of(metric("checkout.duration", 42.5, Map.of())));

        verify(mapper).adjustProjectMetricCount(42L, -1);
        verify(mapper).adjustProjectMetricCount(42L, 1);
        verify(mapper).touchMetric(42L, "checkout.duration");
        verify(mapper, never()).ensureDimension(eq(42L), eq("checkout.duration"), anyString());
    }

    @Test
    void locksTheProjectCounterBeforeRegisteringMetricNames() {
        when(mapper.ensureMetric(42L, "checkout.duration")).thenReturn(1);

        service.reserve(42L, List.of(metric("checkout.duration", 42.5, Map.of())));

        org.mockito.InOrder order = inOrder(mapper);
        order.verify(mapper).ensureProject(42L);
        order.verify(mapper).lockProject(42L);
        order.verify(mapper).deleteExpiredMetricsForProject(eq(42L), any(Date.class));
        order.verify(mapper).ensureMetric(42L, "checkout.duration");
        order.verify(mapper).adjustProjectMetricCount(42L, 1);
    }

    private MonitorMetricCardinalityDimension scope(int count, Date lastSeen) {
        MonitorMetricCardinalityDimension scope = new MonitorMetricCardinalityDimension();
        scope.setDistinctValueCount(count);
        scope.setLastSeen(lastSeen);
        return scope;
    }

    private MonitorEventEnvelope metric(String name, Number value, Map<String, ?> tags) {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("event-1");
        event.setProjectId("project-key");
        event.setEventType(MonitorEventType.METRIC);
        event.setTimestamp(System.currentTimeMillis());
        event.setTraceId("trace-id-not-a-dimension");
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("metricType", "gauge");
        data.put("value", value);
        data.put("unit", "ms");
        data.put("tags", tags);
        event.setData(data);
        return event;
    }
}
