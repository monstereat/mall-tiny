package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.kafka.MonitorEventProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MonitorIngestService {

    private final MonitorProjectService projectService;
    private final MonitorRateLimiter rateLimiter;
    private final MonitorEventProducer producer;

    public void ingest(String ingestKey, MonitorEventEnvelope event) {
        projectService.validateIngestKey(event.getProjectId(), ingestKey);
        acquire(event.getProjectId(), 1);
        producer.publish(event);
    }

    public int ingestBatch(String ingestKey, List<MonitorEventEnvelope> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        String projectId = events.get(0).getProjectId();
        projectService.validateIngestKey(projectId, ingestKey);

        boolean sameProject = events.stream().allMatch(event -> projectId.equals(event.getProjectId()));
        if (!sameProject) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "one batch can contain only one project");
        }

        acquire(projectId, events.size());
        events.forEach(producer::publish);
        return events.size();
    }

    private void acquire(String projectId, long permits) {
        if (!rateLimiter.tryAcquire(projectId, permits)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "monitor ingest rate limit exceeded");
        }
    }
}
