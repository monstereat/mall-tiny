package com.macro.mall.tiny.modules.monitor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MonitorUptimeProbeTest {

    private static final String HOST = "uptime.test";
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final MonitorUptimeProbe probe = new MonitorUptimeProbe();

    @Test
    void acceptsExpectedStatusAndReportsUnexpectedStatus() throws Exception {
        try (TestHttpServer server = new TestHttpServer((request, index) ->
                index == 0 ? Response.status(204) : Response.status(503))) {
            MonitorUptimeProbe.Result expected = checkThroughLocalServer(server, 204, 1000);
            MonitorUptimeProbe.Result unexpected = checkThroughLocalServer(server, 200, 1000);

            assertTrue(expected.successful());
            assertEquals(204, expected.responseStatus());
            assertFalse(unexpected.successful());
            assertEquals(503, unexpected.responseStatus());
            assertEquals("Expected HTTP 200 but received HTTP 503", unexpected.error());
        }
    }

    @Test
    void reportsReadTimeoutFromLocalServer() throws Exception {
        try (TestHttpServer server = new TestHttpServer((request, index) -> Response.status(200).delayed(400))) {
            MonitorUptimeProbe.Result result = checkThroughLocalServer(server, 200, 100);

            assertFalse(result.successful());
            assertEquals("Request timed out", result.error());
        }
    }

    @Test
    void doesNotFollowRedirectResponses() throws Exception {
        try (TestHttpServer server = new TestHttpServer((request, index) -> index == 0
                ? Response.redirect("/followed") : Response.status(200))) {
            MonitorUptimeProbe.Result result = checkThroughLocalServer(server, 200, 1000);

            assertFalse(result.successful());
            assertEquals(302, result.responseStatus());
            assertFalse(server.awaitRequestCount(2, 250));
            assertEquals(1, server.requests().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/health",
            "http://10.1.2.3/health",
            "http://192.168.1.10/health",
            "http://169.254.10.20/health",
            "http://localhost/health",
            "http://api.local/health",
            "http://service.internal/health",
            "http://router.home.arpa/health"
    })
    void rejectsLoopbackPrivateLinkLocalAndLocalDomainTargets(String url) {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> probe.validateUrl(url));
    }

    private MonitorUptimeProbe.Result checkThroughLocalServer(TestHttpServer server,
                                                               int expectedStatus,
                                                               int timeoutMs) throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(HOST, new byte[]{(byte) 93, (byte) 184, (byte) 216, 34});
        List<Socket> delegates = new ArrayList<>(List.of(new Socket(), new Socket(), new Socket()));
        AtomicInteger nextDelegate = new AtomicInteger();
        String url = "http://" + HOST + ":" + server.port() + "/health";

        try (MockedStatic<InetAddress> dns = mockStatic(InetAddress.class);
             MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (mock, context) -> {
                 Socket delegate = delegates.get(nextDelegate.getAndIncrement());
                 doAnswer(invocation -> {
                     delegate.connect(new InetSocketAddress(LOOPBACK, server.port()), invocation.getArgument(1));
                     return null;
                 }).when(mock).connect(any(SocketAddress.class), anyInt());
                 doAnswer(invocation -> {
                     delegate.setSoTimeout(invocation.getArgument(0));
                     return null;
                 }).when(mock).setSoTimeout(anyInt());
                 when(mock.getInputStream()).thenAnswer(invocation -> delegate.getInputStream());
                 when(mock.getOutputStream()).thenAnswer(invocation -> delegate.getOutputStream());
                 doAnswer(invocation -> {
                     delegate.close();
                     return null;
                 }).when(mock).close();
             })) {
            // The production DNS guard sees a documentation-only public address. This test-only socket adapter
            // sends the connection to the local server so no internet socket can be opened by this test.
            dns.when(() -> InetAddress.getAllByName(HOST)).thenReturn(new InetAddress[]{publicAddress});
            return probe.check(url, "GET", expectedStatus, timeoutMs);
        } finally {
            for (Socket delegate : delegates) delegate.close();
        }
    }

    private record Response(int status, String reason, String location, int delayMs) {

        static Response status(int status) {
            return new Response(status, status == 200 ? "OK" : status == 204 ? "No Content" : "Unavailable", null, 0);
        }

        static Response redirect(String location) {
            return new Response(302, "Found", location, 0);
        }

        Response delayed(int delayMs) {
            return new Response(status, reason, location, delayMs);
        }
    }

    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket server;
        private final BiFunction<String, Integer, Response> responder;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final Thread worker;

        private TestHttpServer(BiFunction<String, Integer, Response> responder) throws IOException {
            this.server = new ServerSocket(0, 4, LOOPBACK);
            this.server.setSoTimeout(1200);
            this.responder = responder;
            this.worker = new Thread(this::serve, "monitor-uptime-probe-test-server");
            this.worker.setDaemon(true);
            this.worker.start();
        }

        int port() {
            return server.getLocalPort();
        }

        List<String> requests() {
            return List.copyOf(requests);
        }

        boolean awaitRequestCount(int count, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (requests.size() < count && System.currentTimeMillis() < deadline) Thread.sleep(10);
            return requests.size() >= count;
        }

        private void serve() {
            int index = 0;
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    String request = readRequest(client);
                    requests.add(request);
                    Response response = responder.apply(request, index++);
                    if (response.delayMs() > 0) Thread.sleep(response.delayMs());
                    writeResponse(client, response);
                } catch (java.net.SocketTimeoutException timeout) {
                    return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException ignored) {
                    if (server.isClosed()) return;
                }
            }
        }

        private String readRequest(Socket client) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String request = reader.readLine();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Read through the header terminator before responding.
            }
            return request;
        }

        private void writeResponse(Socket client, Response response) throws IOException {
            StringBuilder headers = new StringBuilder("HTTP/1.1 ")
                    .append(response.status()).append(' ').append(response.reason())
                    .append("\r\nContent-Length: 0\r\nConnection: close\r\n");
            if (response.location() != null) headers.append("Location: ").append(response.location()).append("\r\n");
            headers.append("\r\n");
            client.getOutputStream().write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
        }

        @Override
        public void close() throws Exception {
            server.close();
            worker.interrupt();
            worker.join(300);
        }
    }
}
