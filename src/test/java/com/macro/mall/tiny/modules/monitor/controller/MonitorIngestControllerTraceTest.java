package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventBatchRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.service.MonitorCronService;
import com.macro.mall.tiny.modules.monitor.service.MonitorIngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MonitorIngestControllerTraceTest {

    private final MonitorIngestService ingestService = mock(MonitorIngestService.class);
    private final MonitorIngestController controller = new MonitorIngestController(
            ingestService, mock(MonitorCronService.class));

    @AfterEach
    void clearRequestTrace() {
        MDC.clear();
    }

    @Test
    void batchIngestDoesNotCopyTheUploadRequestTraceIntoEventTraceIds() {
        MDC.put("traceId", "11111111111111111111111111111111");
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        MonitorEventBatchRequest request = new MonitorEventBatchRequest();
        request.setEvents(List.of(event));

        controller.ingestBatch("ingest-key", request);

        assertNull(event.getTraceId());
        verify(ingestService).ingestBatch("ingest-key", List.of(event));
    }

    @Test
    void singleIngestDoesNotCopyTheUploadRequestTraceIntoTheEventTraceId() {
        MDC.put("traceId", "22222222222222222222222222222222");
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("event-1");

        controller.ingest("ingest-key", event);

        assertNull(event.getTraceId());
        verify(ingestService).ingest("ingest-key", event);
    }
}
