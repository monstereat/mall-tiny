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
        project.setCustomSensitiveFields("employeeNo,customer_ref,trace_id");
        MonitorIssue issue = new MonitorIssue();
        issue.setId(7L);
        issue.setProjectId(42L);
        issue.setTitle("TypeError employeeNo=private-issue-value reported by person@example.com with card 4111111111111111 phone 13800138000 id 11010519491231002X");
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
                "payload", "{\"data\":{\"name\":\"TypeError\",\"message\":\"Cannot read person@example.com Bearer abc.def card 4111111111111111 invalid 4111111111111112 client 192.0.2.15 phone 13800138000 id 11010519491231002X employee_no=private-event-value\",\"stack\":\"at run (https://app.example/main.js?api_key=private-key)\",\"breadcrumbs\":[{\"type\":\"click\",\"data\":{\"value\":\"private-button-value\"}}]}}"
        )));
        when(sourceMapService.resolveStackForAdmin(eq(project), eq("release-1"), eq("production"),
                anyString(), isNull(), isNull(), isNull())).thenReturn(List.of(new SourceMapStackFrame(
                0, "run", "at run (...) ", "https://app.example/main.js", 10, 4,
                true, "/src/App.vue", 12, 3, "run", "const email = 'person@example.com'; const api_key = 'private-source-key'; const customer_ref = 'private-source-value'; // 4111111111111111; 203.0.113.8"
        )));
        Map<String, Object> linked = Map.of(
                "signal_type", "performance",
                "event_time", "2026-10-02 12:00:01",
                "title", "LCP",
                "release", "release-1",
                "environment", "production",
                "payload", "{\"data\":{\"metric\":\"LCP\",\"value\":1800,\"unit\":\"ms\",\"tags\":{\"email\":\"person@example.com\"}}}"
        );
        Map<String, Object> metricWithCardNumber = Map.of(
                "signal_type", "metric",
                "event_time", "2026-10-02 12:00:02",
                "title", "customer metric",
                "release", "release-1",
                "environment", "production",
                "payload", "{\"data\":{\"name\":\"billing.card\",\"metricType\":\"gauge\",\"value\":4111111111111111,\"unit\":\"count\"}}"
        );
        when(queryService.explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq("0123456789abcdef0123456789abcdef"), isNull(), eq(21), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(linked, metricWithCardNumber), false, 20));
        when(logQueryService.search(eq(project), eq("0123456789abcdef0123456789abcdef"), eq(168), eq(21)))
                .thenReturn(new MonitorLogSearchResult("0123456789abcdef0123456789abcdef", List.of(
                        new MonitorLogSearchResult.Entry("2026-10-02T12:00:01Z",
                                "request failed for person@example.com Bearer log-secret card 4111111111111111 customer-ref: private-log-value from 2001:db8::1", Map.of(), Map.of())
                )));
        when(replayMapper.selectList(any())).thenReturn(List.of(replay(8)));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "summary", "A property access failed for person@example.com with api_key=response_secret. Card 4111111111111111 client 192.0.2.99 phone 13800138000 id 11010519491231002X employeeNo=response-private-value",
                "severity", "high",
                "confidence", 0.87,
                "possibleCauses", List.of("An expected object was null."),
                "recommendations", List.of("Check the access guard at the reported frame with card 4111111111111111 customer_ref: response-recommendation-value."),
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
                        containsString("[REDACTED_CARD]"),
                        containsString("[REDACTED_IP]"),
                        containsString("invalid 4111111111111112"),
                        containsString("Bearer [REDACTED]"),
                        containsString("api_key=[REDACTED]"),
                        containsString("breadcrumbTypes"),
                        containsString("sourceFrames"),
                        containsString("sourceContext"),
                        containsString("[REDACTED_CUSTOM]"),
                        containsString("linkedTelemetry"),
                        containsString("relatedLogs"),
                        containsString("replayContext"),
                        not(containsString("person@example.com")),
                        not(containsString("4111111111111111")),
                        not(containsString("13800138000")),
                        not(containsString("11010519491231002X")),
                        not(containsString("192.0.2.15")),
                        not(containsString("203.0.113.8")),
                        not(containsString("2001:db8::1")),
                        not(containsString("private-user-123")),
                        not(containsString("private-session-456")),
                        not(containsString("private.example")),
                        not(containsString("private-trace-789")),
                        not(containsString("private-button-value")),
                        not(containsString("private-source-key")),
                        not(containsString("private-issue-value")),
                        not(containsString("private-event-value")),
                        not(containsString("private-source-value")),
                        not(containsString("private-log-value")),
                        not(containsString("0123456789abcdef0123456789abcdef")),
                        not(containsString("log-secret")),
                        not(containsString("person@example.com"))
                )))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        MonitorIssueAiAnalysisService service = new MonitorIssueAiAnalysisService(
                queryService, sourceMapService, logQueryService, replayMapper,
                objectMapper, builder.build(), true, "secret-test-key", "gpt-6-astra");
        var analysis = service.analyze(project, 7L);

        assertEquals("A property access failed for [REDACTED_EMAIL] with api_key=[REDACTED] Card [REDACTED_CARD] client [REDACTED_IP] phone [REDACTED_PHONE] id [REDACTED_CHINESE_ID] employeeNo=[REDACTED_CUSTOM]",
                analysis.summary());
        assertEquals("high", analysis.severity());
        assertEquals(0.87, analysis.confidence());
        assertEquals(1, analysis.possibleCauses().size());
        assertFalse(analysis.recommendations().get(0).contains("response-recommendation-value"));
        verify(queryService).issue(42L, 7L);
        verify(queryService).issueEvents(project, issue, 5);
        verify(queryService).explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq("0123456789abcdef0123456789abcdef"), isNull(), eq(21), eq(0));
        verify(logQueryService).search(project, "0123456789abcdef0123456789abcdef", 168, 21);
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

    @Test
    void reportsMissingCorrelationContextAsUnknownInsteadOfNoMatches() throws Exception {
        MonitorQueryService queryService = mock(MonitorQueryService.class);
        MonitorSourceMapService sourceMapService = mock(MonitorSourceMapService.class);
        MonitorLogQueryService logQueryService = mock(MonitorLogQueryService.class);
        MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        MonitorIssue issue = new MonitorIssue();
        issue.setId(10L);
        issue.setProjectId(42L);
        issue.setTitle("Issue without correlation context");
        issue.setEventCount(2L);
        issue.setAffectedUsers(1L);
        when(queryService.issue(42L, 10L)).thenReturn(issue);
        when(queryService.issueEvents(project, issue, 5)).thenReturn(List.of(
                issueEventWithoutCorrelationContext("2026-10-03 10:00:00"),
                issueEventWithoutCorrelationContext("2026-10-03 09:00:00")));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "summary", "The available events lack correlation context.",
                "severity", "medium",
                "confidence", 0.5,
                "possibleCauses", List.of("Additional identifiers may be needed."),
                "recommendations", List.of("Inspect an event with a trace and session identifier."),
                "evidence", List.of("Two recent issue events were found.")));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", outputText))))));
        server.expect(requestTo("https://ai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.input[0].content[0].text", allOf(
                        containsString("\"sourceFrames\":{\"status\":\"unavailable\",\"itemCount\":0,\"attemptedQueries\":0,\"failedQueries\":0,\"skippedQueries\":2"),
                        containsString("\"linkedTelemetry\":{\"status\":\"unavailable\",\"itemCount\":0,\"attemptedQueries\":0,\"failedQueries\":0,\"skippedQueries\":2"),
                        containsString("\"relatedLogs\":{\"status\":\"unavailable\",\"itemCount\":0,\"attemptedQueries\":0,\"failedQueries\":0,\"skippedQueries\":2"),
                        containsString("\"replayContext\":{\"status\":\"unavailable\",\"itemCount\":0,\"attemptedQueries\":0,\"failedQueries\":0,\"skippedQueries\":2"),
                        containsString("missing items are unknown"),
                        not(containsString("\"status\":\"no_matches\"")))))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        MonitorIssueAiAnalysisService service = new MonitorIssueAiAnalysisService(
                queryService, sourceMapService, logQueryService, replayMapper,
                objectMapper, builder.build(), true, "secret-test-key", "gpt-6-astra");
        var analysis = service.analyze(project, 10L);

        assertEquals("The available events lack correlation context.", analysis.summary());
        assertEquals(8, analysis.limitations().size());
        verify(queryService, never()).explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                anyString(), isNull(), eq(21), eq(0));
        verifyNoInteractions(sourceMapService, logQueryService, replayMapper);
        server.verify();
    }

    @Test
    void correlatesAllValidDistinctTracesFromRecentIssueEventsIncludingOlderTelemetry() throws Exception {
        MonitorQueryService queryService = mock(MonitorQueryService.class);
        MonitorSourceMapService sourceMapService = mock(MonitorSourceMapService.class);
        MonitorLogQueryService logQueryService = mock(MonitorLogQueryService.class);
        MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        project.setProjectKey("demo-web");
        MonitorIssue issue = new MonitorIssue();
        issue.setId(8L);
        issue.setProjectId(42L);
        issue.setTitle("Repeated TypeError");
        issue.setEventCount(5L);
        issue.setAffectedUsers(2L);
        when(queryService.issue(42L, 8L)).thenReturn(issue);
        String traceA = "0123456789abcdef0123456789abcdef";
        String traceB = "fedcba9876543210fedcba9876543210";
        when(queryService.issueEvents(project, issue, 5)).thenReturn(List.of(
                issueEvent("2026-10-03 10:00:00", traceA),
                issueEvent("2026-09-10 10:00:00", traceB),
                issueEvent("2026-09-09 10:00:00", traceA),
                issueEvent("2026-09-08 10:00:00", "not-a-trace"),
                issueEvent("2026-09-07 10:00:00", "00000000000000000000000000000000")));
        when(queryService.explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq(traceA), isNull(), eq(21), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(linkedEvent("linked-event-a", "trace A evidence")), false, 21));
        when(queryService.explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq(traceB), isNull(), eq(21), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(linkedEvent("linked-event-b", "trace B evidence")), false, 21));
        when(logQueryService.search(project, traceA, 168, 21)).thenReturn(new MonitorLogSearchResult(traceA,
                List.of(new MonitorLogSearchResult.Entry("2026-10-03T10:00:01Z", "log for trace A", Map.of(), Map.of()))));
        when(logQueryService.search(project, traceB, 168, 21)).thenReturn(new MonitorLogSearchResult(traceB,
                List.of(new MonitorLogSearchResult.Entry("2026-09-10T10:00:01Z", "log for trace B", Map.of(), Map.of()))));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "summary", "Two traces contain related diagnostic evidence.",
                "severity", "medium",
                "confidence", 0.75,
                "possibleCauses", List.of("A shared dependency may be failing."),
                "recommendations", List.of("Compare both trace paths."),
                "evidence", List.of("Recent issue events contain two distinct trace IDs.")));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", outputText))))
        ));
        server.expect(requestTo("https://ai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("trace A evidence"),
                        containsString("trace B evidence"),
                        containsString("log for trace A"),
                        containsString("log for trace B"),
                        containsString("2026-09-10 10:00:00"),
                        containsString("linkedTelemetryWindowHours"),
                        containsString("relatedLogsRetentionWindowHours"))))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        MonitorIssueAiAnalysisService service = new MonitorIssueAiAnalysisService(
                queryService, sourceMapService, logQueryService, replayMapper,
                objectMapper, builder.build(), true, "secret-test-key", "gpt-6-astra");
        var analysis = service.analyze(project, 8L);

        assertEquals("Two traces contain related diagnostic evidence.", analysis.summary());
        verify(queryService, times(1)).explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq(traceA), isNull(), eq(21), eq(0));
        verify(queryService, times(1)).explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq(traceB), isNull(), eq(21), eq(0));
        verify(logQueryService, times(1)).search(project, traceA, 168, 21);
        verify(logQueryService, times(1)).search(project, traceB, 168, 21);
        server.verify();
    }

    @Test
    void exposesEvidenceAvailabilityAndContinuesOtherTraceLookupsWithoutLeakingFailureDetails() throws Exception {
        MonitorQueryService queryService = mock(MonitorQueryService.class);
        MonitorSourceMapService sourceMapService = mock(MonitorSourceMapService.class);
        MonitorLogQueryService logQueryService = mock(MonitorLogQueryService.class);
        MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        project.setProjectKey("demo-web");
        MonitorIssue issue = new MonitorIssue();
        issue.setId(9L);
        issue.setProjectId(42L);
        issue.setTitle("Trace evidence status check");
        issue.setEventCount(3L);
        issue.setAffectedUsers(1L);
        when(queryService.issue(42L, 9L)).thenReturn(issue);
        String traceA = "0123456789abcdef0123456789abcdef";
        String traceB = "fedcba9876543210fedcba9876543210";
        String traceC = "abcdef0123456789abcdef0123456789";
        when(queryService.issueEvents(project, issue, 5)).thenReturn(List.of(
                issueEventWithStack("2026-10-03 10:00:00", traceA, "session-a"),
                issueEventWithStack("2026-10-03 09:00:00", traceB, "session-b"),
                issueEventWithStack("2026-10-03 08:00:00", traceC, "session-c")));
        when(sourceMapService.resolveStackForAdmin(eq(project), eq("release-2"), eq("production"),
                anyString(), isNull(), isNull(), isNull())).thenReturn(List.of());
        when(queryService.explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq(traceA), isNull(), eq(21), eq(0)))
                .thenThrow(new IllegalStateException("https://provider.invalid/private?api_key=must-not-leak"));
        when(queryService.explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq(traceB), isNull(), eq(21), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(linkedEvent("trace-b-event", "trace B evidence")), false, 21));
        when(queryService.explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq(traceC), isNull(), eq(21), eq(0)))
                .thenReturn(new MonitorExploreResult(List.of(), false, 21));
        when(logQueryService.search(project, traceA, 168, 21))
                .thenThrow(new IllegalStateException("log backend secret must-not-leak"));
        when(logQueryService.search(project, traceB, 168, 21))
                .thenReturn(new MonitorLogSearchResult(traceB, List.of()));
        when(logQueryService.search(project, traceC, 168, 21))
                .thenReturn(new MonitorLogSearchResult(traceC, List.of()));
        when(replayMapper.selectList(any()))
                .thenThrow(new IllegalStateException("database URL secret must-not-leak"))
                .thenReturn(List.of())
                .thenReturn(List.of());

        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "summary", "Some correlated evidence was unavailable.",
                "severity", "medium",
                "confidence", 0.45,
                "possibleCauses", List.of("More evidence may be needed."),
                "recommendations", List.of("Retry the unavailable sources."),
                "evidence", List.of("Trace B has related telemetry.")));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", outputText))))));
        server.expect(requestTo("https://ai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.input[0].content[0].text", allOf(
                        containsString("\"sourceFrames\":{\"status\":\"no_matches\""),
                        containsString("\"linkedTelemetry\":{\"status\":\"partial\",\"itemCount\":1"),
                        containsString("\"failedQueries\":1"),
                        containsString("trace B evidence"),
                        containsString("\"relatedLogs\":{\"status\":\"unavailable\""),
                        containsString("\"replayContext\":{\"status\":\"unavailable\""),
                        containsString("missing items are unknown"),
                        not(containsString("must-not-leak")),
                        not(containsString("provider.invalid")))))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        MonitorIssueAiAnalysisService service = new MonitorIssueAiAnalysisService(
                queryService, sourceMapService, logQueryService, replayMapper,
                objectMapper, builder.build(), true, "secret-test-key", "gpt-6-astra");
        var analysis = service.analyze(project, 9L);

        assertEquals("Some correlated evidence was unavailable.", analysis.summary());
        assertTrue(analysis.limitations().stream().anyMatch(value -> value.contains("linkedTelemetry is partial")));
        assertTrue(analysis.limitations().stream().anyMatch(value -> value.contains("relatedLogs could not be collected")));
        verify(queryService).explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq(traceB), isNull(), eq(21), eq(0));
        verify(queryService).explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                eq(traceC), isNull(), eq(21), eq(0));
        verify(logQueryService).search(project, traceB, 168, 21);
        verify(logQueryService).search(project, traceC, 168, 21);
        server.verify();
    }

    private Map<String, Object> issueEventWithStack(String eventTime, String traceId, String sessionId) {
        return Map.of(
                "event_time", eventTime,
                "environment", "production",
                "release", "release-2",
                "session_id", sessionId,
                "trace_id", traceId,
                "payload", "{\"data\":{\"name\":\"TypeError\",\"message\":\"failure\",\"stack\":\"at run (main.js:10:2)\"}}"
        );
    }

    private Map<String, Object> issueEventWithoutCorrelationContext(String eventTime) {
        return Map.of(
                "event_time", eventTime,
                "payload", "{\"data\":{\"name\":\"TypeError\",\"message\":\"failure\"}}"
        );
    }

    private Map<String, Object> issueEvent(String eventTime, String traceId) {
        return Map.of(
                "event_time", eventTime,
                "environment", "production",
                "release", "release-2",
                "trace_id", traceId,
                "payload", "{\"data\":{\"name\":\"TypeError\",\"message\":\"failure\"}}"
        );
    }

    private Map<String, Object> linkedEvent(String id, String title) {
        return Map.of(
                "signal_type", "error",
                "event_id", id,
                "event_time", "2026-09-10 10:00:01",
                "title", title,
                "release", "release-2",
                "environment", "production",
                "payload", "{\"data\":{\"name\":\"TypeError\",\"message\":\"related failure\"}}"
        );
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
