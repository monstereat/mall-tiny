package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
@Profile("prod")
@RequiredArgsConstructor
public class MonitorReplayIndexRetentionService {

    private final MonitorReplayMapper replayMapper;

    @Value("${monitor.minio.replay-retention-days:30}")
    private int retentionDays;

    @Value("${monitor.minio.replay-index-cleanup-batch-size:500}")
    private int batchSize;

    @Value("${monitor.minio.replay-index-cleanup-max-batches:10}")
    private int maxBatchesPerRun;

    @Scheduled(cron = "${monitor.minio.replay-index-cleanup-cron:0 15 * * * *}")
    public void cleanupExpiredIndexes() {
        cleanupExpiredIndexes(Instant.now());
    }

    int cleanupExpiredIndexes(Instant now) {
        if (retentionDays <= 0 || batchSize <= 0 || maxBatchesPerRun <= 0) {
            throw new IllegalStateException("Replay index retention and cleanup limits must be positive");
        }

        Date cutoff = Date.from(now.minus(retentionDays, ChronoUnit.DAYS));
        int deleted = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int batchDeleted = replayMapper.deleteExpiredBatch(cutoff, batchSize);
            deleted += batchDeleted;
            if (batchDeleted < batchSize) {
                break;
            }
        }
        return deleted;
    }
}
