package com.macro.mall.tiny.modules.monitor.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MonitorUptimeProbe {

    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] ([1-5][0-9]{2})(?:\\s.*)?");

    public Result check(String url, String method, int expectedStatusCode, int timeoutMs) {
        long startedAt = System.nanoTime();
        Integer responseStatus = null;
        try {
            URI uri = validateUrl(url);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            InetAddress address = resolvePublicAddress(host);
            try (Socket connection = new Socket()) {
                connection.connect(new InetSocketAddress(address, port), timeoutMs);
                connection.setSoTimeout(timeoutMs);
                Socket requestSocket = connection;
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    SSLSocket ssl = (SSLSocket) sslSocketFactory.createSocket(connection, host, port, true);
                    SSLParameters parameters = ssl.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!isIpv4Literal(host)) parameters.setServerNames(List.of(new SNIHostName(host)));
                    ssl.setSSLParameters(parameters);
                    ssl.startHandshake();
                    requestSocket = ssl;
                }
                writeRequest(requestSocket.getOutputStream(), uri, host, port, method);
                Matcher status = STATUS_LINE.matcher(readStatusLine(requestSocket.getInputStream()));
                if (!status.matches()) throw new IOException("invalid HTTP status line");
                responseStatus = Integer.parseInt(status.group(1));
            }
            int durationMs = elapsedMillis(startedAt);
            boolean success = responseStatus == expectedStatusCode;
            String message = success ? null
                    : "Expected HTTP " + expectedStatusCode + " but received HTTP " + responseStatus;
            return new Result(success, responseStatus, durationMs, message);
        } catch (IOException | RuntimeException e) {
            return new Result(false, responseStatus, elapsedMillis(startedAt), safeReason(e));
        }
    }

    public URI validateUrl(String value) {
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || host == null || host.isBlank() || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("only public HTTP/HTTPS URLs without credentials or fragments are allowed");
            }
            if (host.startsWith("[") || host.contains(":")) {
                throw new IllegalArgumentException("IPv6 literal targets are not allowed");
            }
            if (host.equalsIgnoreCase("localhost") || host.toLowerCase(Locale.ROOT).endsWith(".localhost")
                    || host.toLowerCase(Locale.ROOT).endsWith(".local")
                    || host.toLowerCase(Locale.ROOT).endsWith(".internal")
                    || host.toLowerCase(Locale.ROOT).endsWith(".home.arpa")) {
                throw new IllegalArgumentException("local target names are not allowed");
            }
            ensurePublicAddresses(host);
            return uri;
        } catch (URISyntaxException | java.net.UnknownHostException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "uptime target URL is invalid or unavailable");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private InetAddress resolvePublicAddress(String host) throws java.net.UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new java.net.UnknownHostException();
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) throw new IllegalArgumentException("target host resolved to a non-public address");
        }
        return addresses[Math.floorMod(System.nanoTime(), addresses.length)];
    }

    private void ensurePublicAddresses(String host) throws java.net.UnknownHostException {
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (!isPublicAddress(address)) throw new IllegalArgumentException("target host resolves to a non-public address");
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return !(first == 0 || first == 10 || (first == 100 && second >= 64 && second <= 127)
                    || first == 127 || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 0 && (third == 0 || third == 2))
                    || (first == 192 && second == 88 && third == 99)
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 224);
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if ((first & 0xe0) != 0x20) return false;
            if ((first & 0xfe) == 0xfc) return false;
            if (first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d
                    && (bytes[3] & 0xff) == 0xb8) return false;
            if (first == 0x00 && second == 0x64 && (bytes[2] & 0xff) == 0xff
                    && (bytes[3] & 0xff) == 0x9b) return false;
            if (isIpv4Mapped(bytes)) {
                try {
                    return isPublicAddress(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
                } catch (java.net.UnknownHostException ignored) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isIpv4Mapped(byte[] address) {
        for (int index = 0; index < 10; index++) if (address[index] != 0) return false;
        return address[10] == (byte) 0xff && address[11] == (byte) 0xff;
    }

    private boolean isIpv4Literal(String host) {
        return host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    }

    private void writeRequest(OutputStream output, URI uri, String host, int port, String method) throws IOException {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String defaultPort = ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80) ? "" : ":" + port;
        String target = method + " " + path + " HTTP/1.1\r\n"
                + "Host: " + host + defaultPort + "\r\n"
                + "User-Agent: MallTiny-Uptime-Monitor/1.0\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n\r\n";
        output.write(target.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private String readStatusLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (line.size() < 8192) {
            int value = input.read();
            if (value < 0 || value == '\n') break;
            if (value != '\r') line.write(value);
        }
        if (line.size() == 8192) throw new IOException("HTTP response status line is too long");
        return line.toString(StandardCharsets.US_ASCII);
    }

    private int elapsedMillis(long startedAt) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L));
    }

    private String safeReason(Exception error) {
        if (error instanceof ResponseStatusException status) return status.getReason();
        if (error instanceof java.net.SocketTimeoutException) return "Request timed out";
        if (error instanceof javax.net.ssl.SSLException) return "TLS handshake failed";
        if (error instanceof java.net.UnknownHostException) return "Target host did not resolve";
        if (error instanceof IllegalArgumentException) return "Target host resolves to a non-public address";
        return "Connection failed";
    }

    public record Result(boolean successful, Integer responseStatus, int durationMs, String error) {}
}
