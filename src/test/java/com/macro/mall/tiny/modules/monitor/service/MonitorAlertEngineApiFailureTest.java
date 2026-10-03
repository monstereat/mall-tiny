package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorAlertEngineApiFailureTest {

    @Test
    void countsHttpErrorsAndNetworkFailuresFromApiBehaviorEvents() {
        assertTrue(isApiFailure(0));
        assertTrue(isApiFailure(400));
        assertTrue(isApiFailure(503));
        assertFalse(isApiFailure(200));
        assertFalse(isApiFailure(399));
    }

    @Test
    void ignoresNonApiBehaviorAndOtherEventTypes() {
        MonitorEventEnvelope pageEvent = event(MonitorEventType.BEHAVIOR, Map.of("category", "click", "status", 500));
        MonitorEventEnvelope errorEvent = event(MonitorEventType.ERROR, Map.of("category", "api", "status", 500));
        MonitorEventEnvelope missingStatus = event(MonitorEventType.BEHAVIOR, Map.of("category", "api"));

        assertFalse(MonitorAlertEngine.isApiFailure(pageEvent));
        assertFalse(MonitorAlertEngine.isApiFailure(errorEvent));
        assertFalse(MonitorAlertEngine.isApiFailure(missingStatus));
    }

    private boolean isApiFailure(int status) {
        return MonitorAlertEngine.isApiFailure(event(MonitorEventType.BEHAVIOR,
                Map.of("category", "api", "status", status)));
    }

    private MonitorEventEnvelope event(MonitorEventType type, Map<String, Object> data) {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventType(type);
        event.setData(data);
        return event;
    }
}
