package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MonitorDataScrubbingRequest {
    @NotNull
    private Boolean scrubEmails;
    @NotNull
    private Boolean scrubCreditCards;
    @NotNull
    private Boolean scrubIpAddresses;
    @NotNull
    private Boolean scrubPhoneNumbers;
    @NotNull
    private Boolean scrubChineseIdNumbers;

    @Size(max = 64)
    private List<@jakarta.validation.constraints.Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,63}") String> customSensitiveFields;
}
