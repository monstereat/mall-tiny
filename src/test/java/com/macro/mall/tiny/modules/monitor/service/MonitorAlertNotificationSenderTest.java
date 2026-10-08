package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorAlertNotificationSenderTest {

    private static final String ROBOT = "https://oapi.dingtalk.com/robot/send?access_token=test-token";
    private final MonitorAlertWebhookClient client = mock(MonitorAlertWebhookClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> payload = Map.of("deliveryId", "delivery-1", "project", "test-project",
            "rule", "Error spike", "level", "error", "metric", "error_count", "value", 10,
            "threshold", 5, "status", "firing", "message", "Synthetic alert");

    @Test
    void preservesGenericWebhookPayload() {
        new MonitorAlertNotificationSender(client, ROBOT, "SECtest-secret", "7")
                .send(7L, "https://hooks.example.test/alert", payload);
        verify(client).post("https://hooks.example.test/alert", payload);
        verify(client, never()).postForJson(anyString(), any());
    }

    @Test
    void signsRobotAndFormatsTextWithoutExposingSecret() throws Exception {
        when(client.postForJson(anyString(), any())).thenReturn(mapper.readTree("{\"errcode\":0,\"errmsg\":\"ok\"}"));
        new MonitorAlertNotificationSender(client, ROBOT, "SECtest-secret", "7").send(7L, ROBOT, payload);
        var url = ArgumentCaptor.forClass(String.class);
        var body = ArgumentCaptor.forClass(Map.class);
        verify(client).postForJson(url.capture(), body.capture());
        assertTrue(url.getValue().startsWith(ROBOT + "&timestamp="));
        assertTrue(url.getValue().contains("&sign="));
        assertEquals("text", body.getValue().get("msgtype"));
        String content = ((Map<?, ?>) body.getValue().get("text")).get("content").toString();
        assertTrue(content.contains("test-project"));
        assertTrue(content.contains("delivery-1"));
        assertTrue(content.contains("firing"));
        assertFalse(content.contains("SECtest-secret"));
        assertFalse(content.contains("test-token"));
    }

    @Test
    void matchesIndependentHmacFixture() {
        assertEquals(ROBOT + "&timestamp=1700000000000&sign=LC7hxZ4wslL%2BP7j7gu1hoQ%2BwzByekL%2ByWgiUMekZs0g%3D",
                MonitorAlertNotificationSender.signedUrl(ROBOT, "SECtest-secret", 1700000000000L));
    }

    @Test
    void rejectsConfiguredRobotForAnotherTenant() {
        var sender = new MonitorAlertNotificationSender(client, ROBOT, "SECtest-secret", "7");

        assertThrows(MonitorAlertNotificationSender.DestinationRejectedException.class,
                () -> sender.send(8L, ROBOT, payload));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsEquivalentConfiguredRobotUrlForAnotherTenant() {
        var sender = new MonitorAlertNotificationSender(client, ROBOT, "SECtest-secret", "7");
        String equivalentUrl = "https://OAPI.DINGTALK.COM/robot/send?source=monitor&access_token=test%2Dtoken";

        assertThrows(MonitorAlertNotificationSender.DestinationRejectedException.class,
                () -> sender.send(8L, equivalentUrl, payload));
        verifyNoInteractions(client);
    }

    @Test
    void requiresTenantBindingEvenWhenConfiguredRobotHasNoSecret() {
        var sender = new MonitorAlertNotificationSender(client, ROBOT, "", "");

        assertThrows(MonitorAlertNotificationSender.DestinationRejectedException.class,
                () -> sender.send(7L, ROBOT, payload));
        verifyNoInteractions(client);
    }

    @Test
    void configurationParsingErrorsDoNotExposeUrlOrToken() {
        String malformedUrl = "https://oapi.dingtalk.com/robot/send?access_token=private-token%ZZ";

        var failure = assertThrows(IllegalStateException.class,
                () -> new MonitorAlertNotificationSender(client, malformedUrl, "secret", "7"));

        assertEquals("DingTalk webhook URL configuration is invalid", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private-token"));
    }

    @Test
    void invalidTenantBindingUsesFixedErrorMessage() {
        var failure = assertThrows(IllegalStateException.class,
                () -> new MonitorAlertNotificationSender(client, ROBOT, "secret", "tenant-secret-value"));

        assertEquals("DingTalk tenant binding must be a positive integer", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("tenant-secret-value"));
    }

    @Test
    void doesNotApplySigningSecretToAnotherRobot() throws Exception {
        var sender = new MonitorAlertNotificationSender(client, ROBOT, "SECtest-secret", "7");
        String anotherRobot = "https://oapi.dingtalk.com/robot/send?access_token=another-token";
        when(client.postForJson(eq(anotherRobot), any())).thenReturn(mapper.readTree("{\"errcode\":0}"));
        sender.send(8L, anotherRobot, payload);
        verify(client).postForJson(eq(anotherRobot), any());
    }

    @Test
    void supportsUnsignedRobotAndRejectsBusinessFailure() throws Exception {
        var sender = new MonitorAlertNotificationSender(client, "", "", "");
        when(client.postForJson(eq(ROBOT), any())).thenReturn(mapper.readTree("{\"errcode\":310000,\"errmsg\":\"secret-data\"}"));
        var failure = assertThrows(IllegalStateException.class, () -> sender.send(7L, ROBOT, payload));
        assertFalse(failure.getMessage().contains("secret-data"));
        verify(client).postForJson(eq(ROBOT), any());
    }

    @Test
    void rejectsMalformedSuccessCodes() throws Exception {
        var sender = new MonitorAlertNotificationSender(client, "", "", "");
        for (String response : new String[]{"{}", "{\"errcode\":\"0\"}", "{\"errcode\":0.5}",
                "{\"errcode\":4294967296}", "null"}) {
            when(client.postForJson(eq(ROBOT), any())).thenReturn(mapper.readTree(response));
            assertThrows(IllegalStateException.class, () -> sender.send(7L, ROBOT, payload));
        }
    }

    @Test
    void boundsTextWithoutBreakingUnicode() throws Exception {
        when(client.postForJson(anyString(), any())).thenReturn(mapper.readTree("{\"errcode\":0}"));
        var longPayload = new java.util.HashMap<>(payload);
        longPayload.put("message", "😀".repeat(3000));
        new MonitorAlertNotificationSender(client, "", "", "").send(7L, ROBOT, longPayload);
        var body = ArgumentCaptor.forClass(Map.class);
        verify(client).postForJson(eq(ROBOT), body.capture());
        String content = ((Map<?, ?>) body.getValue().get("text")).get("content").toString();
        assertEquals(1800, content.codePointCount(0, content.length()));
        assertTrue(content.endsWith("…"));
    }

    @Test
    void recognizesOnlyOfficialHttpsRobotEndpoint() {
        assertTrue(MonitorAlertNotificationSender.isDingTalk(ROBOT));
        assertFalse(MonitorAlertNotificationSender.isDingTalk("https://oapi.dingtalk.com.evil.test/robot/send"));
        assertFalse(MonitorAlertNotificationSender.isDingTalk("http://oapi.dingtalk.com/robot/send"));
        assertFalse(MonitorAlertNotificationSender.isDingTalk("https://oapi.dingtalk.com:8443/robot/send"));
    }
}
