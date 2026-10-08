package com.macro.mall.tiny.modules.monitor.service;

public class MonitorReplayQuotaExceededException extends RuntimeException {

    public MonitorReplayQuotaExceededException(String projectKey, long currentBytes, long incomingBytes, long quotaBytes) {
        super("Replay storage quota exceeded for project " + projectKey
                + " (current=" + currentBytes
                + ", incoming=" + incomingBytes
                + ", quota=" + quotaBytes + ")");
    }
}
