package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MonitorLogQueryServiceTest {

    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorLogQueryService service = new MonitorLogQueryService(clickHouse, "http://localhost:3100");

    @Test
    void rejectsInvalidTraceIdBeforeQueryingStorage() {
        MonitorProject project = project("store-a");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.search(project, "not-a-trace-id", 24, 200)
        );

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(clickHouse);
    }

    @Test
    void doesNotQueryLokiWhenTraceDoesNotBelongToProject() {
        when(clickHouse.queryForObject(any(String.class), eq(Long.class), any(Object[].class))).thenReturn(0L);
        MonitorProject project = project("store-a");

        var result = service.search(project, "0123456789abcdef0123456789abcdef", 24, 200);

        assertEquals(0, result.entries().size());
        verify(clickHouse).queryForObject(
                contains("project_id=? AND trace_id=?"),
                eq(Long.class),
                any(Object[].class)
        );
    }

    @Test
    void recognizesProfileTracesAsBelongingToProject() {
        when(clickHouse.queryForObject(any(String.class), eq(Long.class), any(Object[].class))).thenReturn(0L);

        service.search(project("store-a"), "0123456789abcdef0123456789abcdef", 24, 200);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(clickHouse).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        assertTrue(sql.getValue().contains(
                "UNION ALL SELECT event_id FROM monitor.profile_event WHERE project_id=? AND trace_id=?"));
    }

    @Test
    void filtersByUserAndMultipleEncodedTagsWithLogqlEscapingAndProjectGuard() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(clickHouse, builder.build());
        server.expect(request -> {
                    assertEquals("/loki/api/v1/query_range", request.getURI().getPath());
                    String query = UriUtils.decode(UriComponentsBuilder.fromUri(request.getURI()).build(true)
                            .getQueryParams().getFirst("query"), StandardCharsets.UTF_8);
                    assertEquals("{service_name=\"observability-platform\"} | monitor_project=\"store-a\" " +
                            "| monitor_user_id=\"u\\\"\\\\\\n\" " +
                            "| monitor_tags=~\".*(^|,)cmVnaW9u\\\\.Y24tZWFzdA(,|$).*\" " +
                            "| monitor_tags=~\".*(^|,)dGllcg\\\\.Z29sZA(,|$).*\"",
                            query);
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}",
                        MediaType.APPLICATION_JSON));

        queryService.search(project("store-a"), null, 24, 20, null, null, null, null,
                "u\"\\\n", Map.of("region", "cn-east", "tier", "gold"));

        server.verify();
        verifyNoInteractions(clickHouse);
    }

    @Test
    void groupsExploreLogsBySeverityAndParsesLokiBuckets() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(clickHouse, builder.build());
        server.expect(request -> {
                    assertEquals("/loki/api/v1/query", request.getURI().getPath());
                    String query = UriUtils.decode(UriComponentsBuilder.fromUri(request.getURI()).build(true)
                            .getQueryParams().getFirst("query"), StandardCharsets.UTF_8);
                    assertEquals("sum by (severity_text) (count_over_time({service_name=\"observability-platform\"} " +
                            "| monitor_project=\"store-a\" | monitor_trace_id=\"0123456789abcdef0123456789abcdef\" " +
                            "| monitor_environment=\"production\" | monitor_release=\"web-2\" |= \"checkout\" " +
                            "| severity_text=\"ERROR\" [24h]))", query);
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"severity_text":"ERROR"},"value":[1791015000,"7"]},
                          {"metric":{"severity_text":"WARN"},"value":[1791015000,"2"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        var buckets = queryService.aggregateForExplore(project("store-a"), 24,
                "0123456789abcdef0123456789abcdef", "checkout", "ERROR", "production", "web-2", "level");

        assertEquals(List.of("ERROR", "WARN"), buckets.stream().map(bucket -> bucket.value()).toList());
        assertEquals(List.of(7L, 2L), buckets.stream().map(bucket -> bucket.count()).toList());
        server.verify();
    }

    @Test
    void countsUniqueUsersByEnvironmentAndExcludesLogsWithoutUserMetadata() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(clickHouse, builder.build());
        server.expect(request -> {
                    String query = UriUtils.decode(UriComponentsBuilder.fromUri(request.getURI()).build(true)
                            .getQueryParams().getFirst("query"), StandardCharsets.UTF_8);
                    assertEquals("count by (monitor_environment) (count by (monitor_environment, monitor_user_id) " +
                            "(count_over_time({service_name=\"observability-platform\"} | monitor_project=\"store-a\" " +
                            "| monitor_user_id!=\"\" [24h])))", query);
                })
                .andExpect(method(HttpMethod.GET))
                // Repeated log lines from the same user collapse in the inner count by.
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"monitor_environment":"production"},"value":[1791015000,"2"]},
                          {"metric":{"monitor_environment":"staging"},"value":[1791015000,"1"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        var buckets = queryService.aggregateUniqueUsersForExplore(project("store-a"), 24,
                null, null, null, null, null, null, Map.of(), "environment");

        assertEquals(List.of("production", "staging"), buckets.stream().map(bucket -> bucket.value()).toList());
        assertEquals(List.of(2L, 1L), buckets.stream().map(bucket -> bucket.count()).toList());
        server.verify();
    }

    @Test
    void countsUniqueUsersAcrossLogsWhenGroupedBySignal() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(clickHouse, builder.build());
        server.expect(request -> {
                    String query = UriUtils.decode(UriComponentsBuilder.fromUri(request.getURI()).build(true)
                            .getQueryParams().getFirst("query"), StandardCharsets.UTF_8);
                    assertEquals("count(count by (monitor_user_id) (count_over_time({service_name=\"observability-platform\"} " +
                            "| monitor_project=\"store-a\" | monitor_user_id!=\"\" [24h])))", query);
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{},"value":[1791015000,"2"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        var buckets = queryService.aggregateUniqueUsersForExplore(project("store-a"), 24,
                null, null, null, null, null, null, Map.of(), "signal");

        assertEquals(List.of(new com.macro.mall.tiny.modules.monitor.dto.MonitorExploreAggregationResult.Bucket(
                "logs", 2, null)), buckets);
        server.verify();
    }

    @Test
    void returnsRawUniqueUserIdsForExactCrossStoreMergeWithAllFilters() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLogQueryService queryService = new MonitorLogQueryService(clickHouse, builder.build());
        server.expect(request -> {
                    String query = UriUtils.decode(UriComponentsBuilder.fromUri(request.getURI()).build(true)
                            .getQueryParams().getFirst("query"), StandardCharsets.UTF_8);
                    assertEquals("count by (monitor_user_id) (count_over_time({service_name=\"observability-platform\"} " +
                            "| monitor_project=\"store-a\" | monitor_trace_id=\"0123456789abcdef0123456789abcdef\" " +
                            "| monitor_environment=\"production\" | monitor_release=\"web-1\" " +
                            "| monitor_user_id=\"u1\" | monitor_tags=~\".*(^|,)cmVnaW9u\\\\.ZWFzdA(,|$).*\" " +
                            "|= \"checkout\" | severity_text=\"ERROR\" | monitor_user_id!=\"\" [24h]))", query);
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"monitor_user_id":"u1"},"value":[1791015000,"1"]},
                          {"metric":{"monitor_user_id":"u2"},"value":[1791015000,"2"]},
                          {"metric":{"monitor_user_id":""},"value":[1791015000,"1"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        var userIds = queryService.uniqueUserIdsForExplore(project("store-a"), 24,
                "0123456789abcdef0123456789abcdef", "checkout", "ERROR", "production", "web-1",
                "u1", Map.of("region", "east"));

        assertEquals(java.util.Set.of("u1", "u2"), userIds);
        server.verify();
    }

    private MonitorProject project(String key) {
        MonitorProject project = new MonitorProject();
        project.setProjectKey(key);
        return project;
    }
}
