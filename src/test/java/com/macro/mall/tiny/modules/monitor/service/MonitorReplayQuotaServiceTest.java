package com.macro.mall.tiny.modules.monitor.service;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorReplayQuotaServiceTest {

    private final MinioClient minioClient = mock(MinioClient.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement lockStatement = mock(PreparedStatement.class);
    private final PreparedStatement releaseStatement = mock(PreparedStatement.class);
    private final PreparedStatement usageSelectStatement = mock(PreparedStatement.class);
    private final PreparedStatement usageInsertStatement = mock(PreparedStatement.class);
    private final PreparedStatement usageReserveStatement = mock(PreparedStatement.class);
    private final ResultSet lockResult = mock(ResultSet.class);
    private final ResultSet releaseResult = mock(ResultSet.class);
    private final ResultSet usageResult = mock(ResultSet.class);
    private final MonitorReplayQuotaService service = new MonitorReplayQuotaService(minioClient, dataSource);

    @BeforeEach
    void setUpLockMocks() throws Exception {
        ReflectionTestUtils.setField(service, "projectLockWaitSeconds", 30);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("GET_LOCK")) return lockStatement;
            if (sql.contains("RELEASE_LOCK")) return releaseStatement;
            if (sql.contains("SELECT used_bytes")) return usageSelectStatement;
            if (sql.startsWith("INSERT")) return usageInsertStatement;
            return usageReserveStatement;
        });
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(releaseStatement.executeQuery()).thenReturn(releaseResult);
        when(usageSelectStatement.executeQuery()).thenReturn(usageResult);
        when(usageInsertStatement.executeUpdate()).thenReturn(1);
        when(usageReserveStatement.executeUpdate()).thenReturn(1);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getInt(1)).thenReturn(1);
        when(lockResult.wasNull()).thenReturn(false);
        when(releaseResult.next()).thenReturn(true);
        when(releaseResult.getInt(1)).thenReturn(1);
        when(releaseResult.wasNull()).thenReturn(false);
        when(usageResult.next()).thenReturn(false);
    }

    @Test
    void sumsExistingProjectObjectsAndRejectsAnUploadThatWouldExceedQuota() throws Exception {
        setQuota(100L);
        mockProjectObjects(80L);

        assertThrows(MonitorReplayQuotaExceededException.class,
                () -> service.storeIfWithinQuota("monitor-replays", "project-a", 21L, () -> null));
        verifyPrefix("project-a/");
        org.mockito.ArgumentCaptor<String> lockName = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(lockStatement).setString(org.mockito.ArgumentMatchers.eq(1), lockName.capture());
        verify(lockStatement).setInt(2, 30);
        verify(releaseStatement).setString(
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(lockName.getValue()));
        verify(releaseStatement).executeQuery();
        assertTrue(lockName.getValue().length() <= 64, "MySQL named locks are limited to 64 characters");
    }

    @Test
    void allowsAnUploadThatExactlyFillsTheProjectQuota() throws Exception {
        setQuota(100L);
        mockProjectObjects(80L);

        assertEquals("stored", service.storeIfWithinQuota("monitor-replays", "project-a", 20L, () -> "stored"));
    }

    @Test
    void readsInitializedUsageWithoutListingObjectsAgain() throws Exception {
        setQuota(100L);
        when(usageResult.next()).thenReturn(false, true);
        when(usageResult.getLong(1)).thenReturn(80L);
        mockProjectObjects(80L);

        service.storeIfWithinQuota("monitor-replays", "project-a", 10L, () -> "first");
        service.storeIfWithinQuota("monitor-replays", "project-a", 10L, () -> "second");

        verify(minioClient).listObjects(any(ListObjectsArgs.class));
    }

    @Test
    void refusesUploadsWhenTheConfiguredQuotaIsNotPositive() {
        setQuota(0L);

        assertThrows(IllegalStateException.class,
                () -> service.storeIfWithinQuota("monitor-replays", "project-a", 1L, () -> null));
    }

    @Test
    void refusesNegativeUploadSizes() {
        setQuota(100L);

        assertThrows(IllegalArgumentException.class,
                () -> service.storeIfWithinQuota("monitor-replays", "project-a", -1L, () -> null));
    }

    @Test
    void failsClosedWhenTheDatabaseProjectLockCannotBeAcquired() throws Exception {
        setQuota(100L);
        when(lockResult.getInt(1)).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.storeIfWithinQuota("monitor-replays", "project-a", 1L, () -> "stored"));

        verifyNoInteractions(minioClient);
    }

    @Test
    void releasesTheDatabaseLockWhenTheProtectedUploadActionFails() throws Exception {
        setQuota(100L);
        mockProjectObjects(80L);

        assertThrows(IllegalArgumentException.class,
                () -> service.storeIfWithinQuota("monitor-replays", "project-a", 1L,
                        () -> { throw new IllegalArgumentException("synthetic upload failure"); }));

        verify(releaseStatement).executeQuery();
    }

    @Test
    void abortsTheConnectionWhenReleasingTheDatabaseLockFails() throws Exception {
        setQuota(100L);
        mockProjectObjects(80L);
        when(releaseStatement.executeQuery()).thenThrow(new SQLException("synthetic release failure"));

        assertThrows(SQLException.class,
                () -> service.storeIfWithinQuota("monitor-replays", "project-a", 1L, () -> "stored"));

        verify(connection).abort(any());
    }

    @Test
    void saturatesObjectSizeOverflowAndRejectsAdditionalBytes() throws Exception {
        setQuota(Long.MAX_VALUE);
        mockProjectObjects(Long.MAX_VALUE);

        assertThrows(MonitorReplayQuotaExceededException.class,
                () -> service.storeIfWithinQuota("monitor-replays", "project-a", 1L, () -> null));
    }

    @Test
    void serializesQuotaCheckAndWriteForConcurrentUploadsToOneProject() throws Exception {
        setQuota(100L);
        AtomicLong storedBytes = new AtomicLong(80L);
        CountDownLatch secondQuotaLookupReached = new CountDownLatch(1);
        mockProjectObjects(storedBytes, secondQuotaLookupReached);
        CountDownLatch firstActionEntered = new CountDownLatch(1);
        CountDownLatch allowFirstActionToFinish = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        AtomicBoolean secondActionEntered = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> service.storeIfWithinQuota(
                    "monitor-replays", "project-a", 15L, () -> {
                        firstActionEntered.countDown();
                        if (!allowFirstActionToFinish.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test did not release first upload");
                        }
                        storedBytes.addAndGet(15L);
                        return null;
                    }));

            assertTrue(firstActionEntered.await(10, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> {
                secondAttempted.countDown();
                return service.storeIfWithinQuota("monitor-replays", "project-a", 15L, () -> {
                    secondActionEntered.set(true);
                    storedBytes.addAndGet(15L);
                    return null;
                });
            });

            assertTrue(secondAttempted.await(2, TimeUnit.SECONDS));
            assertFalse(secondQuotaLookupReached.await(150, TimeUnit.MILLISECONDS),
                    "second quota lookup must wait while the first write holds the project lock");
            assertFalse(secondActionEntered.get(), "second write must wait for the first quota transaction");
            allowFirstActionToFinish.countDown();
            first.get(2, TimeUnit.SECONDS);

            ExecutionException rejection = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS));
            assertTrue(rejection.getCause() instanceof MonitorReplayQuotaExceededException);
            assertEquals(95L, storedBytes.get());
            assertFalse(secondActionEntered.get());
        } finally {
            allowFirstActionToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void serializesQuotaCheckAndWriteAcrossSeparateServiceInstances() throws Exception {
        AtomicLong storedBytes = new AtomicLong(80L);
        CountDownLatch secondLockAttempted = new CountDownLatch(1);
        SharedNamedLockDataSource sharedDataSource = new SharedNamedLockDataSource(secondLockAttempted);
        MonitorReplayQuotaService firstService = new MonitorReplayQuotaService(minioClient, sharedDataSource.dataSource());
        MonitorReplayQuotaService secondService = new MonitorReplayQuotaService(minioClient, sharedDataSource.dataSource());
        ReflectionTestUtils.setField(firstService, "projectQuotaBytes", 100L);
        ReflectionTestUtils.setField(secondService, "projectQuotaBytes", 100L);
        ReflectionTestUtils.setField(firstService, "projectLockWaitSeconds", 10);
        ReflectionTestUtils.setField(secondService, "projectLockWaitSeconds", 10);
        mockProjectObjects(storedBytes, null);

        CountDownLatch firstActionEntered = new CountDownLatch(1);
        CountDownLatch allowFirstActionToFinish = new CountDownLatch(1);
        AtomicBoolean secondActionEntered = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> firstService.storeIfWithinQuota(
                    "monitor-replays", "project-multi-instance", 15L, () -> {
                        firstActionEntered.countDown();
                        if (!allowFirstActionToFinish.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test did not release first upload");
                        }
                        storedBytes.addAndGet(15L);
                        return null;
                    }));

            assertTrue(firstActionEntered.await(10, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> secondService.storeIfWithinQuota(
                    "monitor-replays", "project-multi-instance", 15L, () -> {
                        secondActionEntered.set(true);
                        storedBytes.addAndGet(15L);
                        return null;
                    }));

            assertTrue(secondLockAttempted.await(10, TimeUnit.SECONDS),
                    "the second service instance must contend on the shared database lock");
            Thread.sleep(100);
            assertEquals(80L, storedBytes.get(), "the first write is still inside the protected action");
            assertFalse(secondActionEntered.get(), "the second write must wait for the shared lock");
            allowFirstActionToFinish.countDown();
            first.get(10, TimeUnit.SECONDS);

            ExecutionException rejection = assertThrows(ExecutionException.class,
                    () -> second.get(10, TimeUnit.SECONDS));
            assertTrue(rejection.getCause() instanceof MonitorReplayQuotaExceededException);
            assertEquals(95L, storedBytes.get());
            assertFalse(secondActionEntered.get());
        } finally {
            allowFirstActionToFinish.countDown();
            executor.shutdownNow();
        }
    }

    private void setQuota(long bytes) {
        ReflectionTestUtils.setField(service, "projectQuotaBytes", bytes);
    }

    private void mockProjectObjects(long size) throws Exception {
        mockProjectObjects(new AtomicLong(size), null);
    }

    private void mockProjectObjects(AtomicLong size, CountDownLatch secondLookupReached) throws Exception {
        Item object = mock(Item.class);
        when(object.size()).thenAnswer(invocation -> size.get());
        Result<Item> result = mock(Result.class);
        when(result.get()).thenReturn(object);
        AtomicInteger calls = new AtomicInteger();
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2 && secondLookupReached != null) {
                secondLookupReached.countDown();
            }
            return List.of(result);
        });
    }

    private void verifyPrefix(String prefix) {
        org.mockito.ArgumentCaptor<ListObjectsArgs> args =
                org.mockito.ArgumentCaptor.forClass(ListObjectsArgs.class);
        verify(minioClient).listObjects(args.capture());
        assertEquals(prefix, args.getValue().prefix());
        assertTrue(args.getValue().recursive());
    }

    private static final class SharedNamedLockDataSource {
        private final Map<String, ReentrantLock> namedLocks = new ConcurrentHashMap<>();
        private final CountDownLatch lockAttempted;
        private final AtomicInteger lockAttempts = new AtomicInteger();
        private final DataSource dataSource = mock(DataSource.class);

        private SharedNamedLockDataSource(CountDownLatch lockAttempted) throws Exception {
            this.lockAttempted = lockAttempted;
            when(dataSource.getConnection()).thenAnswer(invocation -> newConnection());
        }

        private DataSource dataSource() {
            return dataSource;
        }

        private Connection newConnection() throws Exception {
            Connection connection = mock(Connection.class);
            AtomicReference<ReentrantLock> ownedLock = new AtomicReference<>();
                when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                PreparedStatement statement = mock(PreparedStatement.class);
                AtomicReference<String> lockName = new AtomicReference<>();
                AtomicReference<Integer> waitSeconds = new AtomicReference<>(0);
                org.mockito.Mockito.doAnswer(set -> {
                    lockName.set(set.getArgument(1));
                    return null;
                }).when(statement).setString(org.mockito.ArgumentMatchers.eq(1), any());
                org.mockito.Mockito.doAnswer(set -> {
                    waitSeconds.set(set.getArgument(1));
                    return null;
                }).when(statement).setInt(org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyInt());
                    when(statement.executeQuery()).thenAnswer(execute -> {
                    boolean acquired;
                    if (sql.contains("GET_LOCK")) {
                        if (lockAttempts.incrementAndGet() > 1) {
                            lockAttempted.countDown();
                        }
                        ReentrantLock namedLock = namedLocks.computeIfAbsent(lockName.get(), ignored -> new ReentrantLock());
                        acquired = namedLock.tryLock(waitSeconds.get(), TimeUnit.SECONDS);
                        if (acquired) {
                            ownedLock.set(namedLock);
                        }
                    } else {
                        ReentrantLock namedLock = ownedLock.getAndSet(null);
                        acquired = namedLock != null && namedLock.isHeldByCurrentThread();
                        if (acquired) {
                            namedLock.unlock();
                        }
                    }
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.next()).thenReturn(true);
                    when(resultSet.getInt(1)).thenReturn(acquired ? 1 : 0);
                    when(resultSet.wasNull()).thenReturn(false);
                    return resultSet;
                    });
                    if (sql.contains("SELECT used_bytes")) {
                        ResultSet usage = mock(ResultSet.class);
                        when(usage.next()).thenReturn(false);
                        org.mockito.Mockito.doReturn(usage).when(statement).executeQuery();
                    }
                    when(statement.executeUpdate()).thenReturn(1);
                    return statement;
                });
            return connection;
        }
    }
}
