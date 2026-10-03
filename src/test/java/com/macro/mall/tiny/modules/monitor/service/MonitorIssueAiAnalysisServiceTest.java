package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorReplay;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorLogSearchResult;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapStackFrame;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MonitorIssueAiAnalysisServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsOnlySanitizedProjectScopedEvidenceAndParsesStructuredResponse() throws Exception {
        MonitorQueryService queryService = mock(MonitorQueryService.class);
        MonitorSourceMapService sourceMapService = mock(MonitorSourceMapService.class);
        MonitorLogQueryService logQueryService = mock(MonitorLogQueryService.class);
        MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        project.setProjectKey("demo-web");
        MonitorIssue issue = new MonitorIssue();
        issue.setId(7L);
        issue.setProjectId(42L);
        issue.setTitle("TypeError reported by person@example.com");
        issue.setEventCount(3L);
        issue.setAffectedUsers(2L);
        issue.setLatestRelease("release-1");
        when(queryService.issue(42L, 7L)).thenReturn(issue);
        when(queryService.issueEvents(project, issue, 5)).thenReturn(List.of(Map.of(
                "event_time", "2026-10-02 12:00:00",
                "environment", "production",
                "release", "release-1",
                "user_id", "private-user-123",
                "session_id", "private-session-456",
                "page_url", "https://private.example/page?token=private-token",
                "trace_id", "0123456789abcdef0123456789abcdef",
                "payload", "{\"data\":{\"name\":\"TypeError\",\"message\":\"Cannot read person@example.com Bearer abc.def\",\"stack\":\"at run (https://app.example/main.js?api_key=private-key)\",\"breadcrumbs\":[{\"type\":\"click\",\"data\":{\"value\":\"private-button-value\"}}]}}"
        )));
        when(sourceMapService.resolveStackForAdmin(eq(project), eq("release-1"), eq("production"),
                anyString(), isNull(), isNull(), isNull())).thenReturn(List.of(new SourceMapStackFrame(
                0, "run", "at run (...) ", "https://app.example/main.js", 10, 4,
                true, "/src/App.vue", 12, 3, "run", "const email = 'person@example.com'; const api_key = 'private-source-key';"
        )));
        Map<String, Object> linked = Map.of(
                "signal_type", "performance",
                "event_time", "2026-10-02 12:00:01",
                "title", "LCP",
                "release", "release-1",
                "environment", "production",
                "payload", "{\"data\":{\"metric\":\"LCP\",\"value\":1800,\"unit\":\"ms\",\"tags\":{\"email\":\"person@example.com\"}}}"
        );
        when(queryService.explore(eq(project), eq(168), isNull(), isNull(), isNull(),
                eq("0123456789abcdef0123456789abcdef"), isNull(), eq(20), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(linked), false, 20));
        when(logQueryService.search(eq(project), eq("0123456789abcdef0123456789abcdef"), eq(168), eq(20)))
                .thenReturn(new MonitorLogSearchResult("0123456789abcdef0123456789abcdef", List.of(
                        new MonitorLogSearchResult.Entry("2026-10-02T12:00:01Z",
                                "request failed for person@example.com Bearer log-secret", Map.of(), Map.of())
                )));
        when(replayMapper.selectList(any())).thenReturn(List.of(replay(8)));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "summary", "A property access failed for person@example.com with api_key=response_secret.",
                "severity", "high",
                "confidence", 0.87,
                "possibleCauses", List.of("An expected object was null."),
                "recommendations", List.of("Check the access guard at the reported frame."),
                "evidence", List.of("The event is a TypeError and includes a browser stack.")
        ));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", outputText))))
        ));
        server.expect(requestTo("https://ai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-test-key"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(jsonPath("$.text.format.schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.input[0].content[0].text").value(notNullValue()))
                .andExpect(content().string(allOf(
                        containsString("[REDACTED_EMAIL]"),
                        containsString("Bearer [REDACTED]"),
                        containsString("api_key=[REDACTED]"),
                        containsString("breadcrumbTypes"),
                        containsString("sourceFrames"),
                        containsString("sourceContext"),
                        containsString("linkedTelemetry"),
                        containsString("relatedLogs"),
                        containsString("replayContext"),
                        not(containsString("person@example.com")),
                        not(containsString("private-user-123")),
                        not(containsString("private-session-456")),
                        not(containsString("private.example")),
                        not(containsString("private-trace-789")),
                        not(containsString("private-button-value")),
                        not(containsString("private-source-key")),
                        not(containsString("log-secret")),
                        not(containsString("person@example.com"))
                )))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        MonitorIssueAiAnalysisService service = new MonitorIssueAiAnalysisService(
                queryService, sourceMapService, logQueryService, replayMapper,
                objectMapper, builder.build(), true, "secret-test-key", "gpt-6-astra");
        var analysis = service.analyze(project, 7L);

        assertEquals("A property access failed for [REDACTED_EMAIL] with api_key=[REDACTED]", analysis.summary());
        assertEquals("high", analysis.severity());
        assertEquals(0.87, analysis.confidence());
        assertEquals(1, analysis.possibleCauses().size());
        verify(queryService).issue(42L, 7L);
        verify(queryService).issueEvents(project, issue, 5);
        verify(queryService).explore(eq(project), eq(168), isNull(), isNull(), isNull(),
                eq("0123456789abcdef0123456789abcdef"), isNull(), eq(20), eq(0));
        verify(logQueryService).search(project, "0123456789abcdef0123456789abcdef", 168, 20);
        verify(replayMapper).selectList(any());
        server.verify();
    }

    @Test
    void disabledFeatureDoesNotReadIssueOrCallProvider() {
        MonitorQueryService queryService = mock(MonitorQueryService.class);
        MonitorSourceMapService sourceMapService = mock(MonitorSourceMapService.class);
        MonitorLogQueryService logQueryService = mock(MonitorLogQueryService.class);
        MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);
        RestClient client = RestClient.builder().baseUrl("https://ai.test/v1").build();
        MonitorIssueAiAnalysisService service = new MonitorIssueAiAnalysisService(
                queryService, sourceMapService, logQueryService, replayMapper,
                objectMapper, client, false, "", "gpt-6-astra");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.analyze(new MonitorProject(), 1L));

        assertEquals(503, error.getStatusCode().value());
        verifyNoInteractions(queryService);
    }

    private MonitorReplay replay(int eventCount) {
        MonitorReplay replay = new MonitorReplay();
        replay.setProjectId(42L);
        replay.setSessionId("private-session-456");
        replay.setReleaseVersion("release-1");
        replay.setEventCount(eventCount);
        replay.setStartTime(java.util.Date.from(java.time.Instant.parse("2026-10-02T11:59:00Z")));
        replay.setEndTime(java.util.Date.from(java.time.Instant.parse("2026-10-02T12:01:00Z")));
        return replay;
    }
}
