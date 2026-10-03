package com.macro.mall.tiny.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioReplayBucketHealthIndicatorTest {

    private final MinioClient minioClient = mock(MinioClient.class);
    private final MinioReplayBucketHealthIndicator indicator =
            new MinioReplayBucketHealthIndicator(minioClient, "monitor-replays");

    @Test
    void reportsUpWhenConfiguredReplayBucketExists() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        assertEquals(Status.UP, indicator.health().getStatus());
        verify(minioClient).bucketExists(argThat(args -> "monitor-replays".equals(args.bucket())));
    }

    @Test
    void reportsDownWhenConfiguredReplayBucketIsMissing() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void reportsDownWhenMinioIsUnavailable() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new IllegalStateException("unavailable"));

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void reportsDownWhenMinioBucketCheckTimesOut() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new SocketTimeoutException("timeout"));

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }
}
