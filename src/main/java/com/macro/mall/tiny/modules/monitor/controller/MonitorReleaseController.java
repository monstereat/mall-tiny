package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.ReleaseCreateRequest;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapResolvedPosition;
import com.macro.mall.tiny.modules.monitor.model.MonitorRelease;
import com.macro.mall.tiny.modules.monitor.model.MonitorSourceMap;
import com.macro.mall.tiny.modules.monitor.service.MonitorReleaseService;
import com.macro.mall.tiny.modules.monitor.service.MonitorSourceMapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/releases")
@RequiredArgsConstructor
public class MonitorReleaseController {

    private final MonitorReleaseService releaseService;
    private final MonitorSourceMapService sourceMapService;

    @PostMapping("/{projectKey}")
    public CommonResult<MonitorRelease> createRelease(
            @RequestHeader("X-Release-Key") String releaseKey,
            @PathVariable String projectKey,
            @Valid @RequestBody ReleaseCreateRequest request) {
        return CommonResult.success(
                releaseService.createOrUpdate(projectKey, releaseKey, request)
        );
    }

    @PostMapping("/{projectKey}/{version}/sourcemaps")
    public CommonResult<Map<String, Object>> uploadSourceMap(
            @RequestHeader("X-Release-Key") String releaseKey,
            @PathVariable String projectKey,
            @PathVariable String version,
            @RequestParam(defaultValue = "production") String environment,
            @RequestParam String bundleFile,
            @RequestPart("file") MultipartFile file) {
        MonitorSourceMap sourceMap = sourceMapService.upload(
                projectKey,
                releaseKey,
                version,
                environment,
                bundleFile,
                file
        );
        return CommonResult.success(Map.of(
                "id", sourceMap.getId(),
                "bundleFile", sourceMap.getBundleFile(),
                "checksum", sourceMap.getChecksum()
        ));
    }

    @GetMapping("/{projectKey}/{version}/resolve")
    public CommonResult<SourceMapResolvedPosition> resolve(
            @RequestHeader("X-Release-Key") String releaseKey,
            @PathVariable String projectKey,
            @PathVariable String version,
            @RequestParam(defaultValue = "production") String environment,
            @RequestParam String bundleFile,
            @RequestParam int line,
            @RequestParam int column) {
        SourceMapResolvedPosition position = sourceMapService.resolve(
                        projectKey,
                        releaseKey,
                        version,
                        environment,
                        bundleFile,
                        line,
                        column
                )
                .orElse(null);
        return CommonResult.success(position);
    }
}
