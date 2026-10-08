package com.macro.mall.tiny.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClickHouseHealthIndicatorTest {

    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final ClickHouseHealthIndicator indicator = new ClickHouseHealthIndicator(clickHouse);

    @Test
    void reportsUpWhenSelectOneSucceeds() {
        when(clickHouse.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void reportsDownWhenSelectOneReturnsUnexpectedResult() {
        when(clickHouse.queryForObject("SELECT 1", Integer.class)).thenReturn(0);

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void reportsDownWhenClickHouseIsUnavailable() {
        when(clickHouse.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new IllegalStateException("unavailable"));

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void reportsDownWhenClickHouseQueryTimesOut() {
        when(clickHouse.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new QueryTimeoutException("timeout"));

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }
}
