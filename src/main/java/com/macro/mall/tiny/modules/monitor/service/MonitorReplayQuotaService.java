package com.macro.mall.tiny.modules.monitor.service;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class MonitorReplayQuotaService {

    private static final int PROJECT_LOCK_COUNT = 64;
    private static final String ACQUIRE_PROJECT_LOCK_SQL = "SELECT GET_LOCK(?, ?)";
    private static final String RELEASE_PROJECT_LOCK_SQL = "SELECT RELEASE_LOCK(?)";

    private final MinioClient minioClient;
    private final DataSource dataSource;
    private final ReentrantLock[] projectLocks = createProjectLocks();

    @Value("${monitor.minio.replay-project-quota-bytes:10737418240}")
    private long projectQuotaBytes;

    @Value("${monitor.minio.replay-quota-lock-wait-seconds:30}")
    private int projectLockWaitSeconds;

    public <T> T storeIfWithinQuota(String bucket, String projectKey, long incomingBytes,
                                    QuotaAction<T> action) throws Exception {
        if (projectQuotaBytes <= 0) {
            throw new IllegalStateException("Replay project quota must be positive");
        }
        if (incomingBytes < 0) {
            throw new IllegalArgumentException("Replay object size cannot be negative");
        }

        ReentrantLock lock = projectLocks[Math.floorMod(31 * bucket.hashCode() + projectKey.hashCode(),
                projectLocks.length)];
        lock.lock();
        try {
            return withDatabaseProjectLock(bucket, projectKey, () -> {
                long currentBytes = getProjectObjectBytes(bucket, projectKey);
                if (!isWithinQuota(currentBytes, incomingBytes, projectQuotaBytes)) {
                    throw new MonitorReplayQuotaExceededException(
                            projectKey, currentBytes, incomingBytes, projectQuotaBytes);
                }
                return action.run();
            });
        } finally {
            lock.unlock();
        }
    }

    private <T> T withDatabaseProjectLock(String bucket, String projectKey, QuotaAction<T> action) throws Exception {
        String lockName = projectLockName(bucket, projectKey);
        try (Connection connection = dataSource.getConnection()) {
            try {
                acquireProjectLock(connection, lockName);
            } catch (SQLException e) {
                abortConnection(connection);
                throw e;
            }

            try {
                return action.run();
            } finally {
                try {
                    releaseProjectLock(connection, lockName);
                } catch (SQLException e) {
                    abortConnection(connection);
                    throw e;
                }
            }
        }
    }

    private void acquireProjectLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_PROJECT_LOCK_SQL)) {
            statement.setString(1, lockName);
            statement.setInt(2, projectLockWaitSeconds);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new IllegalStateException("Replay project quota lock acquisition timed out: " + lockName);
                }
            }
        }
    }

    private void releaseProjectLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RELEASE_PROJECT_LOCK_SQL)) {
            statement.setString(1, lockName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new IllegalStateException("Replay project quota lock was not owned at release: " + lockName);
                }
            }
        }
    }

    private void abortConnection(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | AbstractMethodError ignored) {
            // Closing the connection below remains the fallback for drivers without abort support.
        }
    }

    private static String projectLockName(String bucket, String projectKey) {
        try {
            byte[] input = (bucket + '\0' + projectKey).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            return "replay-quota:" + HexFormat.of().formatHex(digest, 0, 25);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static ReentrantLock[] createProjectLocks() {
        ReentrantLock[] locks = new ReentrantLock[PROJECT_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    long getProjectObjectBytes(String bucket, String projectKey) {
        long totalBytes = 0;
        Iterable<Result<Item>> objects = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(projectKey + "/")
                        .recursive(true)
                        .build()
        );
        try {
            for (Result<Item> object : objects) {
                long size = object.get().size();
                if (size > Long.MAX_VALUE - totalBytes) {
                    return Long.MAX_VALUE;
                }
                totalBytes += size;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Replay project quota lookup failed: " + projectKey, e);
        }
        return totalBytes;
    }

    static boolean isWithinQuota(long currentBytes, long incomingBytes, long quotaBytes) {
        return currentBytes >= 0
                && incomingBytes >= 0
                && quotaBytes > 0
                && currentBytes <= quotaBytes
                && incomingBytes <= quotaBytes - currentBytes;
    }

    @FunctionalInterface
    interface QuotaAction<T> {
        T run() throws Exception;
    }
}
