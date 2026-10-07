package com.macro.mall.tiny.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("clickHouseHealthIndicator")
@Profile("prod")
public class ClickHouseHealthIndicator implements HealthIndicator {

    private final JdbcTemplate clickHouseJdbcTemplate;

    public ClickHouseHealthIndicator(
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouseJdbcTemplate) {
        this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            Integer result = clickHouseJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(result) ? Health.up().build() : Health.down().build();
        } catch (RuntimeException e) {
            return Health.down().build();
        }
    }
}
