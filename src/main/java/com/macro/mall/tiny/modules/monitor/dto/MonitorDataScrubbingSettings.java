package com.macro.mall.tiny.modules.monitor.dto;

public record MonitorDataScrubbingSettings(boolean scrubEmails, boolean scrubCreditCards,
                                           boolean scrubIpAddresses, boolean scrubPhoneNumbers,
                                           boolean scrubChineseIdNumbers, java.util.List<String> customSensitiveFields,
                                           boolean canModify) {
}
