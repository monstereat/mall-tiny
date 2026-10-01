package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.kafka.MonitorEventProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MonitorIngestService {

    private final MonitorRateLimiter rateLimiter;
    private final MonitorEventProducer producer;

    public void ingest(MonitorEventEnvelope event) {
        if (!rateLimiter.tryAcquire(event.getProjectId())) {
            throw new IllegalStateException("monitor ingest rate limit exceeded");
        }
        producer.publish(event);
    }
}
