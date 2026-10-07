package com.macro.mall.tiny.modules.monitor.service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.Result;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class MonitorReplayQuotaService {

    private static final int PROJECT_LOCK_COUNT = 64;
    private static final int OBJECT_LIST_PAGE_SIZE = 1_000;
    private static final String OBJECT_SCAN_TIMER = "monitor.replay.quota.object.scan";
    private static final String OBJECT_SCAN_OBJECTS = "monitor.replay.quota.object.scan.objects";
    private static final String OBJECT_SCAN_BYTES = "monitor.replay.quota.object.scan.bytes";
    private static final String OBJECT_SCAN_ESTIMATED_PAGES = "monitor.replay.quota.object.scan.estimated_pages";
    private static final String LOCAL_PROJECT_LOCK_WAIT_TIMER = "monitor.replay.quota.local.lock.wait";
    private static final String PROJECT_LOCK_WAIT_TIMER = "monitor.replay.quota.lock.wait";
    private static final String ACQUIRE_PROJECT_LOCK_SQL = "SELECT GET_LOCK(?, ?)";
    private static final String RELEASE_PROJECT_LOCK_SQL = "SELECT RELEASE_LOCK(?)";
    private static final String SELECT_USAGE_SQL = "SELECT used_bytes FROM monitor_replay_storage_usage " +
            "WHERE storage_bucket = ? AND project_key = ?";
    private static final String INSERT_USAGE_SQL = "INSERT INTO monitor_replay_storage_usage " +
            "(storage_bucket, project_key, used_bytes, reconciled_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP) " +
            "ON DUPLICATE KEY UPDATE used_bytes = VALUES(used_bytes), reconciled_at = CURRENT_TIMESTAMP";
    private static final String RESERVE_USAGE_SQL = "UPDATE monitor_replay_storage_usage " +
            "SET used_bytes = used_bytes + ?, reconciled_at = CURRENT_TIMESTAMP " +
            "WHERE storage_bucket = ? AND project_key = ?";
    private static final String DECREMENT_USAGE_SQL = "UPDATE monitor_replay_storage_usage " +
            "SET used_bytes = GREATEST(0, used_bytes - ?), reconciled_at = CURRENT_TIMESTAMP " +
            "WHERE storage_bucket = ? AND project_key = ?";

    private final MinioClient minioClient;
    private final DataSource dataSource;
    private final ReentrantLock[] projectLocks = createProjectLocks();
    private volatile MeterRegistry meterRegistry = Metrics.globalRegistry;

    @Value("${monitor.minio.replay-project-quota-bytes:10737418240}")
    private long projectQuotaBytes;

    @Value("${monitor.minio.replay-quota-lock-wait-seconds:30}")
    private int projectLockWaitSeconds;

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        if (meterRegistry != null) {
            this.meterRegistry = meterRegistry;
        }
    }

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
        acquireLocalProjectLock(lock);
        try {
            return withDatabaseProjectLock(bucket, projectKey, connection -> {
                long currentBytes = getOrInitializeUsage(connection, bucket, projectKey);
                if (!isWithinQuota(currentBytes, incomingBytes, projectQuotaBytes)) {
                    throw new MonitorReplayQuotaExceededException(
                            projectKey, currentBytes, incomingBytes, projectQuotaBytes);
                }

                reserveUsage(connection, bucket, projectKey, incomingBytes);
                try {
                    return action.run();
                } catch (Exception | Error failure) {
                    try {
                        setUsage(connection, bucket, projectKey, getProjectObjectBytes(bucket, projectKey));
                    } catch (Exception reconciliationFailure) {
                        failure.addSuppressed(reconciliationFailure);
                    }
                    throw failure;
                }
            });
        } finally {
            lock.unlock();
        }
    }

    public void removeObject(String bucket, String projectKey, String objectKey) throws Exception {
        ReentrantLock lock = projectLocks[Math.floorMod(31 * bucket.hashCode() + projectKey.hashCode(),
                projectLocks.length)];
        acquireLocalProjectLock(lock);
        try {
            withDatabaseProjectLock(bucket, projectKey, connection -> {
                getOrInitializeUsage(connection, bucket, projectKey);
                Long objectBytes = null;
                try {
                    StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                            .bucket(bucket).object(objectKey).build());
                    objectBytes = stat.size();
                } catch (Exception ignored) {
                    // A delete is idempotent; recount after it to repair an expired or missing object.
                }

                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
                if (objectBytes == null) {
                    setUsage(connection, bucket, projectKey, getProjectObjectBytes(bucket, projectKey));
                } else {
                    decrementUsage(connection, bucket, projectKey, objectBytes);
                }
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(cron = "${monitor.minio.replay-quota-reconcile-cron:0 45 3 * * *}")
    public void reconcileKnownProjects() throws Exception {
        List<String[]> projects = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT storage_bucket, project_key FROM monitor_replay_storage_usage");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) projects.add(new String[]{result.getString(1), result.getString(2)});
        }
        for (String[] project : projects) {
            reconcileProject(project[0], project[1]);
        }
    }

    private void reconcileProject(String bucket, String projectKey) throws Exception {
        ReentrantLock lock = projectLocks[Math.floorMod(31 * bucket.hashCode() + projectKey.hashCode(),
                projectLocks.length)];
        acquireLocalProjectLock(lock);
        try {
            withDatabaseProjectLock(bucket, projectKey, connection -> {
                setUsage(connection, bucket, projectKey, getProjectObjectBytes(bucket, projectKey));
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    private <T> T withDatabaseProjectLock(String bucket, String projectKey, LockedAction<T> action) throws Exception {
        String lockName = projectLockName(bucket, projectKey);
        try (Connection connection = dataSource.getConnection()) {
            try {
                acquireProjectLock(connection, lockName);
            } catch (SQLException e) {
                abortConnection(connection);
                throw e;
            }

            try {
                return action.run(connection);
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

    private long getOrInitializeUsage(Connection connection, String bucket, String projectKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_USAGE_SQL)) {
            statement.setString(1, bucket);
            statement.setString(2, projectKey);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getLong(1);
                }
            }
        }

        long actualBytes = getProjectObjectBytes(bucket, projectKey);
        setUsage(connection, bucket, projectKey, actualBytes);
        return actualBytes;
    }

    private void setUsage(Connection connection, String bucket, String projectKey, long usedBytes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_USAGE_SQL)) {
            statement.setString(1, bucket);
            statement.setString(2, projectKey);
            statement.setLong(3, usedBytes);
            statement.executeUpdate();
        }
    }

    private void reserveUsage(Connection connection, String bucket, String projectKey, long incomingBytes)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RESERVE_USAGE_SQL)) {
            statement.setLong(1, incomingBytes);
            statement.setString(2, bucket);
            statement.setString(3, projectKey);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Replay project usage ledger was not initialized: " + projectKey);
            }
        }
    }

    private void decrementUsage(Connection connection, String bucket, String projectKey, long removedBytes)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DECREMENT_USAGE_SQL)) {
            statement.setLong(1, removedBytes);
            statement.setString(2, bucket);
            statement.setString(3, projectKey);
            statement.executeUpdate();
        }
    }

    private void acquireProjectLock(Connection connection, String lockName) throws SQLException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_PROJECT_LOCK_SQL)) {
                statement.setString(1, lockName);
                statement.setInt(2, projectLockWaitSeconds);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                        throw new IllegalStateException("Replay project quota lock acquisition timed out: " + lockName);
                    }
                }
            }
        } finally {
            sample.stop(meterRegistry.timer(PROJECT_LOCK_WAIT_TIMER));
        }
    }

    private void acquireLocalProjectLock(ReentrantLock lock) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            lock.lock();
        } finally {
            sample.stop(meterRegistry.timer(LOCAL_PROJECT_LOCK_WAIT_TIMER));
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
        long objectCount = 0;
        long estimatedPageCount = 1;
        boolean completed = false;
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Iterable<Result<Item>> objects = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(projectKey + "/")
                            .recursive(true)
                            .maxKeys(OBJECT_LIST_PAGE_SIZE)
                            .build()
            );
            for (Result<Item> object : objects) {
                long size = object.get().size();
                if (objectCount < Long.MAX_VALUE) {
                    objectCount++;
                }
                if (size > Long.MAX_VALUE - totalBytes) {
                    totalBytes = Long.MAX_VALUE;
                } else {
                    totalBytes += size;
                }
            }
            estimatedPageCount = Math.max(1, objectCount / OBJECT_LIST_PAGE_SIZE
                    + (objectCount % OBJECT_LIST_PAGE_SIZE == 0 ? 0 : 1));
            completed = true;
            return totalBytes;
        } catch (Exception e) {
            throw new IllegalStateException("Replay project quota lookup failed: " + projectKey, e);
        } finally {
            MeterRegistry registry = meterRegistry;
            if (completed) {
                DistributionSummary.builder(OBJECT_SCAN_OBJECTS).register(registry).record(objectCount);
                DistributionSummary.builder(OBJECT_SCAN_BYTES).register(registry).record(totalBytes);
                DistributionSummary.builder(OBJECT_SCAN_ESTIMATED_PAGES).register(registry).record(estimatedPageCount);
            }
            sample.stop(registry.timer(OBJECT_SCAN_TIMER));
        }
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

    @FunctionalInterface
    private interface LockedAction<T> {
        T run(Connection connection) throws Exception;
    }
}
