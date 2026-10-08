package com.macro.mall.tiny.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthReadinessConfigurationTest {

    @Test
    void productionReadinessIncludesStorageContributorsAndPropagatesTheirStatus() {
        AtomicReference<Status> clickHouseStatus = new AtomicReference<>(Status.UP);

        context("prod", clickHouseStatus).run(application -> {
            assertTrue(application.isRunning());
            HealthEndpoint endpoint = application.getBean(HealthEndpoint.class);
            CompositeHealth readiness = (CompositeHealth) endpoint.healthForPath("readiness");

            assertEquals(Set.of("readinessState", "clickHouse", "minioReplayBucket"),
                    readiness.getComponents().keySet());
            assertEquals(Status.UP, readiness.getStatus());
            assertEquals("never", application.getEnvironment()
                    .getProperty("management.endpoint.health.show-details"));
            assertNull(readiness.getDetails());

            clickHouseStatus.set(Status.DOWN);
            assertEquals(Status.DOWN, endpoint.healthForPath("readiness").getStatus());
        });
    }

    @Test
    void defaultProfileDoesNotDeclareStorageHealthInReadiness() {
        assertNull(load("application.yml")
                .getProperty("management.endpoint.health.group.readiness.include[0]"));
        assertEquals("never", load("application.yml")
                .getProperty("management.endpoint.health.show-details"));
    }

    private ApplicationContextRunner context(String profile, AtomicReference<Status> clickHouseStatus) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(HealthEndpointAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=" + profile)
                .withBean("readinessState", HealthIndicator.class,
                        () -> () -> Health.up().build())
                .withBean("clickHouseHealthIndicator", HealthIndicator.class,
                        () -> () -> Health.status(clickHouseStatus.get()).build())
                .withBean("minioReplayBucketHealthIndicator", HealthIndicator.class,
                        () -> () -> Health.up().build())
                .withBean("unrelatedHealthIndicator", HealthIndicator.class,
                        () -> () -> Health.down().build());
    }

    private Properties load(String resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        return factory.getObject();
    }
}
