package com.macro.mall.tiny.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("minioReplayBucketHealthIndicator")
@Profile("prod")
public class MinioReplayBucketHealthIndicator implements HealthIndicator {

    private final MinioClient minioClient;
    private final String replayBucket;

    public MinioReplayBucketHealthIndicator(
            MinioClient minioClient,
            @Value("${monitor.minio.replay-bucket:monitor-replays}") String replayBucket) {
        this.minioClient = minioClient;
        this.replayBucket = replayBucket;
    }

    @Override
    public Health health() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(replayBucket).build());
            return exists ? Health.up().build() : Health.down().build();
        } catch (Exception e) {
            return Health.down().build();
        }
    }
}
