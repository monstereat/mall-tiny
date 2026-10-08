package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class MonitorAlertNotificationSender {

    private final MonitorAlertWebhookClient webhookClient;
    private final String dingTalkUrl;
    private final String dingTalkSecret;
    private final String dingTalkAccessToken;
    private final Long dingTalkTenantId;

    public MonitorAlertNotificationSender(MonitorAlertWebhookClient webhookClient,
            @Value("${monitor.alert.dingtalk.webhook-url:}") String dingTalkUrl,
            @Value("${monitor.alert.dingtalk.secret:}") String dingTalkSecret,
            @Value("${monitor.alert.dingtalk.tenant-id:}") String dingTalkTenantId) {
        this.webhookClient = webhookClient;
        this.dingTalkUrl = dingTalkUrl == null ? "" : dingTalkUrl.trim();
        this.dingTalkSecret = dingTalkSecret == null ? "" : dingTalkSecret.trim();
        this.dingTalkAccessToken = configuredAccessToken(this.dingTalkUrl);
        this.dingTalkTenantId = parseTenantId(dingTalkTenantId);
    }

    public void send(Long tenantId, String destination, Map<String, Object> payload) {
        URI uri = parseDestination(destination);
        if (isConfiguredRobot(uri)) {
            if (!isDingTalk(destination) || hasSigningParameters(uri)
                    || dingTalkTenantId == null || !dingTalkTenantId.equals(tenantId)) {
                throw new DestinationRejectedException();
            }
            String url = dingTalkSecret.isEmpty() ? destination
                    : signedUrl(destination, dingTalkSecret, System.currentTimeMillis());
            sendDingTalk(url, payload);
            return;
        }
        if (!isDingTalk(destination)) {
            webhookClient.post(destination, payload);
            return;
        }
        sendDingTalk(destination, payload);
    }

    private void sendDingTalk(String url, Map<String, Object> payload) {
        JsonNode response = webhookClient.postForJson(url,
                Map.of("msgtype", "text", "text", Map.of("content", text(payload))));
        JsonNode code = response == null ? null : response.get("errcode");
        if (code == null || !code.isIntegralNumber() || !code.canConvertToInt() || code.intValue() != 0) {
            throw new IllegalStateException("DingTalk rejected the notification");
        }
    }

    static boolean isDingTalk(String destination) {
        return isDingTalk(parseDestination(destination));
    }

    private static boolean isDingTalk(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && "oapi.dingtalk.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && "/robot/send".equals(uri.getRawPath())
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
    }

    private boolean isConfiguredRobot(URI destination) {
        if (dingTalkUrl.isEmpty() || !isOfficialRobotPath(destination)) return false;
        List<QueryParameter> parameters;
        try {
            parameters = queryParameters(destination);
        } catch (IllegalArgumentException e) {
            throw new DestinationRejectedException();
        }
        List<String> accessTokens = parameters.stream()
                .filter(parameter -> "access_token".equalsIgnoreCase(parameter.name()))
                .map(QueryParameter::value).toList();
        if (accessTokens.size() > 1) throw new DestinationRejectedException();
        if (accessTokens.size() != 1 || accessTokens.get(0).isEmpty()) return false;
        if (dingTalkAccessToken == null) {
            return destination.toString().equals(dingTalkUrl);
        }
        return dingTalkAccessToken.equals(accessTokens.get(0));
    }

    private static String configuredAccessToken(String configuredUrl) {
        if (configuredUrl.isEmpty()) return null;
        try {
            URI uri = URI.create(configuredUrl);
            if (!isDingTalk(uri)) return null;
            List<String> accessTokens = queryParameters(uri).stream()
                    .filter(parameter -> "access_token".equalsIgnoreCase(parameter.name()))
                    .map(QueryParameter::value).toList();
            if (accessTokens.size() > 1) throw invalidRobotUrl();
            return accessTokens.size() == 1 && !accessTokens.get(0).isEmpty() ? accessTokens.get(0) : null;
        } catch (IllegalArgumentException e) {
            throw invalidRobotUrl();
        }
    }

    private static URI parseDestination(String destination) {
        try {
            return URI.create(destination);
        } catch (IllegalArgumentException e) {
            throw new DestinationRejectedException();
        }
    }

    private static IllegalStateException invalidRobotUrl() {
        return new IllegalStateException("DingTalk webhook URL configuration is invalid");
    }

    private static boolean isOfficialRobotPath(URI uri) {
        String host = uri.getHost();
        if (host != null && host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return "oapi.dingtalk.com".equalsIgnoreCase(host) && "/robot/send".equals(uri.getPath());
    }

    private static List<QueryParameter> queryParameters(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return List.of();
        List<QueryParameter> parameters = new ArrayList<>();
        for (String pair : query.split("&", -1)) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            parameters.add(new QueryParameter(decodeQueryComponent(name), decodeQueryComponent(value)));
        }
        return parameters;
    }

    private static String decodeQueryComponent(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean hasSigningParameters(URI uri) {
        try {
            return queryParameters(uri).stream().anyMatch(parameter ->
                    "timestamp".equalsIgnoreCase(parameter.name()) || "sign".equalsIgnoreCase(parameter.name()));
        } catch (IllegalArgumentException e) {
            throw new DestinationRejectedException();
        }
    }

    private static Long parseTenantId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return null;
        if (!normalized.matches("[0-9]+")) throw invalidTenantBinding();
        try {
            long tenantId = Long.parseLong(normalized);
            if (tenantId > 0) return tenantId;
        } catch (NumberFormatException ignored) {
            // Use a fixed error so invalid configuration values are not exposed.
        }
        throw invalidTenantBinding();
    }

    private static IllegalStateException invalidTenantBinding() {
        return new IllegalStateException("DingTalk tenant binding must be a positive integer");
    }

    static String signedUrl(String destination, String secret, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signature), StandardCharsets.UTF_8);
            return destination + (parseDestination(destination).getRawQuery() == null ? "?" : "&")
                    + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("DingTalk signing failed");
        }
    }

    private String text(Map<String, Object> payload) {
        String content = "监控告警 [" + payload.get("status") + "]\n"
                + "项目：" + payload.get("project") + "\n"
                + "规则：" + payload.get("rule") + "\n"
                + "级别：" + payload.get("level") + "\n"
                + "指标：" + payload.get("metric") + "\n"
                + "当前值：" + payload.get("value") + "，阈值：" + payload.get("threshold") + "\n"
                + "投递 ID：" + payload.get("deliveryId") + "\n"
                + payload.get("message");
        int length = content.codePointCount(0, content.length());
        return length > 1800 ? content.substring(0, content.offsetByCodePoints(0, 1799)) + "…" : content;
    }

    private record QueryParameter(String name, String value) { }

    public static class DestinationRejectedException extends RuntimeException {
        public DestinationRejectedException() {
            super("DingTalk destination is not authorized for this tenant");
        }
    }
}
