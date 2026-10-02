package com.macro.mall.tiny.modules.monitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class MonitorReplayLifecycleInitializer {

    private final MinioBucketService bucketService;

    @Value("${monitor.minio.replay-bucket:monitor-replays}")
    private String replayBucket;

    @Value("${monitor.minio.replay-retention-days:30}")
    private int replayRetentionDays;

    @EventListener(ApplicationReadyEvent.class)
    public void configureReplayRetention() {
        bucketService.configureReplayRetention(replayBucket, replayRetentionDays);
    }
}
