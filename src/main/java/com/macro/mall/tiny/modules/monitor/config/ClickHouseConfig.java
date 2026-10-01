package com.macro.mall.tiny.modules.monitor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class ClickHouseConfig {

    /**
     * Intentionally exposes only JdbcTemplate instead of a second DataSource bean.
     * This keeps Spring Boot's primary MySQL DataSource auto-configuration intact.
     */
    @Bean(name = "clickHouseJdbcTemplate")
    public JdbcTemplate clickHouseJdbcTemplate(
            @Value("${monitor.clickhouse.url}") String url,
            @Value("${monitor.clickhouse.username}") String username,
            @Value("${monitor.clickhouse.password}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return new JdbcTemplate(dataSource);
    }
}
