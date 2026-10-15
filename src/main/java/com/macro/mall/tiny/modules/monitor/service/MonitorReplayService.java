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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * 保存浏览器回放组件收集的一组页面操作，并在 MySQL 登记供问题详情和会话页面查找的基本信息。
 * 较大的原始片段放在对象存储；ClickHouse 事件只记录回放编号，查看详情时再读取原文件。
 */
@Service
@RequiredArgsConstructor
public class MonitorReplayService {

    private final MonitorReplayMapper replayMapper;
    private final MinioClient minioClient;
    private final MinioBucketService bucketService;
    private final MonitorReplayQuotaService quotaService;
    private final ObjectMapper objectMapper;

    @Value("${monitor.minio.replay-bucket:monitor-replays}")
    private String replayBucket;

    public MonitorReplay store(MonitorProject project, MonitorEventEnvelope event) {
        Map<String, Object> data = event.getData();
        Object replayEvents = data == null ? null : data.get("events");

        try {
            // 浏览器回放数据是一组 JSON 事件；先转成字节再压缩，只有文件确实变小时才保存压缩版。
            byte[] json = objectMapper.writeValueAsBytes(replayEvents == null ? data : replayEvents);
            byte[] compressed = gzip(json);
            boolean useCompression = compressed.length < json.length;
            byte[] bytes = useCompression ? compressed : json;
            bucketService.ensureBucket(replayBucket);
            String objectKey = project.getProjectKey() + "/" +
                    safe(event.getRelease()) + "/" +
                    safe(event.getSessionId()) + "/" +
                    safe(event.getEventId()) + (useCompression ? ".json.gz" : ".json");

            MonitorReplay replay = new MonitorReplay();
            replay.setProjectId(project.getId());
            replay.setEventId(event.getEventId());
            replay.setSessionId(safe(event.getSessionId()));
            replay.setReleaseVersion(event.getRelease());
            replay.setObjectKey(objectKey);
            replay.setEventCount(replayEvents instanceof Collection<?> c ? c.size() : 0);
            replay.setStartTime(new Date(event.getTimestamp()));
            replay.setEndTime(new Date(event.getTimestamp()));
            // 先排队检查并预留项目空间，再上传文件和写入索引；超过额度时不会执行里面的上传步骤。
            return quotaService.storeIfWithinQuota(replayBucket, project.getProjectKey(), bytes.length, () -> {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(replayBucket)
                                .object(objectKey)
                                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                                .contentType(useCompression ? "application/gzip" : "application/json")
                                .build()
                );
                replayMapper.insert(replay);
                return replay;
            });
        } catch (MonitorReplayQuotaExceededException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("session replay storage failed", e);
        }
    }

    public byte[] load(MonitorReplay replay) {
        // 根据数据库里的对象路径读取私有文件；旧文件可能没压缩，所以按文件名判断是否需要解压。
        try (InputStream input = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(replayBucket)
                        .object(replay.getObjectKey())
                        .build());
             InputStream decoded = replay.getObjectKey().endsWith(".gz")
                     ? new GZIPInputStream(input)
                     : input) {
            return decoded.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("session replay read failed", e);
        }
    }

    private byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
