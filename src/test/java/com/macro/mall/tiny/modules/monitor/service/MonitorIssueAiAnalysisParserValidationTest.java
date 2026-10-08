package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueAiAnalysis;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MonitorIssueAiAnalysisParserValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MonitorIssueAiAnalysisService service = new MonitorIssueAiAnalysisService(
            null, null, null, null, objectMapper, null, false, "", "");

    @Test
    void rejectsNonFiniteOrStringCoercedConfidence() throws Exception {
        assertInvalid(output("Summary", "high", "NaN", List.of(), List.of(), List.of()));
        assertInvalid(output("Summary", "high", "0.5", List.of(), List.of(), List.of()));
    }

    @Test
    void rejectsWrongFieldTypesAndSchemaShape() throws Exception {
        assertInvalid(output(123, "high", 0.5, List.of(), List.of(), List.of()));
        assertInvalid(output("Summary", 3, 0.5, List.of(), List.of(), List.of()));
        assertInvalid(output("Summary", "high", 0.5, "not-an-array", List.of(), List.of()));
        assertInvalid(output("Summary", "high", 0.5, List.of("valid", 3), List.of(), List.of()));

        Map<String, Object> missing = validOutput();
        missing.remove("evidence");
        assertInvalid(objectMapper.writeValueAsString(missing));

        Map<String, Object> extra = validOutput();
        extra.put("unexpected", "value");
        assertInvalid(objectMapper.writeValueAsString(extra));
    }

    @Test
    void rejectsTrailingJsonAfterStructuredOutput() throws Exception {
        assertInvalid(objectMapper.writeValueAsString(validOutput()) + " {} ");
    }

    @Test
    void rejectsDuplicateFieldsAsSafeBadGateway() throws Exception {
        String duplicateOutput = "{\"summary\":\"First summary.\",\"summary\":\"Second summary.\"," +
                "\"severity\":\"high\",\"confidence\":0.8,\"possibleCauses\":[\"Cause.\"]," +
                "\"recommendations\":[\"Check the frame.\"],\"evidence\":[\"Synthetic event.\"]}";
        assertInvalid(duplicateOutput);

        MonitorQueryService queryService = mock(MonitorQueryService.class);
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        MonitorIssue issue = new MonitorIssue();
        issue.setId(7L);
        issue.setProjectId(42L);
        issue.setTitle("Synthetic parser validation issue");
        when(queryService.issue(42L, 7L)).thenReturn(issue);
        when(queryService.issueEvents(project, issue, 5)).thenReturn(List.of());

        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of("content", List.of(Map.of(
                        "type", "output_text", "text", duplicateOutput))))));
        server.expect(requestTo("https://ai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        MonitorIssueAiAnalysisService configuredService = new MonitorIssueAiAnalysisService(
                queryService,
                mock(MonitorSourceMapService.class),
                mock(MonitorLogQueryService.class),
                mock(MonitorReplayMapper.class),
                objectMapper,
                builder.build(),
                true,
                "synthetic-test-api-key",
                "synthetic-test-model");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> configuredService.analyze(project, 7L));

        assertEquals(502, error.getStatusCode().value());
        assertEquals("Configured AI service returned an invalid response", error.getReason());
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void preservesValidOutputSanitizationAndArrayProjectionLimit() throws Exception {
        List<String> causes = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            causes.add("Possible cause " + index);
        }
        MonitorIssueAiAnalysis analysis = parse(output(
                "Failure reported by person@example.com", "medium", 0.75,
                causes, List.of("Inspect the failing frame."), List.of("A matching error event.")));

        assertEquals("Failure reported by [REDACTED_EMAIL]", analysis.summary());
        assertEquals(0.75, analysis.confidence());
        assertEquals(causes.subList(0, 8), analysis.possibleCauses());
        assertEquals(List.of("Inspect the failing frame."), analysis.recommendations());
    }

    private void assertInvalid(String resultText) {
        assertThrows(IllegalArgumentException.class, () -> parse(resultText));
    }

    private MonitorIssueAiAnalysis parse(String resultText) {
        try {
            Class<?> sanitizerType = List.of(service.getClass().getDeclaredClasses()).stream()
                    .filter(type -> type.getSimpleName().equals("Sanitizer"))
                    .findFirst()
                    .orElseThrow();
            Constructor<?> sanitizerConstructor = sanitizerType.getDeclaredConstructor(
                    MonitorIssueAiAnalysisService.class, Set.class);
            sanitizerConstructor.setAccessible(true);
            Object sanitizer = sanitizerConstructor.newInstance(service, Set.of());

            Method parser = service.getClass().getDeclaredMethod(
                    "parseAnalysis", JsonNode.class, sanitizerType, List.class);
            parser.setAccessible(true);
            return (MonitorIssueAiAnalysis) parser.invoke(service, response(resultText), sanitizer, List.of());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException invalidOutput) {
                throw invalidOutput;
            }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode response(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "output_text");
        content.put("text", text);
        ArrayNode contents = objectMapper.createArrayNode().add(content);
        ObjectNode item = objectMapper.createObjectNode().set("content", contents);
        return objectMapper.createObjectNode().set("output", objectMapper.createArrayNode().add(item));
    }

    private String output(Object summary, Object severity, Object confidence,
                          Object possibleCauses, Object recommendations, Object evidence) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("severity", severity);
        result.put("confidence", confidence);
        result.put("possibleCauses", possibleCauses);
        result.put("recommendations", recommendations);
        result.put("evidence", evidence);
        return objectMapper.writeValueAsString(result);
    }

    private Map<String, Object> validOutput() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", "A failure occurred.");
        result.put("severity", "medium");
        result.put("confidence", 0.5);
        result.put("possibleCauses", List.of());
        result.put("recommendations", List.of());
        result.put("evidence", List.of());
        return result;
    }
}
