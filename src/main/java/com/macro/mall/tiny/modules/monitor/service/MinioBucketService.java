package com.macro.mall.tiny.modules.monitor.service;

import io.minio.BucketExistsArgs;
import io.minio.GetBucketLifecycleArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MinioBucketService {

    private static final String REPLAY_RETENTION_RULE_ID = "monitor-replay-expire";

    private final MinioClient minioClient;

    public void ensureBucket(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build()
            );
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucket).build()
                );
            }
        } catch (Exception e) {
            throw new IllegalStateException("minio bucket initialization failed: " + bucket, e);
        }
    }

    public void configureReplayRetention(String bucket, int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("replay retention days must be positive");
        }

        try {
            ensureBucket(bucket);

            List<LifecycleRule> rules = new ArrayList<>();
            try {
                LifecycleConfiguration current = minioClient.getBucketLifecycle(
                        GetBucketLifecycleArgs.builder().bucket(bucket).build()
                );
                if (current != null && current.rules() != null) {
                    rules.addAll(current.rules().stream()
                            .filter(rule -> !REPLAY_RETENTION_RULE_ID.equals(rule.id()))
                            .toList());
                }
            } catch (ErrorResponseException e) {
                if (e.errorResponse() == null ||
                        !"NoSuchLifecycleConfiguration".equals(e.errorResponse().code())) {
                    throw e;
                }
            }

            rules.add(new LifecycleRule(
                    Status.ENABLED,
                    null,
                    new Expiration((java.time.ZonedDateTime) null, retentionDays, null),
                    new RuleFilter(""),
                    REPLAY_RETENTION_RULE_ID,
                    null,
                    null,
                    null
            ));
            minioClient.setBucketLifecycle(
                    SetBucketLifecycleArgs.builder()
                            .bucket(bucket)
                            .config(new LifecycleConfiguration(rules))
                            .build()
            );
        } catch (Exception e) {
            throw new IllegalStateException("replay bucket lifecycle initialization failed: " + bucket, e);
        }
    }
}
