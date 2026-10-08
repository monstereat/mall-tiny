package com.macro.mall.tiny.modules.monitor.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
