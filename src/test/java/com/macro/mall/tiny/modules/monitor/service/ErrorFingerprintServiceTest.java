package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorFingerprintServiceTest {

    private final ErrorFingerprintService service = new ErrorFingerprintService();

    @Test
    void shouldIgnoreHashedAssetAndDynamicIds() {
        MonitorEventEnvelope first = error(
                "TypeError",
                "order 12345678 failed",
                "https://cdn.example.com/app.a1b2c3d4.js?v=1",
                "10",
                "at detail (app.a1b2c3d4.js:10:20)"
        );
        MonitorEventEnvelope second = error(
                "TypeError",
                "order 87654321 failed",
                "https://cdn.example.com/app.ffffeeee.js?v=2",
                "10",
                "at detail (app.ffffeeee.js:10:20)"
        );

        assertEquals(service.generate(first), service.generate(second));
    }

    private MonitorEventEnvelope error(String name, String message, String file, String line, String stack) {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("evt-1");
        event.setProjectId("demo");
        event.setEventType(MonitorEventType.ERROR);
        event.setTimestamp(System.currentTimeMillis());
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("message", message);
        data.put("file", file);
        data.put("line", line);
        data.put("stack", stack);
        event.setData(data);
        return event;
    }
}
