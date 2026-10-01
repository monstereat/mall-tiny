package com.macro.mall.tiny.modules.monitor.dto;

public record SourceMapResolvedPosition(
        String source,
        int line,
        int column,
        String name,
        String sourceContent
) {
}
