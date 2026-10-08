package com.macro.mall.tiny.modules.monitor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** OTLP ExportTraceServiceResponse with no partial_success on full success. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OtlpTraceExportResponse {
    private Object partialSuccess;
}
