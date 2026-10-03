package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MonitorEventScrubberTest {

    private final MonitorEventScrubber scrubber = new MonitorEventScrubber();

    @Test
    void removesSensitiveFieldsAndCredentialsFromNestedTelemetryAndUrls() {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventType(MonitorEventType.ERROR);
        event.setPageUrl("https://user:pass@example.com/fail?access_token=abc&feature=checkout#session=fragment-secret");
        event.setDevice(Map.<String, Object>of("api_key", "device-secret", "browser", "Chrome"));
        event.setUserId("customer-42");
        event.setSessionId("session-42");
        event.setData(Map.of(
                "message", "request failed: Authorization: Bearer header-secret",
                "password", "body-secret",
                "breadcrumbs", List.of(Map.of("url", "https://example.com/?sessionid=sid-secret", "value", "safe")),
                "context", Map.of("clientSecret", "nested-secret", "sessionId", "nested-session", "status", 500)
        ));

        scrubber.scrub(event);

        assertEquals("https://[Filtered]@example.com/fail?access_token=[Filtered]&feature=checkout#session=[Filtered]", event.getPageUrl());
        assertEquals(Map.of("api_key", "[Filtered]", "browser", "Chrome"), event.getDevice());
        assertEquals("customer-42", event.getUserId());
        assertEquals("session-42", event.getSessionId());
        assertEquals("request failed: Authorization: [Filtered]", event.getData().get("message"));
        assertEquals("[Filtered]", event.getData().get("password"));
        assertEquals(List.of(Map.of("url", "https://example.com/?sessionid=[Filtered]", "value", "safe")),
                event.getData().get("breadcrumbs"));
        assertEquals(Map.of("clientSecret", "[Filtered]", "sessionId", "[Filtered]", "status", 500),
                event.getData().get("context"));
    }

    @Test
    void preservesOrdinaryTextAndDoesNotRewritePartialSensitiveKeyNames() {
        assertEquals("tokenizer=enabled; Bearer [Filtered]",
                scrubberText("tokenizer=enabled; Bearer abc.def_123"));
    }

    @Test
    void appliesOptInEmailAndLuhnValidatedCardScrubbing() {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventType(MonitorEventType.ERROR);
        event.setPageUrl("https://example.com/user/alice@example.com");
        event.setData(Map.of(
                "message", "contact alice@example.com; card 4111 1111 1111 1111; invalid 4111 1111 1111 1112",
                "numericValue", 4111111111111111L
        ));

        scrubber.scrub(event, true, true);

        assertEquals("https://example.com/user/[Filtered]", event.getPageUrl());
        assertEquals("contact [Filtered]; card [Filtered]; invalid 4111 1111 1111 1112",
                event.getData().get("message"));
        assertEquals("[Filtered]", event.getData().get("numericValue"));
    }

    @Test
    void leavesOptionalPiiAloneWhenProjectHasNotEnabledIt() {
        assertEquals("alice@example.com 4111 1111 1111 1111 from 192.0.2.15 and 2001:db8::1",
                scrubberText("alice@example.com 4111 1111 1111 1111 from 192.0.2.15 and 2001:db8::1"));
    }

    @Test
    void scrubsValidIpv4AndIpv6AddressesWithoutChangingInvalidText() {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventType(MonitorEventType.ERROR);
        event.setPageUrl("https://192.0.2.15/error?remote=2001:db8::1");
        event.setData(Map.of("message", "client 203.0.113.8; ipv6 fd12:3456:789a::1; invalid 999.1.1.1"));

        scrubber.scrub(event, false, false, true);

        assertEquals("https://[Filtered]/error?remote=[Filtered]", event.getPageUrl());
        assertEquals("client [Filtered]; ipv6 [Filtered]; invalid 999.1.1.1", event.getData().get("message"));
    }

    @Test
    void recursivelyScrubsConfiguredFieldNamesWithStableKeyNormalization() {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventType(MonitorEventType.ERROR);
        event.setDevice(Map.of("phone-number", "device-phone"));
        event.setData(Map.of(
                "NATIONAL_ID", "top-level-id",
                "context", Map.of("nationalId", "nested-id", "phone_number", "nested-phone"),
                "items", List.of(Map.of("PHONE_NUMBER", "array-phone", "phoneNumberHint", "keep-me"))
        ));

        scrubber.scrub(event, false, false, false, List.of("national-id", "phoneNumber"));

        assertEquals(Map.of("phone-number", "[Filtered]"), event.getDevice());
        assertEquals(Map.of(
                "NATIONAL_ID", "[Filtered]",
                "context", Map.of("nationalId", "[Filtered]", "phone_number", "[Filtered]"),
                "items", List.of(Map.of("PHONE_NUMBER", "[Filtered]", "phoneNumberHint", "keep-me"))
        ), event.getData());
    }

    @Test
    void scrubsOnlyValidMainlandMobileNumbersAndChineseIdsAcrossNestedEventFields() {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setPageUrl("https://example.com/contact/13800138000/phone/+8613812345678?id=11010519491231002X");
        event.setDevice(Map.of("phone", "13912345678", "note", "account 213800138000"));
        event.setData(Map.of(
                "message", "phone 13800138000 id 11010519491231002X wrong-check 110105194912310020 invalid-date 11010519990229002X",
                "breadcrumbs", List.of(Map.of("url", "https://example.com/13800138000", "id", "11010519491231002X")),
                "longNumber", "113800138000"
        ));

        scrubber.scrub(event, false, false, false, true, true, List.of());

        assertEquals("https://example.com/contact/[Filtered]/phone/[Filtered]?id=[Filtered]", event.getPageUrl());
        assertEquals(Map.of("phone", "[Filtered]", "note", "account 213800138000"), event.getDevice());
        assertEquals("phone [Filtered] id [Filtered] wrong-check 110105194912310020 invalid-date 11010519990229002X",
                event.getData().get("message"));
        assertEquals(List.of(Map.of("url", "https://example.com/[Filtered]", "id", "[Filtered]")),
                event.getData().get("breadcrumbs"));
        assertEquals("113800138000", event.getData().get("longNumber"));
    }

    @Test
    void leavesPhoneAndChineseIdNumbersUnchangedWhenSettingsAreDisabled() {
        String value = "13800138000 11010519491231002X";
        assertEquals(value, scrubberText(value));
    }

    private String scrubberText(String value) {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventType(MonitorEventType.ERROR);
        event.setData(Map.of("message", value));
        scrubber.scrub(event);
        return String.valueOf(event.getData().get("message"));
    }
}
