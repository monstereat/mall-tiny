package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MonitorAlertWebhookClient {

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int MAX_RESPONSE_BODY_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_HEADER_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_LINE_BYTES = 8 * 1024;
    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] ([1-5][0-9]{2})(?:\\s.*)?");
    private final ObjectMapper objectMapper;
    private final HostResolver resolver;
    private final Predicate<InetAddress> publicAddress;

    @Autowired
    public MonitorAlertWebhookClient(ObjectMapper objectMapper) {
        this(objectMapper, InetAddress::getAllByName, MonitorAlertWebhookClient::isPublicAddress);
    }

    MonitorAlertWebhookClient(ObjectMapper objectMapper, HostResolver resolver,
                              Predicate<InetAddress> publicAddress) {
        this.objectMapper = objectMapper;
        this.resolver = resolver;
        this.publicAddress = publicAddress;
    }

    public String normalizeUrl(String value) {
        URI uri = parseUrl(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (looksNumeric(host)) {
            try {
                requirePublicAddresses(resolver.resolve(host));
            } catch (IOException e) {
                throw new IllegalArgumentException("webhook IP address is invalid", e);
            }
        }
        return uri.toASCIIString();
    }

    public String normalizeOptionalUrl(String value) {
        return value == null || value.isBlank() ? null : normalizeUrl(value);
    }

    public void post(String value, Object payload) {
        executePost(value, payload, input -> null);
    }

    public JsonNode postForJson(String value, Object payload) {
        return executePost(value, payload, this::readJsonResponse);
    }

    private <T> T executePost(String value, Object payload, ResponseReader<T> responseReader) {
        URI uri = parseUrl(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        InetAddress address;
        try {
            InetAddress[] addresses = resolver.resolve(host);
            requirePublicAddresses(addresses);
            address = addresses[Math.floorMod(System.nanoTime(), addresses.length)];
        } catch (UnsafeTargetException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("webhook host did not resolve", e);
        }

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("webhook payload serialization failed", e);
        }
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        try (Socket connection = new Socket()) {
            connection.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            connection.setSoTimeout(READ_TIMEOUT_MS);
            Socket requestSocket = connection;
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(connection, host, port, true);
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!looksNumeric(host)) parameters.setServerNames(List.of(new SNIHostName(host)));
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
                requestSocket = ssl;
            }
            writeRequest(requestSocket.getOutputStream(), uri, host, port, body);
            Matcher status = STATUS_LINE.matcher(readStatusLine(requestSocket.getInputStream()));
            if (!status.matches()) throw new IOException("invalid HTTP status line");
            int statusCode = Integer.parseInt(status.group(1));
            if (statusCode >= 300 && statusCode < 400) {
                throw new IllegalStateException("webhook redirect rejected");
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("webhook returned HTTP " + statusCode);
            }
            return responseReader.read(requestSocket.getInputStream());
        } catch (UnsafeTargetException | IllegalStateException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("webhook request failed");
        }
    }

    private JsonNode readJsonResponse(InputStream input) throws IOException {
        ResponseFraming framing = readResponseFraming(input);
        byte[] body = readResponseBody(input, framing);
        try {
            JsonNode json = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (json == null) throw new IllegalStateException("webhook returned empty JSON");
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("webhook returned invalid JSON");
        }
    }

    private ResponseFraming readResponseFraming(InputStream input) throws IOException {
        long headerBytes = 0;
        Long contentLength = null;
        String transferEncoding = null;
        while (true) {
            ResponseLine responseLine = readBoundedLine(input, MAX_RESPONSE_LINE_BYTES);
            String line = responseLine == null ? null : responseLine.value();
            if (line == null) throw new IOException("incomplete webhook response headers");
            headerBytes += responseLine.rawBytes();
            if (headerBytes > MAX_RESPONSE_HEADER_BYTES) {
                throw new IllegalStateException("webhook response headers exceed limit");
            }
            if (line.isEmpty()) break;
            int separator = line.indexOf(':');
            if (separator <= 0) throw new IllegalStateException("invalid webhook response headers");
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (name.equalsIgnoreCase("Content-Length")) {
                if (!value.matches("[0-9]+")) {
                    throw new IllegalStateException("invalid webhook response headers");
                }
                long parsed;
                try {
                    parsed = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("invalid webhook response headers");
                }
                if (contentLength != null && contentLength != parsed) {
                    throw new IllegalStateException("invalid webhook response headers");
                }
                if (parsed > MAX_RESPONSE_BODY_BYTES) {
                    throw new IllegalStateException("webhook response body exceeds 64 KiB");
                }
                contentLength = parsed;
            } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
                if (transferEncoding != null) {
                    throw new IllegalStateException("invalid webhook response headers");
                }
                transferEncoding = value;
            }
        }
        if (contentLength != null && transferEncoding != null) {
            throw new IllegalStateException("invalid webhook response headers");
        }
        boolean chunked = false;
        if (transferEncoding != null) {
            if (!"chunked".equalsIgnoreCase(transferEncoding)) {
                throw new IllegalStateException("unsupported webhook response transfer encoding");
            }
            chunked = true;
        }
        return new ResponseFraming(contentLength, chunked);
    }

    private byte[] readResponseBody(InputStream input, ResponseFraming framing) throws IOException {
        if (framing.contentLength() != null) {
            return readExactly(input, framing.contentLength().intValue());
        }
        if (framing.chunked()) return readChunkedBody(input);
        return readUntilClose(input);
    }

    private byte[] readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            ResponseLine responseLine = readBoundedLine(input, MAX_RESPONSE_LINE_BYTES);
            String line = responseLine == null ? null : responseLine.value();
            if (line == null) throw new IOException("incomplete webhook response body");
            int extension = line.indexOf(';');
            String sizeValue = (extension < 0 ? line : line.substring(0, extension)).trim();
            if (!sizeValue.matches("[0-9A-Fa-f]+")) {
                throw new IllegalStateException("invalid webhook response chunk");
            }
            long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeValue, 16);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("invalid webhook response chunk");
            }
            if (chunkSize == 0) {
                readChunkTrailers(input);
                return body.toByteArray();
            }
            if (chunkSize > MAX_RESPONSE_BODY_BYTES - body.size()) {
                throw new IllegalStateException("webhook response body exceeds 64 KiB");
            }
            body.write(readExactly(input, (int) chunkSize));
            if (input.read() != '\r' || input.read() != '\n') {
                throw new IllegalStateException("invalid webhook response chunk");
            }
        }
    }

    private void readChunkTrailers(InputStream input) throws IOException {
        long trailerBytes = 0;
        while (true) {
            ResponseLine responseLine = readBoundedLine(input, MAX_RESPONSE_LINE_BYTES);
            String line = responseLine == null ? null : responseLine.value();
            if (line == null) throw new IOException("incomplete webhook response trailers");
            trailerBytes += responseLine.rawBytes();
            if (trailerBytes > MAX_RESPONSE_HEADER_BYTES) {
                throw new IllegalStateException("webhook response trailers exceed limit");
            }
            if (line.isEmpty()) return;
            if (line.indexOf(':') <= 0) throw new IllegalStateException("invalid webhook response trailers");
        }
    }

    private byte[] readUntilClose(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        while (true) {
            int remainingWithOverflowCheck = MAX_RESPONSE_BODY_BYTES - body.size() + 1;
            int read = input.read(buffer, 0, Math.min(buffer.length, remainingWithOverflowCheck));
            if (read < 0) return body.toByteArray();
            if (read == 0) continue;
            if (read > MAX_RESPONSE_BODY_BYTES - body.size()) {
                throw new IllegalStateException("webhook response body exceeds 64 KiB");
            }
            body.write(buffer, 0, read);
        }
    }

    private byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) throw new IOException("incomplete webhook response body");
            if (read == 0) continue;
            offset += read;
        }
        return body;
    }

    private ResponseLine readBoundedLine(InputStream input, int maximumLength) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int rawBytes = 0;
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (line.size() == 0) return null;
                throw new IOException("incomplete webhook response line");
            }
            rawBytes++;
            if (rawBytes > maximumLength) {
                throw new IllegalStateException("webhook response line exceeds limit");
            }
            if (value == '\n') return new ResponseLine(line.toString(StandardCharsets.US_ASCII), rawBytes);
            if (value == '\r') continue;
            line.write(value);
        }
    }

    private URI parseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("webhook URL is required");
        try {
            URI uri = new URI(value.trim()).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || host == null || host.isBlank() || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || host.startsWith("[") || host.contains(":")) {
                throw new IllegalArgumentException("webhook URL must be an absolute HTTP or HTTPS URL");
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")
                    || normalizedHost.endsWith(".local") || normalizedHost.endsWith(".internal")
                    || normalizedHost.endsWith(".home.arpa")) {
                throw new UnsafeTargetException("local webhook targets are not allowed");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("webhook URL must be an absolute HTTP or HTTPS URL", e);
        }
    }

    private void requirePublicAddresses(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) throw new UnsafeTargetException("webhook host did not resolve");
        for (InetAddress address : addresses) {
            if (!publicAddress.test(address)) {
                throw new UnsafeTargetException("webhook host resolved to a non-public address");
            }
        }
    }

    private static boolean looksNumeric(String host) {
        return host.matches("[0-9]+(?:\\.[0-9]+)*");
    }

    static boolean isPublicAddress(InetAddress address) {
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
                } catch (IOException ignored) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] address) {
        for (int index = 0; index < 10; index++) if (address[index] != 0) return false;
        return address[10] == (byte) 0xff && address[11] == (byte) 0xff;
    }

    private void writeRequest(OutputStream output, URI uri, String host, int port, byte[] body) throws IOException {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String defaultPort = (("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)) ? "" : ":" + port;
        String headers = "POST " + path + " HTTP/1.1\r\n"
                + "Host: " + host + defaultPort + "\r\n"
                + "User-Agent: MallTiny-Alert-Webhook/1.0\r\n"
                + "Accept: */*\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
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

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    @FunctionalInterface
    private interface ResponseReader<T> {
        T read(InputStream input) throws IOException;
    }

    private record ResponseFraming(Long contentLength, boolean chunked) { }

    private record ResponseLine(String value, int rawBytes) { }

    public static class UnsafeTargetException extends RuntimeException {
        public UnsafeTargetException(String message) {
            super(message);
        }
    }
}
