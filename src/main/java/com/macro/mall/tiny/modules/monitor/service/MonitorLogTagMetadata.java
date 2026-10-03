package com.macro.mall.tiny.modules.monitor.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;

final class MonitorLogTagMetadata {
    private static final int MAX_TAGS = 20;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_VALUE_LENGTH = 128;
    private MonitorLogTagMetadata() { }

    static String encode(Map<?, ?> tags) {
        if (tags == null || tags.isEmpty()) return null;
        String encoded = tags.entrySet().stream()
                .filter(entry -> entry.getKey() instanceof String key && key.matches("[A-Za-z0-9_.-]{1,64}")
                        && entry.getValue() != null
                        && (entry.getValue() instanceof String || entry.getValue() instanceof Number
                        || entry.getValue() instanceof Boolean)
                        && !"[Filtered]".equals(String.valueOf(entry.getValue()))
                        && String.valueOf(entry.getValue()).length() <= MAX_VALUE_LENGTH)
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .limit(MAX_TAGS)
                .map(entry -> base64(String.valueOf(entry.getKey())) + "." + base64(String.valueOf(entry.getValue())))
                .collect(Collectors.joining(","));
        return encoded.isEmpty() ? null : encoded;
    }

    static String exactTokenRegex(String key, String value) {
        return ".*(^|,)" + base64(key) + "\\." + base64(value) + "(,|$).*";
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
