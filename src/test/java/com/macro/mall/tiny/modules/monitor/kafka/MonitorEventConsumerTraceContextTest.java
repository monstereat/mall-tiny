package com.macro.mall.tiny.modules.monitor.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.repository.ClickHouseEventRepository;
import com.macro.mall.tiny.modules.monitor.service.*;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MonitorEventConsumerTraceContextTest {

    @Test
    void shouldUseOneParentAndLinkOtherTraceFromKafkaBatch() {
        Tracer tracer = mock(Tracer.class);
        Span.Builder spanBuilder = mock(Span.Builder.class);
        Span batchSpan = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.traceContextBuilder()).thenAnswer(invocation -> new TestTraceContextBuilder());
        when(tracer.spanBuilder()).thenReturn(spanBuilder);
        when(spanBuilder.name(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.kind(any())).thenReturn(spanBuilder);
        when(spanBuilder.tag(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.tag(anyString(), anyLong())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(batchSpan);
        when(tracer.withSpan(batchSpan)).thenReturn(scope);

        MonitorEventDeduplicator deduplicator = mock(MonitorEventDeduplicator.class);
        when(deduplicator.reserve(anyString())).thenReturn(false);
        MonitorEventConsumer consumer = new MonitorEventConsumer(
                new ObjectMapper(),
                mock(ClickHouseEventRepository.class),
                mock(ErrorFingerprintService.class),
                mock(MonitorProjectService.class),
                mock(MonitorIssueService.class),
                deduplicator,
                mock(MonitorReplayService.class),
                mock(MonitorAlertEngine.class),
                tracer
        );

        String payload = "{\"eventId\":\"deduplicated\",\"projectId\":\"demo\","
                + "\"eventType\":\"BEHAVIOR\",\"timestamp\":1}";
        consumer.consumeBehavior(List.of(
                record("11111111111111111111111111111111", "aaaaaaaaaaaaaaaa", payload),
                record("22222222222222222222222222222222", "bbbbbbbbbbbbbbbb", payload)
        ));

        verify(spanBuilder).setParent(argThat(context ->
                context.traceId().equals("11111111111111111111111111111111")
                        && context.spanId().equals("aaaaaaaaaaaaaaaa")));
        verify(spanBuilder, never()).setNoParent();
        verify(spanBuilder, times(1)).setParent(any());
        verify(spanBuilder).addLink(argThat(link ->
                link.getTraceContext().traceId().equals("22222222222222222222222222222222")
                        && link.getTraceContext().spanId().equals("bbbbbbbbbbbbbbbb")));
        verify(spanBuilder, times(1)).addLink(any(Link.class));
        verify(batchSpan).end();
    }

    private static ConsumerRecord<String, String> record(String traceId, String spanId, String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("monitor.behavior", 0, 0L, "key", payload);
        String traceparent = "00-" + traceId + "-" + spanId + "-01";
        record.headers().add(new RecordHeader("traceparent", traceparent.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private static class TestTraceContextBuilder implements TraceContext.Builder {
        private String traceId;
        private String spanId;
        private Boolean sampled;

        @Override
        public TraceContext.Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        @Override
        public TraceContext.Builder parentId(String parentId) {
            return this;
        }

        @Override
        public TraceContext.Builder spanId(String spanId) {
            this.spanId = spanId;
            return this;
        }

        @Override
        public TraceContext.Builder sampled(Boolean sampled) {
            this.sampled = sampled;
            return this;
        }

        @Override
        public TraceContext build() {
            return context(traceId, spanId, Boolean.TRUE.equals(sampled));
        }
    }

    private static TraceContext context(String traceId, String spanId, boolean sampled) {
        return new TraceContext() {
            @Override
            public String traceId() {
                return traceId;
            }

            @Override
            public String parentId() {
                return null;
            }

            @Override
            public String spanId() {
                return spanId;
            }

            @Override
            public Boolean sampled() {
                return sampled;
            }
        };
    }
}
