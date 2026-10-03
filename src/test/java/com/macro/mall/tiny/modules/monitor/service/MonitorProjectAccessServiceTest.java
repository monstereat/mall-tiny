package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorProjectMemberRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantMemberMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTeamMemberMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorProjectMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantMember;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeam;
import com.macro.mall.tiny.modules.monitor.model.MonitorTeamMember;
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
import static org.mockito.Mockito.lenient;
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
    private MonitorTenantMemberMapper tenantMemberMapper;
    @Mock
    private MonitorTeamMemberMapper teamMemberMapper;
    @Mock
    private MonitorTeamMapper teamMapper;
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
        project.setTenantId(5L);
        project.setTeamId(3L);
        project.setStatus(1);
        lenient().when(projectMapper.selectOne(any())).thenReturn(project);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void viewerCanReadProjectButCannotWrite() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        stubMembership("VIEWER");

        assertEquals(PROJECT_ID, accessService.requireProject("demo", false).getId());
        assertForbidden(() -> accessService.requireProject("demo", true));
    }

    @Test
    void memberCanWriteButCannotManageMembers() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectFixture());
        stubMembership("MEMBER");

        assertEquals(PROJECT_ID, accessService.requireProject("demo", true).getId());
        assertForbidden(() -> accessService.listMembers("demo"));
    }

    @Test
    void ownerCanManageMembers() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectFixture());
        stubMembership("OWNER");
        MonitorProjectMember owner = membership("OWNER");
        when(memberMapper.selectList(any())).thenReturn(List.of(owner));

        assertEquals(List.of(owner), accessService.listMembers("demo"));
    }

    @Test
    void onlyProjectOwnerCanManageDataScrubbingSettings() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        stubMembership("OWNER");
        assertEquals(true, accessService.canManageProject(projectFixture()));

        stubMembership("MEMBER");
        assertEquals(false, accessService.canManageProject(projectFixture()));
    }

    @Test
    void nonMemberCannotLearnProjectExists() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        when(memberMapper.selectOne(any())).thenReturn(null);

        assertStatus(HttpStatus.NOT_FOUND, () -> accessService.requireProject("demo", false));
    }

    @Test
    void projectMemberOutsideTenantCannotAccessProject() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(null);

        assertStatus(HttpStatus.NOT_FOUND, () -> accessService.requireProject("demo", false));
    }

    @Test
    void tenantViewerCannotWriteEvenWhenProjectRoleAllowsIt() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("VIEWER"));
        stubMembership("OWNER");

        assertEquals(PROJECT_ID, accessService.requireProject("demo", false).getId());
        assertStatus(HttpStatus.FORBIDDEN, () -> accessService.requireProject("demo", true));
    }

    @Test
    void tenantOwnerCannotReadProjectWithoutProjectMembership() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("OWNER"));
        when(memberMapper.selectOne(any())).thenReturn(null);

        assertStatus(HttpStatus.NOT_FOUND, () -> accessService.requireProject("demo", false));
    }

    @Test
    void teamMemberCanAccessProjectsInItsTeam() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(teamMemberMapper.selectOne(any())).thenReturn(teamMembership("MEMBER"));

        assertEquals(PROJECT_ID, accessService.requireProject("demo", true).getId());
    }

    @Test
    void teamViewerCanReadButCannotWriteProject() {
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(teamMemberMapper.selectOne(any())).thenReturn(teamMembership("VIEWER"));

        assertEquals(PROJECT_ID, accessService.requireProject("demo", false).getId());
        assertForbidden(() -> accessService.requireProject("demo", true));
    }

    @Test
    void projectListExposesWritePermissionUsingTenantAndProjectRoles() {
        MonitorProject writableProject = projectFixture();
        MonitorProject readOnlyProject = projectFixture();
        writableProject.setId(17L);
        readOnlyProject.setId(18L);
        when(tenantMemberMapper.selectList(any())).thenReturn(List.of(tenantMembership("MEMBER")));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                membership(17L, "MEMBER"), membership(18L, "VIEWER")
        ));
        when(teamMemberMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of(writableProject, readOnlyProject));

        List<MonitorProject> projects = accessService.listProjects();

        assertEquals(true, projects.get(0).getCanWrite());
        assertEquals(false, projects.get(1).getCanWrite());
    }

    @Test
    void tenantViewerProjectListIsReadOnlyEvenForProjectOwner() {
        MonitorProject project = projectFixture();
        when(tenantMemberMapper.selectList(any())).thenReturn(List.of(tenantMembership("VIEWER")));
        when(memberMapper.selectList(any())).thenReturn(List.of(membership("OWNER")));
        when(teamMemberMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        assertEquals(false, accessService.listProjects().get(0).getCanWrite());
    }

    @Test
    void projectListUsesTeamRoleWhenNoDirectProjectRoleExists() {
        MonitorProject project = projectFixture();
        when(tenantMemberMapper.selectList(any())).thenReturn(List.of(tenantMembership("MEMBER")));
        when(memberMapper.selectList(any())).thenReturn(List.of());
        when(teamMemberMapper.selectList(any())).thenReturn(List.of(teamMembership("VIEWER")));
        when(projectMapper.selectList(any())).thenReturn(List.of(project), List.of(project));

        assertEquals(false, accessService.listProjects().get(0).getCanWrite());
    }

    @Test
    void teamMemberCanCreateProjectInItsTeam() {
        when(teamMapper.selectById(3L)).thenReturn(teamFixture(3L));
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        when(teamMemberMapper.selectOne(any())).thenReturn(teamMembership(3L, "MEMBER"));

        assertEquals(3L, accessService.teamForProjectCreation(3L).getId());
    }

    @Test
    void tenantMemberCannotCreateProjectInUnassignedTeam() {
        when(teamMapper.selectById(4L)).thenReturn(teamFixture(4L));
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        when(teamMemberMapper.selectOne(any())).thenReturn(null);

        assertForbidden(() -> accessService.teamForProjectCreation(4L));
    }

    @Test
    void tenantOwnerCanCreateProjectInTeamWithoutTeamMembership() {
        when(teamMapper.selectById(4L)).thenReturn(teamFixture(4L));
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("OWNER"));

        assertEquals(4L, accessService.teamForProjectCreation(4L).getId());
    }

    @Test
    void teamViewerCannotCreateProjectInItsTeam() {
        when(teamMapper.selectById(3L)).thenReturn(teamFixture(3L));
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        when(teamMemberMapper.selectOne(any())).thenReturn(teamMembership(3L, "VIEWER"));

        assertForbidden(() -> accessService.teamForProjectCreation(3L));
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
        when(tenantMemberMapper.selectOne(any())).thenReturn(tenantMembership("MEMBER"));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectFixture());
        stubMembership("OWNER");
        MonitorProjectMemberRequest request = new MonitorProjectMemberRequest();
        request.setAdminId(84L);
        request.setRole("OWNER");

        assertStatus(HttpStatus.BAD_REQUEST, () -> accessService.addOrUpdateMember("demo", request));
    }

    private void stubMembership(String role) {
        when(memberMapper.selectOne(any())).thenReturn(membership(role));
    }

    private MonitorProject projectFixture() {
        MonitorProject project = new MonitorProject();
        project.setId(PROJECT_ID);
        project.setProjectKey("demo");
        project.setTenantId(5L);
        project.setTeamId(3L);
        project.setStatus(1);
        return project;
    }

    private MonitorProjectMember membership(String role) {
        return membership(PROJECT_ID, role);
    }

    private MonitorProjectMember membership(Long projectId, String role) {
        MonitorProjectMember membership = new MonitorProjectMember();
        membership.setProjectId(projectId);
        membership.setAdminId(CURRENT_ADMIN_ID);
        membership.setRole(role);
        return membership;
    }

    private MonitorTenantMember tenantMembership(String role) {
        MonitorTenantMember membership = new MonitorTenantMember();
        membership.setTenantId(5L);
        membership.setAdminId(CURRENT_ADMIN_ID);
        membership.setRole(role);
        return membership;
    }

    private MonitorTeam teamFixture(Long teamId) {
        MonitorTeam team = new MonitorTeam();
        team.setId(teamId);
        team.setTenantId(5L);
        return team;
    }

    private MonitorTeamMember teamMembership(String role) {
        return teamMembership(3L, role);
    }

    private MonitorTeamMember teamMembership(Long teamId, String role) {
        MonitorTeamMember membership = new MonitorTeamMember();
        membership.setTenantId(5L);
        membership.setTeamId(teamId);
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
