package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorSavedExploreQueryRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorSavedExploreQueryMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorSavedExploreQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonitorSavedExploreQueryServiceTest {

    private final MonitorSavedExploreQueryMapper mapper = mock(MonitorSavedExploreQueryMapper.class);
    private final MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
    private final AtomicLong currentAdminId = new AtomicLong();
    private MonitorSavedExploreQueryService service;
    private MonitorProject project;

    @BeforeEach
    void setUp() {
        service = new MonitorSavedExploreQueryService(mapper, accessService, new ObjectMapper());
        project = new MonitorProject();
        project.setId(31L);
        when(accessService.currentAdminId()).thenAnswer(ignored -> currentAdminId.get());
    }

    @Test
    void savedQueriesAreSharedWithinProjectButModificationBelongsToCreator() {
        MonitorSavedExploreQuery ownerQuery = saved(11L, 101L, "owner query");
        MonitorSavedExploreQuery otherQuery = saved(12L, 202L, "other query");
        when(mapper.selectList(any())).thenReturn(List.of(ownerQuery, otherQuery));

        currentAdminId.set(101L);
        var ownerView = service.list(project);
        assertEquals(2, ownerView.size());
        assertTrue(ownerView.get(0).canModify());
        assertFalse(ownerView.get(1).canModify());

        currentAdminId.set(202L);
        var otherView = service.list(project);
        assertEquals(2, otherView.size());
        assertFalse(otherView.get(0).canModify());
        assertTrue(otherView.get(1).canModify());
    }

    @Test
    void onlyCreatorCanUpdateOrDeleteSharedQuery() {
        MonitorSavedExploreQuery saved = saved(12L, 101L, "shared");
        when(mapper.selectOne(any())).thenReturn(saved);
        MonitorSavedExploreQueryRequest request = new MonitorSavedExploreQueryRequest();
        request.setName("renamed");

        currentAdminId.set(202L);
        ResponseStatusException updateDenied = assertThrows(ResponseStatusException.class,
                () -> service.update(project, 12L, request));
        ResponseStatusException deleteDenied = assertThrows(ResponseStatusException.class,
                () -> service.delete(project, 12L));
        assertEquals(403, updateDenied.getStatusCode().value());
        assertEquals(403, deleteDenied.getStatusCode().value());
        verify(mapper, never()).updateById(any(MonitorSavedExploreQuery.class));
        verify(mapper, never()).deleteById(any());

        currentAdminId.set(101L);
        var updated = service.update(project, 12L, request);
        assertEquals("renamed", updated.name());
        service.delete(project, 12L);
        verify(mapper).updateById(saved);
        verify(mapper).deleteById(12L);
    }

    @Test
    void savesLogsGroupingByLevelWithEnvironmentFilter() {
        MonitorSavedExploreQueryRequest request = new MonitorSavedExploreQueryRequest();
        request.setName("production errors");
        request.setType("logs");
        request.setEnvironment("production");
        request.setGroupBy("level");
        request.setAggregation("count");
        request.setField("value");

        var saved = service.create(project, request);

        assertEquals("level", saved.criteria().getGroupBy());
        assertEquals("production", saved.criteria().getEnvironment());
        verify(mapper).insert(any(MonitorSavedExploreQuery.class));
    }

    @Test
    void rejectsUnsupportedLogsGroupingAndWindowsWhenSaving() {
        MonitorSavedExploreQueryRequest request = new MonitorSavedExploreQueryRequest();
        request.setName("logs by url");
        request.setType("logs");
        request.setGroupBy("url");

        ResponseStatusException invalidGroup = assertThrows(ResponseStatusException.class,
                () -> service.create(project, request));
        assertEquals(400, invalidGroup.getStatusCode().value());

        request.setGroupBy("signal");
        request.setHours(720);
        ResponseStatusException invalidWindow = assertThrows(ResponseStatusException.class,
                () -> service.create(project, request));
        assertEquals(400, invalidWindow.getStatusCode().value());
        verify(mapper, never()).insert(any(MonitorSavedExploreQuery.class));
    }

    private MonitorSavedExploreQuery saved(Long id, Long creator, String name) {
        MonitorSavedExploreQuery query = new MonitorSavedExploreQuery();
        query.setId(id);
        query.setProjectId(project.getId());
        query.setCreatedBy(creator);
        query.setName(name);
        query.setCriteriaJson("{\"name\":\"" + name + "\",\"hours\":24}");
        return query;
    }
}
