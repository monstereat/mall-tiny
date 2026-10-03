package com.macro.mall.tiny.modules.monitor.dto;

public record SourceMapStackFrame(
        int index,
        String function,
        String raw,
        String generatedFile,
        int generatedLine,
        int generatedColumn,
        boolean mapped,
        String source,
        Integer line,
        Integer column,
        String name,
        String sourceContent
) {
}
