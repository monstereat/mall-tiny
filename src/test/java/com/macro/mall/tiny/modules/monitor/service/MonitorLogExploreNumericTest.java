package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreNumericBucket;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MonitorLogExploreNumericTest {

    private static final Instant END = Instant.parse("2026-02-03T04:05:06.123456789Z");
    private static final String MAX_FINITE = "1.7976931348623157e308";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggregatesAllNumericStatsAtOneInstantWithProjectAndExploreFilters() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(mock(JdbcTemplate.class), builder.build());
        String[] responses = {
                vector("\"production\"", "\"10.75\"", "\"staging\"", "\"3.25\""),
                vector("\"production\"", "\"-2.5\"", "\"staging\"", "\"1.25\""),
                vector("\"production\"", "\"10\"", "\"staging\"", "\"3.25\""),
                vector("\"production\"", "\"4\"", "\"staging\"", "\"2\"")
        };
        String[] aggregations = {"sum_over_time", "min_over_time", "max_over_time", "count_over_time"};
        for (int index = 0; index < responses.length; index++) {
            int queryIndex = index;
            String aggregation = aggregations[index];
            server.expect(request -> {
                        assertEquals("/loki/api/v1/query", request.getURI().getPath());
                        assertEquals(toNanos(END), UriComponentsBuilder.fromUri(request.getURI())
                                .build(true).getQueryParams().getFirst("time"));
                        String query = queryFrom(request.getURI());
                        assertTrue(query.startsWith((queryIndex == 3 ? "sum" : aggregation.substring(0,
                                aggregation.indexOf("_over_time"))) + " by (monitor_environment) (" + aggregation + "("));
                        assertTrue(query.contains("{service_name=\"observability-platform\"} | monitor_project=\"store-a\""));
                        assertTrue(query.contains("| monitor_trace_id=\"0123456789abcdef0123456789abcdef\""));
                        assertTrue(query.contains("| monitor_environment=\"production\" | monitor_release=\"web-2\""));
                        assertTrue(query.contains("| monitor_user_id=\"synthetic-user\""));
                        assertTrue(query.contains("| monitor_tags=~\".*(^|,)cmVnaW9u\\\\.ZWFzdA(,|$).*\""));
                        assertTrue(query.contains("|= \"checkout\" | severity_text=\"ERROR\""));
                        int filterIndex = query.indexOf("| severity_text=");
                        int jsonIndex = query.indexOf("| json monitor_explore_numeric_value=\"value\"");
                        assertTrue(filterIndex >= 0 && filterIndex < jsonIndex);
                        assertTrue(query.contains("| drop monitor_explore_numeric_value | json monitor_explore_numeric_value=\"value\""));
                        assertTrue(query.contains("| monitor_explore_numeric_value=~\"^[+-]?([0-9]+(\\\\.[0-9]*)?|\\\\.[0-9]+)([eE][+-]?[0-9]+)?$\""));
                        assertTrue(query.contains("| monitor_explore_numeric_value >= -" + MAX_FINITE));
                        assertTrue(query.contains("| monitor_explore_numeric_value <= " + MAX_FINITE + " | __error__=\"\""));
                        assertTrue(query.endsWith(queryIndex == 3
                                ? "[168h]))"
                                : "| unwrap monitor_explore_numeric_value | __error__=\"\" [168h]))"));
                    })
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(responses[queryIndex], MediaType.APPLICATION_JSON));
        }

        List<MonitorExploreNumericBucket> buckets = queryService.aggregateNumericForExplore(
                project("store-a"), 168, "0123456789abcdef0123456789abcdef", "checkout", "ERROR",
                "production", "web-2", "synthetic-user", Map.of("region", "east"), "environment", END);

        assertEquals(List.of(
                new MonitorExploreNumericBucket("production", 4, 10.75, -2.5, 10),
                new MonitorExploreNumericBucket("staging", 2, 3.25, 1.25, 3.25)), buckets);
        server.verify();
    }

    @Test
    void mapsWarningsAndNonFiniteResultsToFixedBadGatewayWithoutCause() {
        assertBadGateway("""
                {"status":"success","warnings":["synthetic partial warning"],"data":{"resultType":"vector","result":[]}}
                """);
        assertBadGateway(vector("\"production\"", "\"NaN\""));
    }

    @Test
    void rejectsInconsistentDimensionsAcrossStatistics() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(mock(JdbcTemplate.class), builder.build());
        server.expect(request -> { }).andRespond(withSuccess(vector("\"production\"", "\"10\""), MediaType.APPLICATION_JSON));
        server.expect(request -> { }).andRespond(withSuccess(vector("\"staging\"", "\"1\""), MediaType.APPLICATION_JSON));
        server.expect(request -> { }).andRespond(withSuccess(vector("\"production\"", "\"10\""), MediaType.APPLICATION_JSON));
        server.expect(request -> { }).andRespond(withSuccess(vector("\"production\"", "\"1\""), MediaType.APPLICATION_JSON));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                queryService.aggregateNumericForExplore(project("store-a"), 24, null, null, null,
                        null, null, null, Map.of(), "environment", END));

        assertEquals(502, error.getStatusCode().value());
        assertEquals("log storage returned an invalid numeric Explore result", error.getReason());
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void rejectsNonIntegerCounts() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(mock(JdbcTemplate.class), builder.build());
        server.expect(request -> { }).andRespond(withSuccess(vector("\"production\"", "\"10\""), MediaType.APPLICATION_JSON));
        server.expect(request -> { }).andRespond(withSuccess(vector("\"production\"", "\"1\""), MediaType.APPLICATION_JSON));
        server.expect(request -> { }).andRespond(withSuccess(vector("\"production\"", "\"10\""), MediaType.APPLICATION_JSON));
        server.expect(request -> { }).andRespond(withSuccess(vector("\"production\"", "\"1.5\""), MediaType.APPLICATION_JSON));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                queryService.aggregateNumericForExplore(project("store-a"), 24, null, null, null,
                        null, null, null, Map.of(), "environment", END));

        assertEquals(502, error.getStatusCode().value());
        assertEquals("log storage returned an invalid numeric Explore result", error.getReason());
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void returns422InsteadOfTruncatingMoreThanOneThousandGroups() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(mock(JdbcTemplate.class), builder.build());
        ArrayNode results = objectMapper.createArrayNode();
        for (int index = 0; index <= 1_000; index++) {
            ObjectNode item = results.addObject();
            item.putObject("metric").put("monitor_environment", "synthetic-env-" + index);
            ArrayNode sample = item.putArray("value");
            sample.add(1_791_015_000);
            sample.add("1");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "success");
        ObjectNode data = response.putObject("data");
        data.put("resultType", "vector");
        data.set("result", results);
        server.expect(request -> { }).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                queryService.aggregateNumericForExplore(project("store-a"), 24, null, null, null,
                        null, null, null, Map.of(), "environment", END));

        assertEquals(422, error.getStatusCode().value());
        assertTrue(error.getReason().contains("exceeds 1,000 groups"));
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void mapsLokiSeriesLimitTo422WithoutExposingResponseBodyOrCause() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(mock(JdbcTemplate.class), builder.build());
        server.expect(request -> { }).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("synthetic maximum series limit reached"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                queryService.aggregateNumericForExplore(project("store-a"), 24, null, null, null,
                        null, null, null, Map.of(), "environment", END));

        assertEquals(422, error.getStatusCode().value());
        assertTrue(error.getReason().contains("exceeds 1,000 groups"));
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void rejectsHoursOutsideTheSupportedLokiRange() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(mock(JdbcTemplate.class), builder.build());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                queryService.aggregateNumericForExplore(project("store-a"), 169, null, null, null,
                        null, null, null, Map.of(), "environment", END));

        assertEquals(400, error.getStatusCode().value());
        server.verify();
    }

    private void assertBadGateway(String response) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(mock(JdbcTemplate.class), builder.build());
        server.expect(request -> { }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                queryService.aggregateNumericForExplore(project("store-a"), 24, null, null, null,
                        null, null, null, Map.of(), "environment", END));

        assertEquals(502, error.getStatusCode().value());
        assertEquals("log storage returned an invalid numeric Explore result", error.getReason());
        assertNull(error.getCause());
        server.verify();
    }

    private String vector(String... groupAndValues) {
        StringBuilder result = new StringBuilder("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[");
        for (int index = 0; index < groupAndValues.length; index += 2) {
            if (index > 0) result.append(',');
            result.append("{\"metric\":{\"monitor_environment\":")
                    .append(groupAndValues[index]).append("},\"value\":[1791015000,")
                    .append(groupAndValues[index + 1]).append("]}");
        }
        return result.append("]}}").toString();
    }

    private String queryFrom(java.net.URI uri) {
        String encoded = UriComponentsBuilder.fromUri(uri).build(true).getQueryParams().getFirst("query");
        return UriUtils.decode(encoded, StandardCharsets.UTF_8);
    }

    private String toNanos(Instant instant) {
        return BigInteger.valueOf(instant.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(instant.getNano())).toString();
    }

    private MonitorProject project(String key) {
        MonitorProject project = new MonitorProject();
        project.setProjectKey(key);
        return project;
    }
}
