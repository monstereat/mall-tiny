package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorProjectMemberRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMemberMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorProjectMember;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorProjectAccessServiceTest {

    private static final Long PROJECT_ID = 17L;
    private static final Long CURRENT_ADMIN_ID = 42L;

    @Mock
    private MonitorProjectMapper projectMapper;
    @Mock
    private MonitorProjectMemberMapper memberMapper;
    @Mock
    private UmsAdminService adminService;
    @InjectMocks
    private MonitorProjectAccessService accessService;

    @BeforeEach
    void authenticateActiveAdmin() {
        UmsAdmin currentAdmin = new UmsAdmin();
        currentAdmin.setId(CURRENT_ADMIN_ID);
        currentAdmin.setStatus(1);
        when(adminService.getAdminByUsername("tester")).thenReturn(currentAdmin);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", "", List.of())
        );
        MonitorProject project = new MonitorProject();
        project.setId(PROJECT_ID);
        project.setProjectKey("demo");
        project.setStatus(1);
        when(projectMapper.selectOne(any())).thenReturn(project);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void viewerCanReadProjectButCannotWrite() {
        stubMembership("VIEWER");

        assertEquals(PROJECT_ID, accessService.requireProject("demo", false).getId());
        assertForbidden(() -> accessService.requireProject("demo", true));
    }

    @Test
    void memberCanWriteButCannotManageMembers() {
        stubMembership("MEMBER");

        assertEquals(PROJECT_ID, accessService.requireProject("demo", true).getId());
        assertForbidden(() -> accessService.listMembers("demo"));
    }

    @Test
    void ownerCanManageMembers() {
        stubMembership("OWNER");
        MonitorProjectMember owner = membership("OWNER");
        when(memberMapper.selectList(any())).thenReturn(List.of(owner));

        assertEquals(List.of(owner), accessService.listMembers("demo"));
    }

    @Test
    void nonMemberCannotLearnProjectExists() {
        when(memberMapper.selectOne(any())).thenReturn(null);

        assertStatus(HttpStatus.NOT_FOUND, () -> accessService.requireProject("demo", false));
    }

    @Test
    void inactiveAdminCannotAccessProjects() {
        UmsAdmin inactive = new UmsAdmin();
        inactive.setId(CURRENT_ADMIN_ID);
        inactive.setStatus(0);
        when(adminService.getAdminByUsername("tester")).thenReturn(inactive);

        assertStatus(HttpStatus.UNAUTHORIZED, () -> accessService.requireProject("demo", false));
    }

    @Test
    void ownerRoleCannotBeAssignedThroughMemberManagement() {
        stubMembership("OWNER");
        MonitorProjectMemberRequest request = new MonitorProjectMemberRequest();
        request.setAdminId(84L);
        request.setRole("OWNER");

        assertStatus(HttpStatus.BAD_REQUEST, () -> accessService.addOrUpdateMember("demo", request));
    }

    private void stubMembership(String role) {
        when(memberMapper.selectOne(any())).thenReturn(membership(role));
    }

    private MonitorProjectMember membership(String role) {
        MonitorProjectMember membership = new MonitorProjectMember();
        membership.setProjectId(PROJECT_ID);
        membership.setAdminId(CURRENT_ADMIN_ID);
        membership.setRole(role);
        return membership;
    }

    private void assertForbidden(Runnable action) {
        assertStatus(HttpStatus.FORBIDDEN, action);
    }

    private void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, exception.getStatusCode());
    }
}
