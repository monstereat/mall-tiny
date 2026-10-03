package com.macro.mall.tiny.modules.monitor.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared normalization for project-configured sensitive object keys. */
public final class MonitorSensitiveFieldNames {

    public static final int MAX_FIELDS = 64;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    private MonitorSensitiveFieldNames() {
    }

    public static List<String> validateAndNormalize(List<String> fields) {
        if (fields == null || fields.isEmpty()) return List.of();
        if (fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("At most " + MAX_FIELDS + " sensitive field names are allowed");
        }
        List<String> result = new ArrayList<>(fields.size());
        Set<String> normalized = new LinkedHashSet<>();
        for (String field : fields) {
            if (field == null || !VALID_NAME.matcher(field).matches()) {
                throw new IllegalArgumentException("Sensitive field names must match [A-Za-z][A-Za-z0-9_-]{0,63}");
            }
            if (!normalized.add(normalize(field))) {
                throw new IllegalArgumentException("Sensitive field names must be unique after normalization");
            }
            result.add(field);
        }
        return List.copyOf(result);
    }

    public static String toStorage(List<String> fields) {
        return String.join(",", fields == null ? List.of() : fields);
    }

    public static List<String> fromStorage(String fields) {
        if (fields == null || fields.isBlank()) return List.of();
        return List.of(fields.split(",", -1));
    }

    public static Set<String> normalizedSet(List<String> fields) {
        if (fields == null || fields.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        fields.forEach(field -> {
            if (field != null && !field.isBlank()) result.add(normalize(field));
        });
        return Set.copyOf(result);
    }

    public static String normalize(String field) {
        return field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
