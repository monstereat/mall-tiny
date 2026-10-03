package com.macro.mall.tiny.modules.monitor.service;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in runtime harness for the existing local Compose stack. Only operates on the supplied
 * quota-it-* prefix and is deliberately kept out of normal unit-test execution.
 */
public final class MonitorReplayQuotaComposeHarness {

    private static final String BUCKET = "monitor-replays";
    private static final long QUOTA_BYTES = 100;

    private MonitorReplayQuotaComposeHarness() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int run(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: <seed|over|upload|size|cleanup> <quota-it-project-key> [worker]");
        }
        String mode = args[0];
        String projectKey = args[1];
        if (!projectKey.startsWith("quota-it-") || projectKey.contains("/")) {
            throw new IllegalArgumentException("Harness only accepts isolated quota-it-* project keys");
        }

        String jdbcUrl = System.getProperty("quota.it.jdbc-url",
                "jdbc:mysql://host.docker.internal:3306/monitor_platform?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String jdbcUser = System.getProperty("quota.it.jdbc-user", "root");
        String jdbcPassword = System.getProperty("quota.it.jdbc-password", "root");
        String minioEndpoint = System.getProperty("quota.it.minio-endpoint", "http://host.docker.internal:9002");
        String minioUser = System.getProperty("quota.it.minio-user", "monitor");
        String minioPassword = System.getProperty("quota.it.minio-password", "monitor123456");

        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, jdbcUser, jdbcPassword);
        try (MinioClient minio = MinioClient.builder()
                .endpoint(minioEndpoint)
                .credentials(minioUser, minioPassword)
                .build()) {
            MonitorReplayQuotaService quota = new MonitorReplayQuotaService(minio, dataSource);
            ReflectionTestUtils.setField(quota, "projectQuotaBytes", QUOTA_BYTES);
            ReflectionTestUtils.setField(quota, "projectLockWaitSeconds", 30);

            switch (mode) {
                case "seed" -> put(minio, projectKey + "/seed.bin", bytes(80));
                case "over" -> {
                    try {
                        quota.storeIfWithinQuota(BUCKET, projectKey, 21, () -> "unexpected");
                        throw new AssertionError("over-quota upload unexpectedly succeeded");
                    } catch (MonitorReplayQuotaExceededException expected) {
                        System.out.println("OVER_QUOTA_REJECTED " + expected.getMessage());
                    }
                }
                case "upload" -> {
                    if (args.length != 3) {
                        throw new IllegalArgumentException("upload mode requires a worker name");
                    }
                    String worker = args[2].replaceAll("[^a-zA-Z0-9._-]", "_");
                    try {
                        quota.storeIfWithinQuota(BUCKET, projectKey, 15, () -> {
                            put(minio, projectKey + "/worker-" + worker + ".bin", bytes(15));
                            Thread.sleep(1_500);
                            return null;
                        });
                        System.out.println("UPLOAD_STORED worker=" + worker);
                    } catch (MonitorReplayQuotaExceededException expected) {
                        System.out.println("UPLOAD_REJECTED worker=" + worker + " " + expected.getMessage());
                        return 10;
                    }
                }
                case "size" -> System.out.println("PROJECT_BYTES=" + quota.getProjectObjectBytes(BUCKET, projectKey));
                case "cleanup" -> cleanup(minio, projectKey);
                default -> throw new IllegalArgumentException("Unsupported harness mode: " + mode);
            }
        }
        return 0;
    }

    private static byte[] bytes(int size) {
        return "x".repeat(size).getBytes(StandardCharsets.UTF_8);
    }

    private static void put(MinioClient minio, String objectKey, byte[] payload) throws Exception {
        minio.putObject(PutObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .stream(new ByteArrayInputStream(payload), payload.length, -1)
                .contentType("application/octet-stream")
                .build());
    }

    private static void cleanup(MinioClient minio, String projectKey) throws Exception {
        List<String> objectKeys = new ArrayList<>();
        Iterable<Result<Item>> objects = minio.listObjects(ListObjectsArgs.builder()
                .bucket(BUCKET)
                .prefix(projectKey + "/")
                .recursive(true)
                .build());
        for (Result<Item> result : objects) {
            objectKeys.add(result.get().objectName());
        }
        for (String objectKey : objectKeys) {
            minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(objectKey).build());
        }
        System.out.println("SYNTHETIC_OBJECTS_REMOVED=" + objectKeys.size());
    }
}
