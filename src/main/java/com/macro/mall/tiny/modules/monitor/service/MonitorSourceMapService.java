package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapResolvedPosition;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapStackFrame;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorSourceMapMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorRelease;
import com.macro.mall.tiny.modules.monitor.model.MonitorSourceMap;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 管理每个版本的私有源码映射文件（Source Map）：上传时保存文件和索引，报错查询时把压缩代码位置还原成原始源码位置。
 */
@Service
@RequiredArgsConstructor
public class MonitorSourceMapService {

    private final MonitorProjectService projectService;
    private final MonitorReleaseService releaseService;
    private final MonitorSourceMapMapper sourceMapMapper;
    private final SourceMapV3Resolver resolver;
    private final SourceMapStackTraceParser stackTraceParser;
    private final MinioClient minioClient;
    private final MinioBucketService bucketService;

    @Value("${monitor.minio.source-map-bucket}")
    private String sourceMapBucket;

    public MonitorSourceMap upload(
            String projectKey,
            String releaseKey,
            String version,
            String environment,
            String bundleFile,
            MultipartFile file) {
        // 版本上传密钥供 CI 发布流程使用，与浏览器事件上报密钥分开。
        MonitorProject project = projectService.validateReleaseKey(projectKey, releaseKey);
        MonitorRelease release = releaseService.require(project.getId(), version, environment);

        try {
            // file 是 Spring 读到的上传文件；文件放在私有对象存储，数据库只记查找它所需的信息。
            byte[] bytes = file.getBytes();
            bucketService.ensureBucket(sourceMapBucket);
            String objectKey = objectKey(projectKey, release, bundleFile);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(sourceMapBucket)
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                            .contentType("application/json")
                            .build()
            );

            MonitorSourceMap sourceMap = sourceMapMapper.selectOne(
                    Wrappers.<MonitorSourceMap>lambdaQuery()
                            .eq(MonitorSourceMap::getReleaseId, release.getId())
                            .eq(MonitorSourceMap::getBundleFile, bundleFile)
                            .last("LIMIT 1")
            );
            if (sourceMap == null) {
                sourceMap = new MonitorSourceMap();
                sourceMap.setProjectId(project.getId());
                sourceMap.setReleaseId(release.getId());
                sourceMap.setBundleFile(bundleFile);
            }
            sourceMap.setObjectKey(objectKey);
            sourceMap.setChecksum(sha256(bytes));

            if (sourceMap.getId() == null) {
                sourceMapMapper.insert(sourceMap);
            } else {
                sourceMapMapper.updateById(sourceMap);
            }

            // 文件和数据库索引都保存成功后，才把这个版本标记为“已上传源码映射文件”。
            releaseService.markSourceMapUploaded(release);
            return sourceMap;
        } catch (Exception e) {
            throw new IllegalStateException("source map upload failed", e);
        }
    }

    public Optional<SourceMapResolvedPosition> resolve(
            String projectKey,
            String releaseKey,
            String version,
            String environment,
            String bundleFile,
            int line,
            int column) {
        MonitorProject project = projectService.validateReleaseKey(projectKey, releaseKey);
        return resolveInternal(project, version, environment, bundleFile, line, column);
    }

    public Optional<SourceMapResolvedPosition> resolveForAdmin(
            MonitorProject project,
            String version,
            String environment,
            String bundleFile,
            int line,
            int column) {
        return resolveInternal(project, version, environment, bundleFile, line, column);
    }

    public List<SourceMapStackFrame> resolveStackForAdmin(
            MonitorProject project,
            String version,
            String environment,
            String stack,
            String fallbackFile,
            Integer fallbackLine,
            Integer fallbackColumn) {
        // 先把浏览器调用堆栈拆成一条条代码位置，再逐条查映射；查不到时保留原来的压缩文件位置。
        MonitorRelease release = releaseService.require(project.getId(), version, environment);
        List<SourceMapStackTraceParser.Frame> frames =
                stackTraceParser.parse(stack, fallbackFile, fallbackLine, fallbackColumn);
        // 多条调用位置可能指向同一个压缩文件；本次请求缓存查找结果和文件内容，避免重复读存储。
        Map<String, Optional<MonitorSourceMap>> sourceMaps = new HashMap<>();
        Map<String, byte[]> sourceMapContents = new HashMap<>();
        List<SourceMapStackFrame> results = new ArrayList<>(frames.size());

        for (SourceMapStackTraceParser.Frame frame : frames) {
            String bundleFile = bundleFile(frame.file());
            Optional<MonitorSourceMap> sourceMap = sourceMaps.computeIfAbsent(bundleFile, name ->
                    Optional.ofNullable(sourceMapMapper.selectOne(
                            Wrappers.<MonitorSourceMap>lambdaQuery()
                                    .eq(MonitorSourceMap::getReleaseId, release.getId())
                                    .eq(MonitorSourceMap::getBundleFile, name)
                                    .last("LIMIT 1")
                    ))
            );
            SourceMapResolvedPosition position = sourceMap
                    .map(map -> resolveFrame(map, sourceMapContents, frame))
                    .orElse(null);
            results.add(new SourceMapStackFrame(
                    frame.index(),
                    frame.function(),
                    frame.raw(),
                    frame.file(),
                    frame.line(),
                    frame.column(),
                    position != null,
                    position == null ? null : position.source(),
                    position == null ? null : position.line(),
                    position == null ? null : position.column(),
                    position == null ? null : position.name(),
                    position == null ? null : position.sourceContent()
            ));
        }
        return results;
    }

    private SourceMapResolvedPosition resolveFrame(
            MonitorSourceMap sourceMap,
            Map<String, byte[]> sourceMapContents,
            SourceMapStackTraceParser.Frame frame) {
        // 首次遇到这个对象路径时才下载，后续相同路径直接使用本次请求已经读到的文件。
        byte[] contents = sourceMapContents.computeIfAbsent(sourceMap.getObjectKey(), key -> readSourceMap(sourceMap));
        return resolver.resolve(contents, frame.line(), frame.column()).orElse(null);
    }

    private byte[] readSourceMap(MonitorSourceMap sourceMap) {
        try (InputStream input = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(sourceMapBucket)
                        .object(sourceMap.getObjectKey())
                        .build())) {
            return input.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("source map resolve failed", e);
        }
    }

    private String bundleFile(String generatedFile) {
        String normalized = generatedFile.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private Optional<SourceMapResolvedPosition> resolveInternal(
            MonitorProject project,
            String version,
            String environment,
            String bundleFile,
            int line,
            int column) {
        // 先限定项目、版本和环境，再按压缩文件名查映射索引，避免拿错版本的文件来还原位置。
        MonitorRelease release = releaseService.require(project.getId(), version, environment);
        MonitorSourceMap sourceMap = sourceMapMapper.selectOne(
                Wrappers.<MonitorSourceMap>lambdaQuery()
                        .eq(MonitorSourceMap::getReleaseId, release.getId())
                        .eq(MonitorSourceMap::getBundleFile, bundleFile)
                        .last("LIMIT 1")
        );
        if (sourceMap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "source map not found");
        }

        try (InputStream input = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(sourceMapBucket)
                        .object(sourceMap.getObjectKey())
                        .build())) {
            return resolver.resolve(input.readAllBytes(), line, column);
        } catch (Exception e) {
            throw new IllegalStateException("source map resolve failed", e);
        }
    }

    private String objectKey(String projectKey, MonitorRelease release, String bundleFile) {
        String safeFile = bundleFile == null ? "bundle.js" : bundleFile
                .replace('\\', '/')
                .replaceAll("^.*/", "")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        String environment = release.getEnvironment() == null ? "production" : release.getEnvironment();
        return projectKey + "/" + environment + "/" + release.getVersion() + "/" + safeFile + ".map";
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
