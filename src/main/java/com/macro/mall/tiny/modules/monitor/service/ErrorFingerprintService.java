package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 给相似的前端错误算出相同的“错误指纹”，MySQL 用它把多次发生归到同一个问题（Issue）。
 * 计算时保留错误类型、消息和代码位置，先替换会变化的编号/文件哈希，再生成固定长度摘要；用户和会话编号不参与归并。
 */
@Service
public class ErrorFingerprintService {

    private static final Pattern HASHED_ASSET = Pattern.compile("([.-])[a-fA-F0-9]{8,}([.-])");
    private static final Pattern QUERY_STRING = Pattern.compile("\\?.*$");

    public String generate(MonitorEventEnvelope event) {
        // 用错误类型、消息、文件、行号和调用堆栈组成特征；用户/会话信息只描述“谁遇到”，不决定是不是同一个问题。
        Map<String, Object> data = event.getData();
        String type = value(data, "name");
        String message = normalizeMessage(value(data, "message"));
        String file = normalizeFile(value(data, "file"));
        String line = value(data, "line");
        String stack = normalizeStack(value(data, "stack"));
        // 把特征转成固定长度的 SHA-256 摘要，Issue 表不必用很长的堆栈文本做查找键。
        return sha256(String.join("|", type, message, file, line, stack));
    }

    String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        // 请求编号、用户编号等数字每次可能不同；先替换掉，内容相同的错误才能落到同一个问题里。
        return message
                .replaceAll("\\b[0-9a-fA-F]{16,}\\b", "<id>")
                .replaceAll("\\b\\d{4,}\\b", "<num>")
                .trim();
    }

    String normalizeFile(String file) {
        if (file == null) {
            return "";
        }
        // URL 查询参数和打包文件名中的构建哈希常会变化，去掉它们以免一个错误被误拆成多个问题。
        String withoutQuery = QUERY_STRING.matcher(file).replaceAll("");
        return HASHED_ASSET.matcher(withoutQuery).replaceAll("$1<hash>$2");
    }

    String normalizeStack(String stack) {
        if (stack == null) {
            return "";
        }
        // 只取最靠近报错位置的前 8 层调用记录，既控制输入长度，也保留最有用的定位信息。
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
