package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;

public record SourceMapStackResolveRequest(
        @NotBlank String version,
        String environment,
        String stack,
        String file,
        Integer line,
        Integer column
) {
}
