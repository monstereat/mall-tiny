package com.macro.mall.tiny.modules.monitor.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把浏览器报错时的调用堆栈文字解析成“文件、行号、列号”，交给源码映射服务还原位置。
 * 每一条有效的调用位置都会保留下来；整段文字都无法识别时，再尝试使用事件本身提供的文件和行号。
 */
@Component
public class SourceMapStackTraceParser {

    private static final Pattern V8_FRAME = Pattern.compile(
            "^\\s*at\\s+(?:(.*?)\\s+\\()?(.+?):(\\d+):(\\d+)\\)?\\s*$"
    );
    private static final Pattern GECKO_FRAME = Pattern.compile(
            "^\\s*(.*?)@(.+?):(\\d+):(\\d+)\\s*$"
    );
    private static final Pattern LOCATION_ONLY = Pattern.compile(
            "^\\s*(.+?):(\\d+):(\\d+)\\s*$"
    );

    public record Frame(int index, String function, String raw, String file, int line, int column) {
    }

    public List<Frame> parse(String stack, String fallbackFile, Integer fallbackLine, Integer fallbackColumn) {
        // 堆栈通常一行一个调用位置；只保留能读出文件和行列号的行，忽略浏览器内部或格式错误的行。
        List<Frame> frames = new ArrayList<>();
        if (StringUtils.hasText(stack)) {
            for (String raw : stack.split("\\R")) {
                Frame frame = parseLine(raw, frames.size());
                if (frame != null) {
                    frames.add(frame);
                }
            }
        }

        if (frames.isEmpty() && StringUtils.hasText(fallbackFile)
                && fallbackLine != null && fallbackLine > 0) {
            // 有些错误没有调用堆栈；这时用事件自带的文件名和行列号作为唯一的备用位置。
            String normalizedFile = fallbackFile.trim().replaceAll("[?#].*$", "");
            frames.add(new Frame(
                    0,
                    null,
                    fallbackFile + ":" + fallbackLine + ":" + Math.max(1, fallbackColumn == null ? 1 : fallbackColumn),
                    normalizedFile,
                    fallbackLine,
                    Math.max(1, fallbackColumn == null ? 1 : fallbackColumn)
            ));
        }
        return frames;
    }

    private Frame parseLine(String raw, int index) {
        // 依次尝试 Chrome 常见格式、Firefox 常见格式和单独的文件位置。
        Matcher matcher = V8_FRAME.matcher(raw);
        if (matcher.matches()) {
            return frame(index, matcher.group(1), raw, matcher.group(2), matcher.group(3), matcher.group(4));
        }

        matcher = GECKO_FRAME.matcher(raw);
        if (matcher.matches()) {
            return frame(index, matcher.group(1), raw, matcher.group(2), matcher.group(3), matcher.group(4));
        }

        matcher = LOCATION_ONLY.matcher(raw);
        if (matcher.matches()) {
            return frame(index, null, raw, matcher.group(1), matcher.group(2), matcher.group(3));
        }
        return null;
    }

    private Frame frame(int index, String function, String raw, String file, String line, String column) {
        // 去掉 URL 问号和井号后面的部分；无效行列号及浏览器内部位置不用于查映射。
        String normalizedFile = file.trim().replaceAll("[?#].*$", "");
        if (!StringUtils.hasText(normalizedFile) || "native".equals(normalizedFile)
                || normalizedFile.startsWith("<")) {
            return null;
        }
        try {
            int parsedLine = Integer.parseInt(line);
            int parsedColumn = Integer.parseInt(column);
            if (parsedLine < 1 || parsedColumn < 0) {
                return null;
            }
            return new Frame(index, StringUtils.hasText(function) ? function.trim() : null,
                    raw.trim(), normalizedFile, parsedLine, Math.max(1, parsedColumn));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
