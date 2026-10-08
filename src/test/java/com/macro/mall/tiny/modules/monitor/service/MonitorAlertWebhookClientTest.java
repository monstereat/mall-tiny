package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MonitorAlertWebhookClientTest {

    @Test
    void resolvesAndPinsPublicDestinationBeforeSendingPayload() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alert", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("POST".equals(exchange.getRequestMethod()) && body.contains("deliveryId")) requests.incrementAndGet();
            assertEquals("hooks.example.test:" + server.getAddress().getPort(),
                    exchange.getRequestHeaders().getFirst("Host"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        MonitorAlertWebhookClient client = new MonitorAlertWebhookClient(new ObjectMapper(),
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}, address -> true);
        try {
            client.post("http://hooks.example.test:" + server.getAddress().getPort() + "/alert",
                    Map.of("deliveryId", "delivery-1"));
        } finally {
            server.stop(0);
        }

        assertEquals(1, requests.get());
    }

    @Test
    void rejectsPrivateAndMixedDnsResultsBeforeConnecting() throws Exception {
        MonitorAlertWebhookClient privateClient = new MonitorAlertWebhookClient(new ObjectMapper(),
                host -> new InetAddress[]{InetAddress.getByName("10.0.0.1")},
                MonitorAlertWebhookClient::isPublicAddress);
        MonitorAlertWebhookClient mixedClient = new MonitorAlertWebhookClient(new ObjectMapper(),
                host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("169.254.169.254")},
                MonitorAlertWebhookClient::isPublicAddress);

        assertThrows(MonitorAlertWebhookClient.UnsafeTargetException.class,
                () -> privateClient.post("https://hooks.example.test/alert", Map.of("deliveryId", "private")));
        assertThrows(MonitorAlertWebhookClient.UnsafeTargetException.class,
                () -> mixedClient.post("https://hooks.example.test/alert", Map.of("deliveryId", "mixed")));
    }

    @Test
    void refusesPrivateIpLiteralsAndLocalHostnamesWhenSavingRoutes() {
        MonitorAlertWebhookClient client = new MonitorAlertWebhookClient(new ObjectMapper());

        assertThrows(MonitorAlertWebhookClient.UnsafeTargetException.class,
                () -> client.normalizeUrl("http://127.0.0.1/alert"));
        assertThrows(MonitorAlertWebhookClient.UnsafeTargetException.class,
                () -> client.normalizeUrl("http://metadata.google.internal/"));
    }

    @Test
    void doesNotFollowRedirectsToAnotherDestination() throws Exception {
        AtomicInteger targetRequests = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/target", exchange -> {
            targetRequests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        target.start();
        var source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://hooks.example.test:"
                    + target.getAddress().getPort() + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        source.start();
        MonitorAlertWebhookClient client = new MonitorAlertWebhookClient(new ObjectMapper(),
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}, address -> true);
        try {
            assertThrows(IllegalStateException.class,
                    () -> client.post("http://hooks.example.test:" + source.getAddress().getPort() + "/",
                            Map.of("deliveryId", "delivery-2")));
        } finally {
            source.stop(0);
            target.stop(0);
        }

        assertEquals(0, targetRequests.get());
    }

    @Test
    void readsContentLengthJsonResponse() throws Exception {
        byte[] body = "{\"errcode\":0,\"message\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

        JsonNode response = postForJsonWithLocalSocket(
                "Content-Length: " + body.length + "\r\nConnection: close", body);

        assertEquals(0, response.path("errcode").asInt(-1));
        assertEquals("ok", response.path("message").asText());
    }

    @Test
    void readsChunkedJsonResponse() throws Exception {
        byte[] body = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
        byte[] chunks = (Integer.toHexString(body.length) + "\r\n" + new String(body, StandardCharsets.UTF_8)
                + "\r\n0\r\n\r\n").getBytes(StandardCharsets.UTF_8);

        JsonNode response = postForJsonWithLocalSocket(
                "Transfer-Encoding: chunked\r\nConnection: close", chunks);

        assertEquals(0, response.path("errcode").asInt(-1));
    }

    @Test
    void readsConnectionCloseDelimitedJsonResponse() throws Exception {
        byte[] body = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);

        JsonNode response = postForJsonWithLocalSocket("Connection: close", body);

        assertEquals(0, response.path("errcode").asInt(-1));
    }

    @Test
    void rejectsOversizedJsonResponseWithoutEchoingCredentials() throws Exception {
        byte[] response = new byte[64 * 1024 + 1];
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> postForJsonWithLocalSocket("Content-Length: " + response.length, new byte[0]));

        assertEquals("webhook response body exceeds 64 KiB", error.getMessage());
        assertFalse(error.getMessage().contains("private-url-secret"));
    }

    @Test
    void countsCarriageReturnsTowardResponseLineLimit() throws Exception {
        String oversizedLine = "X-Pad: " + "\r".repeat(8 * 1024);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> postForJsonWithLocalSocket(oversizedLine, new byte[0]));

        assertEquals("webhook response line exceeds limit", error.getMessage());
    }

    @Test
    void rejectsInvalidAndTrailingJsonWithoutEchoingResponseBody() throws Exception {
        byte[] invalid = "{\"token\":\"private-response-secret\",}".getBytes(StandardCharsets.UTF_8);
        IllegalStateException invalidError = assertThrows(IllegalStateException.class,
                () -> postForJsonWithLocalSocket("Content-Length: " + invalid.length, invalid));
        assertEquals("webhook returned invalid JSON", invalidError.getMessage());
        assertFalse(invalidError.getMessage().contains("private-response-secret"));

        byte[] trailing = "{\"errcode\":0} {\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
        IllegalStateException trailingError = assertThrows(IllegalStateException.class,
                () -> postForJsonWithLocalSocket("Content-Length: " + trailing.length, trailing));
        assertEquals("webhook returned invalid JSON", trailingError.getMessage());
    }

    private JsonNode postForJsonWithLocalSocket(String responseHeaders, byte[] responseBody) throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread responder = new Thread(() -> {
                try (Socket connection = server.accept()) {
                    consumeRequest(connection.getInputStream());
                    OutputStream output = connection.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\n" + responseHeaders + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(responseBody);
                    output.flush();
                    connection.shutdownOutput();
                } catch (Throwable error) {
                    serverFailure.set(error);
                }
            });
            responder.start();

            JsonNode response;
            try {
                int port = server.getLocalPort();
                MonitorAlertWebhookClient client = new MonitorAlertWebhookClient(new ObjectMapper(),
                        host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}, address -> true);
                response = client.postForJson("http://hooks.example.test:" + port
                        + "/alert?token=private-url-secret", Map.of("test", "payload"));
            } finally {
                responder.join(5_000);
            }
            if (responder.isAlive()) throw new AssertionError("local webhook responder did not finish");
            if (serverFailure.get() != null) throw new AssertionError("local webhook responder failed");
            return response;
        }
    }

    private void consumeRequest(InputStream input) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (matched < terminator.length) {
            int value = input.read();
            if (value < 0) throw new IOException("incomplete local request");
            headers.write(value);
            matched = value == terminator[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
        }
        String headerText = headers.toString(StandardCharsets.US_ASCII);
        int contentLength = 0;
        for (String line : headerText.split("\\r\\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        byte[] buffer = new byte[1024];
        while (contentLength > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, contentLength));
            if (read < 0) throw new IOException("incomplete local request body");
            contentLength -= read;
        }
    }
}
