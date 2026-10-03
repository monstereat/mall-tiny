package com.macro.mall.tiny.modules.monitor.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ClickHouseEventRepositorySpanTest {

    @Test
    void storesSpanInDedicatedEventTableUsingSharedColumns() {
        JdbcTemplate clickHouse = mock(JdbcTemplate.class);
        ClickHouseEventRepository repository = new ClickHouseEventRepository(clickHouse, new ObjectMapper());
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("span-1");
        event.setProjectId("project-a");
        event.setEventType(MonitorEventType.SPAN);
        event.setTimestamp(1_700_000_000_000L);
        event.setTraceId("0123456789abcdef0123456789abcdef");
        event.setData(Map.of("spanId", "0123456789abcdef", "startTime", 1_700_000_000_000L,
                "durationMs", 10, "op", "http.client", "description", "GET /", "status", "ok"));

        repository.saveBatch(List.of(new ClickHouseEventRepository.StoredEvent(event, "")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(clickHouse).batchUpdate(sql.capture(), anyList(), anyInt(), any(ParameterizedPreparedStatementSetter.class));
        assertTrue(sql.getValue().contains("INSERT INTO monitor.span_event"));
        assertTrue(sql.getValue().contains("trace_id, fingerprint, payload"));
    }
}
