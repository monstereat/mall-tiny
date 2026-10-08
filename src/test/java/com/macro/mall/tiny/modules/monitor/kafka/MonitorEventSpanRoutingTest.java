package com.macro.mall.tiny.modules.monitor.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.repository.ClickHouseEventRepository;
import com.macro.mall.tiny.modules.monitor.service.*;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MonitorEventSpanRoutingTest {

    @Test
    void producerPublishesSpanToDedicatedKafkaTopic() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        MonitorEventProducer producer = new MonitorEventProducer(kafka, new ObjectMapper());
        ReflectionTestUtils.setField(producer, "spanTopic", "monitor-span-v1");
        MonitorEventEnvelope event = spanEvent();

        producer.publish(event);

        verify(kafka).send(eq("monitor-span-v1"), eq("project-a"), anyString());
    }

    @Test
    void consumerRequiresSpanTopicTypeAndPersistsSpanEvent() {
        ObjectMapper objectMapper = new ObjectMapper();
        ClickHouseEventRepository repository = mock(ClickHouseEventRepository.class);
        MonitorProject project = new MonitorProject();
        MonitorEventDeduplicator deduplicator = mock(MonitorEventDeduplicator.class);
        when(deduplicator.reserve("span-1")).thenReturn(true);
        Tracer tracer = mock(Tracer.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span batchSpan = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.kind(any())).thenReturn(builder);
        when(builder.tag(anyString(), anyString())).thenReturn(builder);
        when(builder.tag(anyString(), anyLong())).thenReturn(builder);
        when(builder.setNoParent()).thenReturn(builder);
        when(builder.start()).thenReturn(batchSpan);
        when(tracer.withSpan(batchSpan)).thenReturn(scope);
        MonitorProjectService projects = projectService();
        when(projects.getActiveProject("project-a")).thenReturn(project);
        MonitorEventConsumer consumer = new MonitorEventConsumer(
                objectMapper, repository, mock(ErrorFingerprintService.class), projects,
                mock(MonitorIssueService.class), deduplicator, mock(MonitorReplayService.class),
                mock(MonitorAlertEngine.class), tracer);
        String payload = "{\"eventId\":\"span-1\",\"projectId\":\"project-a\",\"eventType\":\"SPAN\","
                + "\"timestamp\":1700000000000,\"traceId\":\"0123456789abcdef0123456789abcdef\","
                + "\"data\":{\"spanId\":\"0123456789abcdef\",\"parentSpanId\":\"fedcba9876543210\","
                + "\"op\":\"http.client\",\"description\":\"GET /checkout\",\"startTime\":1700000000000,"
                + "\"durationMs\":25.5,\"status\":\"error\",\"statusCode\":503}}";

        consumer.consumeSpan(List.of(new ConsumerRecord<>("monitor-span-v1", 0, 0L, "project-a", payload)));

        org.mockito.ArgumentCaptor<List<ClickHouseEventRepository.StoredEvent>> rows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveBatch(rows.capture());
        org.junit.jupiter.api.Assertions.assertEquals(MonitorEventType.SPAN,
                rows.getValue().get(0).event().getEventType());
        verify(batchSpan).end();
    }

    private static MonitorProjectService projectService() {
        return mock(MonitorProjectService.class);
    }

    private static MonitorEventEnvelope spanEvent() {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("span-1");
        event.setProjectId("project-a");
        event.setEventType(MonitorEventType.SPAN);
        event.setTimestamp(1_700_000_000_000L);
        event.setTraceId("0123456789abcdef0123456789abcdef");
        event.setData(Map.of("spanId", "0123456789abcdef", "op", "http.client", "description", "GET /checkout",
                "startTime", 1_700_000_000_000L, "durationMs", 25.5, "status", "ok"));
        return event;
    }
}
