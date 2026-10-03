package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macro.mall.tiny.modules.monitor.mapper.*;
import com.macro.mall.tiny.modules.monitor.model.*;
import com.macro.mall.tiny.modules.ums.mapper.UmsAdminMapper;
import com.macro.mall.tiny.modules.ums.mapper.UmsAdminRoleRelationMapper;
import com.macro.mall.tiny.modules.ums.mapper.UmsRoleMapper;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.model.UmsAdminRoleRelation;
import com.macro.mall.tiny.modules.ums.model.UmsRole;
import com.macro.mall.tiny.modules.ums.service.UmsAdminCacheService;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorScimServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private MonitorScimUserMapper scimUserMapper;
    @Mock private MonitorScimGroupMapper scimGroupMapper;
    @Mock private MonitorTenantMemberMapper tenantMemberMapper;
    @Mock private MonitorTeamMapper teamMapper;
    @Mock private MonitorTeamMemberMapper teamMemberMapper;
    @Mock private MonitorProjectMapper projectMapper;
    @Mock private MonitorTenantAuditLogMapper auditMapper;
    @Mock private UmsAdminMapper adminMapper;
    @Mock private UmsAdminRoleRelationMapper adminRoleMapper;
    @Mock private UmsRoleMapper roleMapper;
    @Mock private UmsAdminService adminService;
    @Mock private UmsAdminCacheService adminCacheService;
    @Mock private PasswordEncoder passwordEncoder;

    @BeforeEach
    void initializeMyBatisPlusLambdaMetadata() {
        initTableInfo(MonitorScimUser.class);
        initTableInfo(MonitorScimGroup.class);
        initTableInfo(MonitorTenantMember.class);
        initTableInfo(MonitorTeamMember.class);
        initTableInfo(MonitorProject.class);
        initTableInfo(UmsAdmin.class);
        initTableInfo(UmsRole.class);
    }

    @Test
    void deletesUserAndRevokesTenantAndTeamMemberships() {
        MonitorScimContext context = context(7L);
        MonitorScimUser identity = user("user-1", 7L, 41L, "external-1", 1);
        UmsAdmin admin = admin(41L, "alice", 1);
        when(scimUserMapper.selectOne(any())).thenReturn(identity);

        service().deleteUser(context, "user-1");

        assertEquals(0, identity.getActive());
        assertEquals(1, admin.getStatus());
        ArgumentCaptor<LambdaQueryWrapper<MonitorTenantMember>> tenantMembers = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tenantMemberMapper, org.mockito.Mockito.atLeastOnce()).delete(tenantMembers.capture());
        assertTrue(tenantMembers.getAllValues().stream().allMatch(query ->
                query.getSqlSegment().contains("tenant_id")
                        && query.getSqlSegment().contains("admin_id")),
                tenantMembers.getAllValues().stream().map(LambdaQueryWrapper::getSqlSegment).toList().toString());
        ArgumentCaptor<LambdaQueryWrapper<MonitorTeamMember>> teamMembers = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMemberMapper).delete(teamMembers.capture());
        assertTrue(teamMembers.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(teamMembers.getValue().getSqlSegment().contains("admin_id"));
        assertTrue(teamMembers.getValue().getSqlSegment().contains("MPGENVAL"));
        verify(scimUserMapper).deleteById("user-1");
    }

    @Test
    void deactivatingUserRemovesOnlyTenantMembershipAndKeepsGlobalAdminEnabled() throws Exception {
        MonitorScimUser identity = user("user-1", 7L, 41L, null, 1);
        UmsAdmin admin = admin(41L, "alice", 1);
        stubExistingUser(identity, admin);
        JsonNode input = json("""
                {"Operations":[{"op":"replace","path":"active","value":false}]}
                """);

        service().patchUser(context(7L), "user-1", input);

        assertEquals(0, identity.getActive());
        assertEquals(1, admin.getStatus());
        ArgumentCaptor<LambdaQueryWrapper<MonitorTenantMember>> tenantMembers = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tenantMemberMapper).delete(tenantMembers.capture());
        assertTrue(tenantMembers.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(tenantMembers.getValue().getSqlSegment().contains("admin_id"));
    }

    @Test
    void deletedUserIsHiddenFromGet() {
        MonitorScimContext context = context(7L);
        when(scimUserMapper.selectOne(any())).thenReturn(null);

        assertThrows(ResponseStatusException.class, () -> service().getUser(context, "user-1"));
    }

    @Test
    void deletedUserIsHiddenFromList() {
        MonitorScimContext context = context(7L);
        MonitorScimUser active = user("user-2", 7L, 42L, "external-2", 1);
        when(scimUserMapper.selectList(any())).thenReturn(List.of(active));
        when(adminMapper.selectById(42L)).thenReturn(admin(42L, "bob", 1));

        JsonNode response = service().listUsers(context, 1, 100, null);

        assertEquals(1, response.path("totalResults").asInt());
        assertEquals("user-2", response.path("Resources").get(0).path("id").asText());
    }

    @Test
    void doesNotReturnAUserFromAnotherTenant() {
        when(scimUserMapper.selectOne(any())).thenReturn(null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().getUser(context(7L), "foreign-user"));

        assertEquals(404, error.getStatusCode().value());
        ArgumentCaptor<LambdaQueryWrapper<MonitorScimUser>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(scimUserMapper).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("foreign-user"));
    }

    @Test
    void rejectsStringActiveOnCreate() throws Exception {
        stubCreateUserDependencies();
        JsonNode input = json("""
                {"userName":"alice","active":"false"}
                """);

        assertBadRequest(() -> service().createUser(context(7L), input));
    }

    @Test
    void rejectsNullActiveOnCreate() throws Exception {
        stubCreateUserDependencies();
        JsonNode input = json("""
                {"userName":"alice","active":null}
                """);

        assertBadRequest(() -> service().createUser(context(7L), input));
    }

    private void stubCreateUserDependencies() {
        lenient().when(adminMapper.selectOne(any())).thenReturn(null);
        lenient().when(roleMapper.selectOne(any())).thenReturn(scimRole());
        lenient().when(passwordEncoder.encode(any(CharSequence.class))).thenReturn("encoded-password");
        lenient().when(adminMapper.insert(any(UmsAdmin.class))).thenAnswer(invocation -> {
            ((UmsAdmin) invocation.getArgument(0)).setId(41L);
            return 1;
        });
        lenient().when(adminService.getCacheService()).thenReturn(adminCacheService);
    }

    @Test
    void rejectsNullActiveOnPut() throws Exception {
        MonitorScimUser identity = user("user-1", 7L, 41L, null, 1);
        stubExistingUser(identity, admin(41L, "alice", 1));
        JsonNode input = json("""
                {"userName":"alice","active":null}
                """);

        assertBadRequest(() -> service().replaceUser(context(7L), "user-1", input));
    }

    @Test
    void rejectsNumericActiveOnPatch() throws Exception {
        MonitorScimUser identity = user("user-1", 7L, 41L, null, 1);
        stubExistingUser(identity, admin(41L, "alice", 1));
        JsonNode input = json("""
                {"Operations":[{"op":"replace","path":"active","value":0}]}
                """);

        assertBadRequest(() -> service().patchUser(context(7L), "user-1", input));
    }

    @Test
    void reusesDisabledAdminWithScimRoleAfterPreviousIdentityIsDeleted() throws Exception {
        MonitorScimContext context = context(7L);
        UmsAdmin disabledAdmin = admin(41L, "alice", 0);
        UmsRole scimRole = scimRole();
        UmsAdminRoleRelation existingRelation = new UmsAdminRoleRelation();
        existingRelation.setAdminId(41L);
        existingRelation.setRoleId(scimRole.getId());
        when(scimUserMapper.findByExternalId(7L, "external-1")).thenReturn(null);
        when(adminMapper.selectOne(any())).thenReturn(disabledAdmin);
        when(roleMapper.selectOne(any())).thenReturn(scimRole);
        when(adminRoleMapper.selectCount(any())).thenReturn(1L);
        when(scimUserMapper.selectCount(any())).thenReturn(0L);
        when(tenantMemberMapper.selectOne(any())).thenReturn(null);
        when(adminMapper.selectById(41L)).thenReturn(disabledAdmin);
        when(adminService.getCacheService()).thenReturn(adminCacheService);

        JsonNode input = json("""
                {"userName":"alice","externalId":"external-1","active":true}
                """);
        var created = service().createUser(context, input);

        assertEquals("alice", created.path("userName").asText());
        assertTrue(created.path("active").asBoolean());
        ArgumentCaptor<MonitorScimUser> inserted = ArgumentCaptor.forClass(MonitorScimUser.class);
        verify(scimUserMapper).insert(inserted.capture());
        assertEquals(41L, inserted.getValue().getAdminId());
        assertEquals(0, disabledAdmin.getStatus());
        verify(adminMapper, never()).insert(any(UmsAdmin.class));
        verify(adminRoleMapper, never()).insert(any(UmsAdminRoleRelation.class));
        verify(adminRoleMapper).selectCount(any());
    }

    @Test
    void groupDeleteWithProjectRemovesScimMappingAndMembershipButKeepsTeam() {
        MonitorScimGroup group = group("group-1", 7L, 91L);
        MonitorScimUser managed = user("user-1", 7L, 41L, null, 1);
        when(scimGroupMapper.selectOne(any())).thenReturn(group);
        when(projectMapper.selectCount(any())).thenReturn(1L);
        when(scimUserMapper.selectList(any())).thenReturn(List.of(managed));

        service().deleteGroup(context(7L), "group-1");

        verify(scimGroupMapper).deleteById("group-1");
        ArgumentCaptor<LambdaQueryWrapper<MonitorTeamMember>> memberRemoval = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMemberMapper).delete(memberRemoval.capture());
        String removalSql = memberRemoval.getValue().getSqlSegment();
        assertTrue(removalSql.contains("tenant_id"), removalSql);
        assertTrue(removalSql.contains("team_id"), removalSql);
        assertTrue(removalSql.contains("admin_id"), removalSql);
        ArgumentCaptor<LambdaQueryWrapper<MonitorScimUser>> tenantUsers = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(scimUserMapper).selectList(tenantUsers.capture());
        assertTrue(tenantUsers.getValue().getSqlSegment().contains("tenant_id"));
        verify(teamMapper, never()).deleteById(91L);
        ArgumentCaptor<LambdaQueryWrapper<MonitorProject>> projects = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMapper).selectCount(projects.capture());
        assertTrue(projects.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(projects.getValue().getSqlSegment().contains("team_id"));
        verify(auditMapper).insert(any(MonitorTenantAuditLog.class));
    }

    @Test
    void groupDeleteDoesNotRemoveMembershipsWhenTenantHasNoScimManagedUsers() {
        MonitorScimGroup group = group("group-1", 7L, 91L);
        when(scimGroupMapper.selectOne(any())).thenReturn(group);
        when(projectMapper.selectCount(any())).thenReturn(1L);
        when(scimUserMapper.selectList(any())).thenReturn(List.of());

        service().deleteGroup(context(7L), "group-1");

        verify(teamMemberMapper, never()).delete(any());
        verify(teamMapper, never()).deleteById(91L);
    }

    @Test
    void groupPatchRejectsMembersProvisionedInAnotherTenant() throws Exception {
        MonitorScimGroup group = group("group-1", 7L, 91L);
        MonitorTeam team = team(91L, 7L, "Engineering");
        when(scimGroupMapper.selectOne(any())).thenReturn(group, group);
        when(teamMapper.selectById(91L)).thenReturn(team, team);
        when(teamMemberMapper.selectList(any())).thenReturn(List.of());
        when(scimUserMapper.selectList(any())).thenReturn(List.of());
        when(scimUserMapper.selectOne(any())).thenReturn(null);
        JsonNode patch = json("""
                {"Operations":[{"op":"add","path":"members","value":{"value":"foreign-user"}}]}
                """);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().patchGroup(context(7L), "group-1", patch));

        assertEquals(400, error.getStatusCode().value());
        verify(teamMemberMapper, never()).insert(any(MonitorTeamMember.class));
    }

    @Test
    void groupPatchChangesOnlyManagedMemberships() throws Exception {
        MonitorScimContext context = context(7L);
        MonitorScimGroup group = group("group-1", 7L, 91L);
        MonitorTeam team = team(91L, 7L, "Engineering");
        MonitorScimUser removed = user("user-1", 7L, 41L, null, 1);
        MonitorScimUser retained = user("user-2", 7L, 42L, null, 1);
        MonitorScimUser added = user("user-3", 7L, 43L, null, 1);
        MonitorTeamMember removedMember = teamMember(701L, 7L, 91L, 41L);
        MonitorTeamMember retainedMember = teamMember(702L, 7L, 91L, 42L);
        MonitorTeamMember unmanagedMember = teamMember(703L, 7L, 91L, 999L);
        MonitorTeamMember addedMember = teamMember(704L, 7L, 91L, 43L);
        when(scimGroupMapper.selectOne(any())).thenReturn(group, group);
        when(teamMapper.selectById(91L)).thenReturn(team, team, team);
        when(teamMemberMapper.selectList(any())).thenReturn(
                List.of(removedMember, retainedMember, unmanagedMember),
                List.of(removedMember, retainedMember, unmanagedMember),
                List.of(retainedMember, unmanagedMember, addedMember));
        when(scimUserMapper.selectList(any())).thenReturn(
                List.of(removed, retained, added), List.of(removed, retained, added), List.of(removed, retained, added));
        when(scimUserMapper.selectOne(any())).thenReturn(retained, added);
        when(teamMemberMapper.selectOne(any())).thenReturn(retainedMember).thenReturn(null);
        when(teamMemberMapper.insert(any(MonitorTeamMember.class))).thenReturn(1);

        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode operations = patch.putArray("Operations");
        operations.addObject().put("op", "remove").put("path", "members[value eq \"user-1\"]");
        operations.addObject().put("op", "add").put("path", "members")
                .putObject("value").put("value", "user-3");

        service().patchGroup(context, "group-1", patch);

        verify(teamMemberMapper).deleteById(701L);
        verify(teamMemberMapper, never()).deleteById(703L);
        ArgumentCaptor<MonitorTeamMember> inserted = ArgumentCaptor.forClass(MonitorTeamMember.class);
        verify(teamMemberMapper).insert(inserted.capture());
        assertEquals(43L, inserted.getValue().getAdminId());
        assertEquals(91L, inserted.getValue().getTeamId());
    }

    private MonitorScimService service() {
        return new MonitorScimService(scimUserMapper, scimGroupMapper, tenantMemberMapper, teamMapper,
                teamMemberMapper, projectMapper, auditMapper, adminMapper, adminRoleMapper, roleMapper,
                adminService, passwordEncoder, objectMapper);
    }

    private void stubExistingUser(MonitorScimUser identity, UmsAdmin admin) {
        lenient().when(scimUserMapper.selectOne(any())).thenReturn(identity);
        lenient().when(adminMapper.selectById(identity.getAdminId())).thenReturn(admin);
        lenient().when(adminService.getCacheService()).thenReturn(adminCacheService);
    }

    private void assertBadRequest(Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(400, error.getStatusCode().value());
    }

    private JsonNode json(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private MonitorScimContext context(long tenantId) {
        return new MonitorScimContext(tenantId, 900L, 55L);
    }

    private MonitorScimUser user(String scimId, long tenantId, long adminId, String externalId, int active) {
        MonitorScimUser user = new MonitorScimUser();
        user.setScimId(scimId);
        user.setTenantId(tenantId);
        user.setAdminId(adminId);
        user.setExternalId(externalId);
        user.setActive(active);
        return user;
    }

    private UmsAdmin admin(long id, String username, int status) {
        UmsAdmin admin = new UmsAdmin();
        admin.setId(id);
        admin.setUsername(username);
        admin.setNickName(username);
        admin.setEmail(username + "@example.test");
        admin.setStatus(status);
        return admin;
    }

    private UmsRole scimRole() {
        UmsRole role = new UmsRole();
        role.setId(8L);
        role.setName("Observability SCIM Member [monitor-scim-v1]");
        role.setDescription("monitor-scim-system-role:v1; tenant/project scoped");
        role.setStatus(1);
        return role;
    }

    private MonitorScimGroup group(String scimId, long tenantId, long teamId) {
        MonitorScimGroup group = new MonitorScimGroup();
        group.setScimId(scimId);
        group.setTenantId(tenantId);
        group.setTeamId(teamId);
        return group;
    }

    private MonitorTeam team(long id, long tenantId, String name) {
        MonitorTeam team = new MonitorTeam();
        team.setId(id);
        team.setTenantId(tenantId);
        team.setName(name);
        return team;
    }

    private MonitorTeamMember teamMember(long id, long tenantId, long teamId, long adminId) {
        MonitorTeamMember member = new MonitorTeamMember();
        member.setId(id);
        member.setTenantId(tenantId);
        member.setTeamId(teamId);
        member.setAdminId(adminId);
        member.setRole("MEMBER");
        return member;
    }

    private void initTableInfo(Class<?> model) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), model.getName()), model);
    }

}
