package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorReplay;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MonitorReplayService {

    private final MonitorReplayMapper replayMapper;
    private final MinioClient minioClient;
    private final MinioBucketService bucketService;
    private final ObjectMapper objectMapper;

    @Value("${monitor.minio.replay-bucket:monitor-replays}")
    private String replayBucket;

    public MonitorReplay store(MonitorProject project, MonitorEventEnvelope event) {
        Map<String, Object> data = event.getData();
        Object replayEvents = data == null ? null : data.get("events");

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(replayEvents == null ? data : replayEvents);
            bucketService.ensureBucket(replayBucket);
            String objectKey = project.getProjectKey() + "/" +
                    safe(event.getRelease()) + "/" +
                    event.getSessionId() + "/" +
                    event.getEventId() + ".json";

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(replayBucket)
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                            .contentType("application/json")
                            .build()
            );

            MonitorReplay replay = new MonitorReplay();
            replay.setProjectId(project.getId());
            replay.setEventId(event.getEventId());
            replay.setSessionId(safe(event.getSessionId()));
            replay.setReleaseVersion(event.getRelease());
            replay.setObjectKey(objectKey);
            replay.setEventCount(replayEvents instanceof Collection<?> c ? c.size() : 0);
            replay.setStartTime(new Date(event.getTimestamp()));
            replay.setEndTime(new Date(event.getTimestamp()));
            replayMapper.insert(replay);
            return replay;
        } catch (Exception e) {
            throw new IllegalStateException("session replay storage failed", e);
        }
    }

    public byte[] load(MonitorReplay replay) {
        try (InputStream input = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(replayBucket)
                        .object(replay.getObjectKey())
                        .build())) {
            return input.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("session replay read failed", e);
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
