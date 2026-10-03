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
import java.util.List;
import java.util.Map;

@Service
@Profile("prod")
@RequiredArgsConstructor
public class MonitorReplayIndexRetentionService {

    private final MonitorReplayMapper replayMapper;
    private final MonitorReplayQuotaService quotaService;

    @Value("${monitor.minio.replay-bucket:monitor-replays}")
    private String replayBucket;

    @Value("${monitor.minio.replay-retention-days:30}")
    private int retentionDays;

    @Value("${monitor.minio.replay-index-cleanup-batch-size:500}")
    private int batchSize;

    @Value("${monitor.minio.replay-index-cleanup-max-batches:10}")
    private int maxBatchesPerRun;

    @Scheduled(cron = "${monitor.minio.replay-index-cleanup-cron:0 15 * * * *}")
    public void cleanupExpiredIndexes() throws Exception {
        cleanupExpiredIndexes(Instant.now());
    }

    int cleanupExpiredIndexes(Instant now) throws Exception {
        if (retentionDays <= 0 || batchSize <= 0 || maxBatchesPerRun <= 0) {
            throw new IllegalStateException("Replay index retention and cleanup limits must be positive");
        }

        Date cutoff = Date.from(now.minus(retentionDays, ChronoUnit.DAYS));
        int deleted = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            List<Map<String, Object>> expired = replayMapper.selectExpiredBatch(cutoff, batchSize);
            for (Map<String, Object> replay : expired) {
                String projectKey = (String) replay.get("projectKey");
                String objectKey = (String) replay.get("objectKey");
                if (projectKey == null || objectKey == null) {
                    throw new IllegalStateException("Expired replay is missing its project or object key");
                }

                quotaService.removeObject(replayBucket, projectKey, objectKey);
                deleted += replayMapper.deleteById(((Number) replay.get("id")).longValue());
            }
            if (expired.size() < batchSize) {
                break;
            }
        }
        return deleted;
    }
}
