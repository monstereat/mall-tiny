package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.config.MonitorIssueAiConfiguration;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueAiAnalysis;
import com.macro.mall.tiny.modules.monitor.dto.MonitorLogSearchResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTraceSpan;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapStackFrame;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorReplay;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Manual real-provider acceptance entry point. Not a JUnit/Surefire test. */
public final class MonitorIssueAiProviderAcceptanceHarness {

    private static final String TRACE_ID = "a11ce00000000000a11ce00000000001";
    private static final String RELEASE = "synthetic-release-issue-ai-acceptance";
    private static final String ENVIRONMENT = "synthetic-acceptance";
    private static final String SESSION_ID = "synthetic-private-session-id";
    private static final List<String> PRIVATE_MARKERS = List.of(
            "synthetic-private-email@example.invalid",
            "synthetic-private-bearer-secret",
            "synthetic-private-api-key",
            "synthetic-private-title-value",
            "synthetic-private-event-value",
            "synthetic-private-source-value",
            "synthetic-private-span-value",
            "synthetic-private-log-secret",
            SESSION_ID,
            "synthetic-private-user-id",
            "synthetic-private-page-host",
            "synthetic-private-replay-payload"
    );

    private MonitorIssueAiProviderAcceptanceHarness() {
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("MONITOR_AI_API_KEY");
        String baseUrl = System.getenv("MONITOR_AI_BASE_URL");
        String model = System.getenv("MONITOR_AI_MODEL");
        if (isBlank(apiKey) || isBlank(baseUrl) || isBlank(model)) {
            fail("config", "required_environment_missing", 0);
        }

        URI providerUri;
        try {
            providerUri = URI.create(baseUrl);
        } catch (IllegalArgumentException ignored) {
            fail("config", "provider_url_invalid", 0);
            return;
        }
        if (!"https".equalsIgnoreCase(providerUri.getScheme())
                || !"api.deepseek.com".equalsIgnoreCase(providerUri.getHost())
                || providerUri.getUserInfo() != null
                || providerUri.getQuery() != null
                || providerUri.getFragment() != null) {
            fail("config", "provider_host_not_allowed", 0);
        }

        ObjectMapper objectMapper = new ObjectMapper();
        AtomicBoolean preflightPassed = new AtomicBoolean();
        AtomicInteger providerHttpStatus = new AtomicInteger();
        AtomicReference<ContextCounts> contextCounts = new AtomicReference<>();
        ClientHttpRequestInterceptor preflightInterceptor = (request, body, execution) -> {
            if (request.getMethod() != HttpMethod.POST
                    || !"api.deepseek.com".equalsIgnoreCase(request.getURI().getHost())
                    || !request.getURI().getPath().endsWith("/responses")
                    || !("Bearer " + apiKey).equals(request.getHeaders().getFirst("Authorization"))) {
                throw new RestClientException("acceptance request preflight failed");
            }
            verifyRequest(objectMapper, body, apiKey, contextCounts);
            preflightPassed.set(true);
            var response = execution.execute(request, body);
            providerHttpStatus.set(response.getStatusCode().value());
            return response;
        };

        try {
            MonitorProject project = syntheticProject();
            MonitorIssue issue = syntheticIssue();
            MonitorQueryService queryService = mock(MonitorQueryService.class);
            MonitorSourceMapService sourceMapService = mock(MonitorSourceMapService.class);
            MonitorLogQueryService logQueryService = mock(MonitorLogQueryService.class);
            MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);

            when(queryService.issue(project.getId(), issue.getId())).thenReturn(issue);
            when(queryService.issueEvents(project, issue, 5)).thenReturn(List.of(syntheticIssueEvent(objectMapper)));
            when(sourceMapService.resolveStackForAdmin(eq(project), eq(RELEASE), eq(ENVIRONMENT),
                    anyString(), isNull(), isNull(), isNull())).thenReturn(List.of(syntheticSourceFrame()));
            when(queryService.explore(eq(project), eq(24 * 90), isNull(), isNull(), isNull(),
                    eq(TRACE_ID), isNull(), eq(21), eq(0)))
                    .thenReturn(new MonitorExploreResult(List.of(syntheticPerformanceEvent(objectMapper)), false, 21));
            when(queryService.traceSpans(project, TRACE_ID)).thenReturn(List.of(syntheticErrorSpan()));
            when(logQueryService.search(project, TRACE_ID, 168, 21))
                    .thenReturn(new MonitorLogSearchResult(TRACE_ID, List.of(syntheticLogEntry())));
            when(replayMapper.selectList(any())).thenReturn(List.of(syntheticReplay()));

            RestClient aiClient = new MonitorIssueAiConfiguration().monitorIssueAiRestClient(
                    RestClient.builder().requestInterceptor(preflightInterceptor), baseUrl);
            MonitorIssueAiAnalysisService service = new MonitorIssueAiAnalysisService(
                    queryService, sourceMapService, logQueryService, replayMapper,
                    objectMapper, aiClient, true, apiKey, model);
            MonitorIssueAiAnalysis analysis = service.analyze(project, issue.getId());
            ContextCounts counts = contextCounts.get();
            if (!preflightPassed.get() || counts == null || analysis == null
                    || analysis.possibleCauses() == null || analysis.recommendations() == null
                    || analysis.evidence() == null || analysis.limitations() == null
                    || analysis.possibleCauses().isEmpty() || analysis.recommendations().isEmpty()
                    || analysis.evidence().isEmpty()) {
                fail("provider_response", "acceptance_invariant_failed", providerHttpStatus.get());
            }
            System.out.printf("AI_ACCEPTANCE status=passed preflight=passed providerHttpStatus=%d severity=%s " +
                            "sourceFrames=%d linkedTelemetry=%d linkedSpans=%d relatedLogs=%d replayContext=%d " +
                            "causes=%d recommendations=%d evidence=%d limitations=%d%n",
                    providerHttpStatus.get(), analysis.severity(), counts.sourceFrames(), counts.linkedTelemetry(),
                    counts.linkedSpans(), counts.relatedLogs(), counts.replayContext(),
                    analysis.possibleCauses().size(), analysis.recommendations().size(),
                    analysis.evidence().size(), analysis.limitations().size());
        } catch (ResponseStatusException ignored) {
            fail(preflightPassed.get() ? "provider_response" : "request_preflight",
                    preflightPassed.get() ? "service_rejected_provider_response" : "preflight_rejected",
                    providerHttpStatus.get());
        } catch (Exception | AssertionError ignored) {
            fail(preflightPassed.get() ? "provider_response" : "request_preflight",
                    preflightPassed.get() ? "provider_or_transport_failure" : "preflight_rejected",
                    providerHttpStatus.get());
        }
    }

    private static void verifyRequest(ObjectMapper objectMapper, byte[] requestBody, String apiKey,
                                      AtomicReference<ContextCounts> contextCounts) {
        try {
            String body = new String(requestBody, StandardCharsets.UTF_8);
            if (body.contains(apiKey)) throw new RestClientException("acceptance request preflight failed");
            JsonNode request = objectMapper.readTree(body);
            if (request.path("store").asBoolean(true)) throw new RestClientException("acceptance request preflight failed");
            String inputText = request.at("/input/0/content/0/text").asText("");
            int evidenceStart = inputText.indexOf('\n');
            if (evidenceStart < 0) throw new RestClientException("acceptance request preflight failed");
            JsonNode evidence = objectMapper.readTree(inputText.substring(evidenceStart + 1));
            requireAvailableEvidence(evidence, "sourceFrames");
            requireAvailableEvidence(evidence, "linkedTelemetry");
            requireAvailableEvidence(evidence, "linkedSpans");
            requireAvailableEvidence(evidence, "relatedLogs");
            requireAvailableEvidence(evidence, "replayContext");
            if (!evidence.path("issueTitle").asText().startsWith("synthetic")
                    || !evidence.at("/recentEvents/0/errorMessage").asText().startsWith("synthetic")
                    || !"error".equals(evidence.at("/linkedSpans/0/status").asText())
                    || !evidence.at("/linkedSpans/0/serviceName").asText().startsWith("synthetic")
                    || !TRACE_ID.equals(evidence.at("/linkedSpans/0/traceId").asText())
                    || !evidence.at("/linkedTelemetry/0/metric").asText().startsWith("synthetic")
                    || !evidence.at("/relatedLogs/0/line").asText().startsWith("synthetic")
                    || !evidence.at("/replayContext/0/release").asText().startsWith("synthetic")
                    || !evidence.at("/sourceFrames/0/mapped").asBoolean(false)
                    || !evidence.at("/sourceFrames/0/sourceContext").asText().contains("syntheticProfile = null")) {
                throw new RestClientException("acceptance request preflight failed");
            }
            for (String marker : PRIVATE_MARKERS) {
                if (body.contains(marker)) throw new RestClientException("acceptance request preflight failed");
            }
            contextCounts.set(new ContextCounts(
                    evidence.path("sourceFrames").size(),
                    evidence.path("linkedTelemetry").size(),
                    evidence.path("linkedSpans").size(),
                    evidence.path("relatedLogs").size(),
                    evidence.path("replayContext").size()
            ));
        } catch (RestClientException failure) {
            throw failure;
        } catch (Exception ignored) {
            throw new RestClientException("acceptance request preflight failed");
        }
    }

    private static void requireAvailableEvidence(JsonNode evidence, String name) {
        JsonNode items = evidence.path(name);
        JsonNode status = evidence.path("evidenceSources").path(name);
        if (!items.isArray() || items.size() == 0 || !"available".equals(status.path("status").asText())) {
            throw new RestClientException("acceptance request preflight failed");
        }
    }

    private static MonitorProject syntheticProject() {
        MonitorProject project = new MonitorProject();
        project.setId(990_001L);
        project.setProjectKey("synthetic-issue-ai-acceptance");
        project.setCustomSensitiveFields("customer_ref");
        return project;
    }

    private static MonitorIssue syntheticIssue() {
        MonitorIssue issue = new MonitorIssue();
        issue.setId(990_002L);
        issue.setProjectId(990_001L);
        issue.setFingerprint("synthetic-issue-ai-acceptance-fingerprint");
        issue.setTitle("synthetic TypeError customer_ref=synthetic-private-title-value");
        issue.setStatus("unresolved");
        issue.setEventCount(1L);
        issue.setAffectedUsers(1L);
        issue.setLatestRelease(RELEASE);
        return issue;
    }

    private static Map<String, Object> syntheticIssueEvent(ObjectMapper objectMapper) throws Exception {
        Map<String, Object> data = Map.of(
                "name", "synthetic TypeError",
                "message", "synthetic null profile access for synthetic-private-email@example.invalid; " +
                        "Bearer synthetic-private-bearer-secret api_key=synthetic-private-api-key " +
                        "customer_ref=synthetic-private-event-value",
                "stack", "TypeError: synthetic null profile access\n" +
                        "    at syntheticRenderCard (https://synthetic.example/assets/synthetic.js:10:7)",
                "breadcrumbs", List.of(Map.of("type", "synthetic-render", "data",
                        Map.of("customer_ref", "synthetic-private-event-value")))
        );
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_time", "synthetic-time-2026-10-04T04:00:00Z");
        row.put("environment", ENVIRONMENT);
        row.put("release", RELEASE);
        row.put("user_id", "synthetic-private-user-id");
        row.put("session_id", SESSION_ID);
        row.put("page_url", "https://synthetic-private-page-host.invalid/synthetic");
        row.put("trace_id", TRACE_ID);
        row.put("payload", objectMapper.writeValueAsString(Map.of("data", data)));
        return row;
    }

    private static SourceMapStackFrame syntheticSourceFrame() {
        return new SourceMapStackFrame(0, "syntheticRenderCard", "at syntheticRenderCard (synthetic.js:10:7)",
                "https://synthetic.example/assets/synthetic.js", 10, 7, true, "/src/synthetic/Checkout.vue",
                12, 3, "syntheticRenderCard",
                "const syntheticProfile = null;\n" +
                        "return syntheticProfile.customer_ref;\n" +
                        "const syntheticApiKey = 'api_key=synthetic-private-api-key';\n" +
                        "const syntheticContact = 'synthetic-private-email@example.invalid';\n" +
                        "const syntheticCustomer = 'customer_ref=synthetic-private-source-value';");
    }

    private static Map<String, Object> syntheticPerformanceEvent(ObjectMapper objectMapper) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("signal_type", "performance");
        row.put("event_id", "synthetic-performance-event");
        row.put("event_time", "synthetic-time-2026-10-04T04:00:01Z");
        row.put("title", "synthetic slow LCP");
        row.put("release", RELEASE);
        row.put("environment", ENVIRONMENT);
        row.put("trace_id", TRACE_ID);
        row.put("payload", objectMapper.writeValueAsString(Map.of("data", Map.of(
                "metric", "synthetic-LCP", "value", 4_321, "unit", "ms",
                "tags", Map.of("customer_ref", "synthetic-private-event-value")))));
        return row;
    }

    private static MonitorTraceSpan syntheticErrorSpan() {
        return new MonitorTraceSpan(TRACE_ID, "a11ce00000000001", "a11ce00000000002", "server",
                "synthetic-orders-service", "server", "otel.server",
                "synthetic failed request; customer_ref=synthetic-private-span-value " +
                        "synthetic-private-email@example.invalid",
                Instant.parse("2026-10-04T04:00:00Z").toEpochMilli(), 4_321.0, "error", 503L);
    }

    private static MonitorLogSearchResult.Entry syntheticLogEntry() {
        return new MonitorLogSearchResult.Entry("synthetic-time-2026-10-04T04:00:02Z",
                "synthetic server failure Bearer synthetic-private-log-secret " +
                        "customer_ref=synthetic-private-event-value",
                Map.of("synthetic-service", "synthetic-orders-service"), Map.of());
    }

    private static MonitorReplay syntheticReplay() {
        MonitorReplay replay = new MonitorReplay();
        replay.setProjectId(990_001L);
        replay.setEventId("synthetic-replay-event");
        replay.setSessionId(SESSION_ID);
        replay.setReleaseVersion(RELEASE);
        replay.setObjectKey("synthetic-private-replay-payload");
        replay.setEventCount(3);
        replay.setStartTime(Date.from(Instant.parse("2026-10-04T03:59:50Z")));
        replay.setEndTime(Date.from(Instant.parse("2026-10-04T04:00:10Z")));
        return replay;
    }

    private static void fail(String stage, String category, int httpStatus) {
        int safeHttpStatus = httpStatus >= 100 && httpStatus <= 599 ? httpStatus : 0;
        System.out.printf("AI_ACCEPTANCE status=failed stage=%s category=%s providerHttpStatus=%d%n",
                stage, category, safeHttpStatus);
        System.exit(1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ContextCounts(int sourceFrames, int linkedTelemetry, int linkedSpans,
                                 int relatedLogs, int replayContext) {
    }
}
