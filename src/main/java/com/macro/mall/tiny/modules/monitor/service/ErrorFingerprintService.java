package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ErrorFingerprintService {

    private static final Pattern HASHED_ASSET = Pattern.compile("([.-])[a-fA-F0-9]{8,}([.-])");
    private static final Pattern QUERY_STRING = Pattern.compile("\\?.*$");

    public String generate(MonitorEventEnvelope event) {
        Map<String, Object> data = event.getData();
        String type = value(data, "name");
        String message = normalizeMessage(value(data, "message"));
        String file = normalizeFile(value(data, "file"));
        String line = value(data, "line");
        String stack = normalizeStack(value(data, "stack"));
        return sha256(String.join("|", type, message, file, line, stack));
    }

    String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message
                .replaceAll("\\b[0-9a-fA-F]{16,}\\b", "<id>")
                .replaceAll("\\b\\d{4,}\\b", "<num>")
                .trim();
    }

    String normalizeFile(String file) {
        if (file == null) {
            return "";
        }
        String withoutQuery = QUERY_STRING.matcher(file).replaceAll("");
        return HASHED_ASSET.matcher(withoutQuery).replaceAll("$1<hash>$2");
    }

    String normalizeStack(String stack) {
        if (stack == null) {
            return "";
        }
        return stack.lines()
                .limit(8)
                .map(line -> normalizeFile(line.trim()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String value(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String sha256(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
