package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDashboardRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorDashboardMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorSavedExploreQueryMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDashboard;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorSavedExploreQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonitorDashboardServiceTest {
    private final MonitorDashboardMapper dashboardMapper = mock(MonitorDashboardMapper.class);
    private final MonitorSavedExploreQueryMapper savedQueryMapper = mock(MonitorSavedExploreQueryMapper.class);
    private final MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
    private final MonitorDashboardService service = new MonitorDashboardService(
            dashboardMapper, savedQueryMapper, accessService, new ObjectMapper());
    private final MonitorProject project = project(31L);

    @BeforeEach
    void setUp() {
        when(accessService.currentAdminId()).thenReturn(88L);
        when(savedQueryMapper.selectList(any())).thenAnswer(invocation -> List.of(
                savedQuery(11L, project.getId()), savedQuery(12L, project.getId())));
    }

    @Test
    void createsProjectSharedDashboardWithOrderedQueryIds() throws Exception {
        MonitorDashboardRequest request = request("Errors", List.of(12L, 11L));
        var view = service.create(project, request);

        assertEquals("Errors", view.name());
        assertEquals(List.of(12L, 11L), view.queryIds());
        assertEquals(88L, view.createdBy());
        assertTrue(view.canModify());
        verify(savedQueryMapper).selectList(any());
        verify(dashboardMapper).insert(org.mockito.ArgumentMatchers.<MonitorDashboard>argThat(dashboard -> dashboard.getProjectId().equals(31L)
                && dashboard.getName().equals("Errors") && dashboard.getQueryIds().equals("[12,11]")));
    }

    @Test
    void rejectsCrossProjectExploreQueryReferences() {
        when(savedQueryMapper.selectList(any())).thenReturn(List.of(savedQuery(11L, project.getId())));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(project, request("Invalid", List.of(11L, 99L))));

        assertEquals(400, error.getStatusCode().value());
        verify(dashboardMapper, never()).insert(any(MonitorDashboard.class));
    }

    @Test
    void rejectsMoreThanTwentyOrDuplicateWidgetIds() {
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList();
        ResponseStatusException countError = assertThrows(ResponseStatusException.class,
                () -> service.create(project, request("Too many", tooMany)));
        ResponseStatusException duplicateError = assertThrows(ResponseStatusException.class,
                () -> service.create(project, request("Duplicate", List.of(11L, 11L))));

        assertEquals(400, countError.getStatusCode().value());
        assertEquals(400, duplicateError.getStatusCode().value());
        verifyNoInteractions(savedQueryMapper);
        verify(dashboardMapper, never()).insert(any(MonitorDashboard.class));
    }

    @Test
    void scopesDashboardLookupToProjectAndAllowsProjectWritersToUpdate() {
        when(savedQueryMapper.selectList(any())).thenReturn(List.of(savedQuery(12L, project.getId())));
        AtomicInteger lookupCount = new AtomicInteger();
        when(dashboardMapper.selectOne(any())).thenAnswer(invocation -> lookupCount.getAndIncrement() == 0
                ? dashboard(9L, 31L, 101L, "Shared", "[11]") : null);

        var updated = service.update(project, 9L, request("Renamed", List.of(12L)));

        assertEquals("Renamed", updated.name());
        assertEquals(List.of(12L), updated.queryIds());
        assertTrue(updated.canModify());
        verify(dashboardMapper).updateById(org.mockito.ArgumentMatchers.<MonitorDashboard>argThat(item -> item.getCreatedBy().equals(101L)
                && item.getName().equals("Renamed")));

        MonitorProject otherProject = project(32L);
        when(dashboardMapper.selectOne(any())).thenReturn(null);
        ResponseStatusException isolated = assertThrows(ResponseStatusException.class,
                () -> service.get(otherProject, 9L, false));
        assertEquals(404, isolated.getStatusCode().value());
    }

    @Test
    void mapsDuplicateProjectDashboardNameToConflict() {
        doThrow(new DuplicateKeyException("duplicate")).when(dashboardMapper).insert(any(MonitorDashboard.class));

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> service.create(project, request("Already used", List.of())));

        assertEquals(409, conflict.getStatusCode().value());
    }

    private static MonitorDashboardRequest request(String name, List<Long> queryIds) {
        MonitorDashboardRequest request = new MonitorDashboardRequest();
        request.setName(name);
        request.setQueryIds(queryIds);
        return request;
    }

    private static MonitorProject project(Long id) {
        MonitorProject project = new MonitorProject();
        project.setId(id);
        return project;
    }

    private static MonitorSavedExploreQuery savedQuery(Long id, Long projectId) {
        MonitorSavedExploreQuery query = new MonitorSavedExploreQuery();
        query.setId(id);
        query.setProjectId(projectId);
        return query;
    }

    private static MonitorDashboard dashboard(Long id, Long projectId, Long createdBy, String name, String queryIds) {
        MonitorDashboard dashboard = new MonitorDashboard();
        dashboard.setId(id);
        dashboard.setProjectId(projectId);
        dashboard.setCreatedBy(createdBy);
        dashboard.setName(name);
        dashboard.setQueryIds(queryIds);
        return dashboard;
    }
}
