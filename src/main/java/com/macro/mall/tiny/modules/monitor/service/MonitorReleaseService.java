package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.dto.ReleaseCreateRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReleaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorRelease;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class MonitorReleaseService {

    private final MonitorProjectService projectService;
    private final MonitorReleaseMapper releaseMapper;

    @Transactional
    public MonitorRelease createOrUpdate(
            String projectKey,
            String releaseKey,
            ReleaseCreateRequest request) {
        MonitorProject project = projectService.validateReleaseKey(projectKey, releaseKey);
        String environment = normalizeEnvironment(request.getEnvironment());

        MonitorRelease release = find(project.getId(), request.getVersion(), environment);
        if (release == null) {
            release = new MonitorRelease();
            release.setProjectId(project.getId());
            release.setVersion(request.getVersion());
            release.setEnvironment(environment);
            release.setSourceMapStatus("pending");
        }

        release.setGitCommit(request.getGitCommit());
        release.setBranchName(request.getBranchName());
        release.setBuildTime(toDate(request.getBuildTime()));
        release.setDeployTime(toDate(request.getDeployTime()));

        if (release.getId() == null) {
            releaseMapper.insert(release);
        } else {
            releaseMapper.updateById(release);
        }
        return release;
    }

    public MonitorRelease require(
            Long projectId,
            String version,
            String environment) {
        MonitorRelease release = find(projectId, version, normalizeEnvironment(environment));
        if (release == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor release not found");
        }
        return release;
    }

    public void markSourceMapUploaded(MonitorRelease release) {
        release.setSourceMapStatus("uploaded");
        releaseMapper.updateById(release);
    }

    private MonitorRelease find(Long projectId, String version, String environment) {
        return releaseMapper.selectOne(
                Wrappers.<MonitorRelease>lambdaQuery()
                        .eq(MonitorRelease::getProjectId, projectId)
                        .eq(MonitorRelease::getVersion, version)
                        .eq(MonitorRelease::getEnvironment, environment)
                        .last("LIMIT 1")
        );
    }

    private String normalizeEnvironment(String environment) {
        return environment == null || environment.isBlank() ? "production" : environment;
    }

    private Date toDate(Long epochMillis) {
        return epochMillis == null ? null : new Date(epochMillis);
    }
}
