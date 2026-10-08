package com.macro.mall.tiny.config;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(prefix = "monitor.logging", name = "endpoint")
public class MonitorOtelLoggingConfiguration {

    @Bean(destroyMethod = "shutdown")
    SdkLoggerProvider monitorLoggerProvider(@Value("${monitor.logging.endpoint}") String endpoint) {
        var resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), "observability-platform")
        ));
        var exporter = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(Duration.ofSeconds(5))
                .build();
        return SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter).build())
                .build();
    }

    @Bean
    SmartInitializingSingleton installMonitorLogAppender(SdkLoggerProvider loggerProvider) {
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setLoggerProvider(loggerProvider)
                .build();
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
