package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDashboardRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDashboardView;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorDashboardMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorSavedExploreQueryMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDashboard;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorSavedExploreQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MonitorDashboardService {
    private static final int MAX_QUERY_IDS = 20;
    private static final TypeReference<List<Long>> QUERY_IDS_TYPE = new TypeReference<>() { };

    private final MonitorDashboardMapper dashboardMapper;
    private final MonitorSavedExploreQueryMapper savedQueryMapper;
    private final MonitorProjectAccessService accessService;
    private final ObjectMapper objectMapper;

    public List<MonitorDashboardView> list(MonitorProject project, boolean canModify) {
        return dashboardMapper.selectList(Wrappers.<MonitorDashboard>lambdaQuery()
                        .eq(MonitorDashboard::getProjectId, project.getId())
                        .orderByAsc(MonitorDashboard::getName)
                        .orderByAsc(MonitorDashboard::getId))
                .stream().map(dashboard -> view(dashboard, canModify)).toList();
    }

    public MonitorDashboardView get(MonitorProject project, Long id, boolean canModify) {
        return view(require(project, id), canModify);
    }

    public MonitorDashboardView create(MonitorProject project, MonitorDashboardRequest request) {
        validate(project, request);
        MonitorDashboard dashboard = new MonitorDashboard();
        dashboard.setProjectId(project.getId());
        dashboard.setName(request.getName().trim());
        dashboard.setQueryIds(writeQueryIds(request.getQueryIds()));
        dashboard.setCreatedBy(accessService.currentAdminId());
        try {
            dashboardMapper.insert(dashboard);
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
        return view(dashboard, true);
    }

    public MonitorDashboardView update(MonitorProject project, Long id, MonitorDashboardRequest request) {
        validate(project, request);
        MonitorDashboard dashboard = require(project, id);
        dashboard.setName(request.getName().trim());
        dashboard.setQueryIds(writeQueryIds(request.getQueryIds()));
        try {
            dashboardMapper.updateById(dashboard);
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
        return view(dashboard, true);
    }

    public void delete(MonitorProject project, Long id) {
        MonitorDashboard dashboard = require(project, id);
        dashboardMapper.deleteById(dashboard.getId());
    }

    private void validate(MonitorProject project, MonitorDashboardRequest request) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw badRequest("dashboard name is required");
        }
        String name = request.getName().trim();
        if (name.length() > 100) throw badRequest("dashboard name must be at most 100 characters");
        request.setName(name);

        List<Long> queryIds = request.getQueryIds() == null ? List.of() : request.getQueryIds();
        if (queryIds.size() > MAX_QUERY_IDS) throw badRequest("dashboard supports at most 20 Explore queries");
        if (queryIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw badRequest("dashboard Explore query IDs must be positive");
        }
        if (queryIds.stream().distinct().count() != queryIds.size()) {
            throw badRequest("dashboard Explore query IDs must be unique");
        }
        if (!queryIds.isEmpty()) {
            List<MonitorSavedExploreQuery> found = savedQueryMapper.selectList(
                    Wrappers.<MonitorSavedExploreQuery>lambdaQuery()
                            .eq(MonitorSavedExploreQuery::getProjectId, project.getId())
                            .in(MonitorSavedExploreQuery::getId, queryIds));
            if (found.size() != queryIds.size()) {
                throw badRequest("dashboard can only reference Explore queries in this project");
            }
        }
    }

    private MonitorDashboard require(MonitorProject project, Long id) {
        MonitorDashboard dashboard = dashboardMapper.selectOne(Wrappers.<MonitorDashboard>lambdaQuery()
                .eq(MonitorDashboard::getProjectId, project.getId())
                .eq(MonitorDashboard::getId, id)
                .last("LIMIT 1"));
        if (dashboard == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dashboard not found");
        return dashboard;
    }

    private String writeQueryIds(List<Long> queryIds) {
        try {
            return objectMapper.writeValueAsString(queryIds == null ? List.of() : queryIds);
        } catch (Exception e) {
            throw new IllegalStateException("dashboard query ID serialization failed", e);
        }
    }

    private MonitorDashboardView view(MonitorDashboard dashboard, boolean canModify) {
        try {
            return new MonitorDashboardView(dashboard.getId(), dashboard.getName(),
                    objectMapper.readValue(dashboard.getQueryIds(), QUERY_IDS_TYPE), dashboard.getCreatedBy(), canModify);
        } catch (Exception e) {
            throw new IllegalStateException("stored dashboard is invalid", e);
        }
    }

    private static ResponseStatusException duplicateName() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "a dashboard with this name already exists");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
