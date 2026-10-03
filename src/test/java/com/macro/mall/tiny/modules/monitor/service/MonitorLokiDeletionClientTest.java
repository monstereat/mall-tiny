package com.macro.mall.tiny.modules.monitor.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MonitorLokiDeletionClientTest {

    @Test
    void submitsEncodedLogQlAndTimeRangeAndTreats204AsAcceptance() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLokiDeletionClient client = new MonitorLokiDeletionClient(builder.build());
        String query = "{service_name=\"observability-platform\"} | monitor_user_id=\"u & 1\"";
        Instant start = Instant.parse("2026-10-01T01:02:03.123Z");
        Instant end = Instant.parse("2026-10-02T04:05:06.789Z");

        server.expect(request -> {
                    assertEquals("/loki/api/v1/delete", request.getURI().getPath());
                    var params = UriComponentsBuilder.fromUri(request.getURI()).build(true).getQueryParams();
                    assertEquals(query, UriUtils.decode(params.getFirst("query"), StandardCharsets.UTF_8));
                    assertEquals(start.toString(), UriUtils.decode(params.getFirst("start"), StandardCharsets.UTF_8));
                    assertEquals(end.toString(), UriUtils.decode(params.getFirst("end"), StandardCharsets.UTF_8));
                })
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        client.submitDelete(query, start, end);

        server.verify();
    }

    @Test
    void parsesV35RequestFieldsAndProgress() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLokiDeletionClient client = new MonitorLokiDeletionClient(builder.build());
        server.expect(request -> assertEquals("/loki/api/v1/delete", request.getURI().getPath()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"request_id":"req-1","query":"{job=\\\"app\\\"} | user_id=\\\"u1\\\"",
                           "start_time":1790816523.123,"end_time":1790906706.789,
                           "created_at":1791015000.25,"status":"50% Complete"},
                          {"request_id":"req-2","query":"{job=\\\"app\\\"}",
                           "start_time":1790816523,"end_time":1790906706,
                           "created_at":1791015000,"status":"processed"},
                          {"request_id":"req-3","query":"{job=\\\"app\\\"}",
                           "start_time":1790816523,"end_time":1790906706,
                           "created_at":1791015000,"status":"received","progress":12}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<MonitorLokiDeleteRequest> requests = client.listRequests();

        assertEquals(3, requests.size());
        assertEquals("req-1", requests.get(0).requestId());
        assertEquals(Instant.ofEpochMilli(1_790_816_523_123L), requests.get(0).start());
        assertEquals(Instant.ofEpochMilli(1_790_906_706_789L), requests.get(0).end());
        assertEquals(Instant.ofEpochMilli(1_791_015_000_250L), requests.get(0).createdAt());
        assertEquals(50, requests.get(0).progress());
        assertEquals(100, requests.get(1).progress());
        assertEquals(12, requests.get(2).progress());
        assertEquals("{job=\"app\"} | user_id=\"u1\"", requests.get(0).query());
        server.verify();
    }

    @Test
    void rejectsInvalidTimeRangeBeforeSendingRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLokiDeletionClient client = new MonitorLokiDeletionClient(builder.build());

        assertThrows(IllegalArgumentException.class, () -> client.submitDelete(
                "{job=\"app\"}", Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")));

        server.verify();
    }

    @Test
    void propagatesLokiErrorResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:3100");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MonitorLokiDeletionClient client = new MonitorLokiDeletionClient(builder.build());
        server.expect(request -> assertEquals("/loki/api/v1/delete", request.getURI().getPath()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThrows(RestClientResponseException.class, client::listRequests);

        server.verify();
    }
}
