package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorLogTagMetadataTest {

    @Test
    void indexesOnlyTagValuesAfterProjectScrubbing() {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setData(new LinkedHashMap<>(Map.of("tags", new LinkedHashMap<>(Map.of(
                "region", "cn-east", "email", "alice@example.com", "apiKey", "secret-value", "owner", "private")))));
        new MonitorEventScrubber().scrub(event, true, false, false, java.util.List.of("owner"));

        String encoded = MonitorLogTagMetadata.encode((Map<?, ?>) event.getData().get("tags"));

        assertNotNull(encoded);
        assertTrue(Pattern.compile(MonitorLogTagMetadata.exactTokenRegex("region", "cn-east"))
                .matcher(encoded).matches());
        assertFalse(encoded.contains("alice@example.com"));
        assertFalse(encoded.contains("secret-value"));
        assertFalse(encoded.contains("private"));
        assertFalse(encoded.contains("ZW1haWw"));
        assertFalse(encoded.contains("YXBpS2V5"));
        assertFalse(encoded.contains("b3duZXI"));
    }

    @Test
    void matchesTagSubsetAndOmitsOversizedOrUnsupportedDimensions() {
        String multiTagValue = MonitorLogTagMetadata.encode(Map.of("region", "cn-east", "tier", "gold"));
        assertTrue(Pattern.compile(MonitorLogTagMetadata.exactTokenRegex("region", "cn-east"))
                .matcher(multiTagValue).matches());
        assertTrue(Pattern.compile(MonitorLogTagMetadata.exactTokenRegex("tier", "gold"))
                .matcher(multiTagValue).matches());

        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("region", "cn-east");
        tags.put("oversized", "x".repeat(129));
        tags.put("bad key", "ignored");
        IntStream.range(0, 22).forEach(index -> tags.put(String.format("tag%02d", index), "v"));

        String encoded = MonitorLogTagMetadata.encode(tags);
        assertNotNull(encoded);
        assertTrue(Pattern.compile(MonitorLogTagMetadata.exactTokenRegex("region", "cn-east"))
                .matcher(encoded).matches());
        assertFalse(Pattern.compile(MonitorLogTagMetadata.exactTokenRegex("region", "cn-west"))
                .matcher(encoded).matches());
        assertFalse(Pattern.compile(MonitorLogTagMetadata.exactTokenRegex("oversized", "x".repeat(129)))
                .matcher(encoded).matches());
        assertFalse(Pattern.compile(MonitorLogTagMetadata.exactTokenRegex("bad key", "ignored"))
                .matcher(encoded).matches());
        assertFalse(Pattern.compile(MonitorLogTagMetadata.exactTokenRegex("tag21", "v"))
                .matcher(encoded).matches());
    }

    @Test
    void returnsNoMetadataWhenAllTagsAreSensitiveOrUnsupported() {
        assertNull(MonitorLogTagMetadata.encode(Map.of("token", "[Filtered]", "object", Map.of("a", 1))));
    }
}
