package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** SCIM 2.0 Users and Groups, scoped to the tenant resolved from the bearer token. */
@Service
@RequiredArgsConstructor
public class MonitorScimService {
    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    private static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    private static final String RESOURCE_BASE = "/scim/v2";
    private static final String SCIM_ROLE_DESCRIPTION = "monitor-scim-system-role:v1; tenant/project scoped";
    private static final int MAX_PAGE_SIZE = 200;
    private static final Pattern FILTER = Pattern.compile("(?i)^([a-zA-Z]+)\\s+eq\\s+\"([^\"]*)\"$");
    private static final Pattern GROUP_MEMBER_PATH = Pattern.compile("(?i)^members\\[value\\s+eq\\s+\"([^\"]+)\"\\]$");
    private static final Pattern EMAIL_VALUE_PATH = Pattern.compile("(?i)^emails(?:\\[[^]]+\\])?(?:\\.value)?$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MonitorScimUserMapper scimUserMapper;
    private final MonitorScimGroupMapper scimGroupMapper;
    private final MonitorTenantMemberMapper tenantMemberMapper;
    private final MonitorTenantMapper tenantMapper;
    private final MonitorProjectMemberMapper projectMemberMapper;
    private final MonitorTeamMapper teamMapper;
    private final MonitorTeamMemberMapper teamMemberMapper;
    private final MonitorProjectMapper projectMapper;
    private final MonitorTenantAuditLogMapper auditMapper;
    private final UmsAdminMapper adminMapper;
    private final UmsAdminRoleRelationMapper adminRoleMapper;
    private final UmsRoleMapper roleMapper;
    private final UmsAdminService adminService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public JsonNode listUsers(MonitorScimContext context, int startIndex, int count, String filter) {
        List<ObjectNode> resources = scimUserMapper.selectList(Wrappers.<MonitorScimUser>lambdaQuery()
                        .eq(MonitorScimUser::getTenantId, context.tenantId())
                        .orderByAsc(MonitorScimUser::getScimId)).stream()
                .map(this::userResource)
                .filter(resource -> matchesFilter(resource, filter))
                .toList();
        return listResponse(resources, startIndex, count);
    }

    public JsonNode getUser(MonitorScimContext context, String scimId) {
        MonitorScimUser identity = findUser(context.tenantId(), scimId);
        if (identity == null) throw notFound("User");
        return userResource(identity);
    }

    @Transactional
    public ObjectNode createUser(MonitorScimContext context, JsonNode input) {
        String userName = requiredText(input, "userName", 64);
        String externalId = optionalText(input, "externalId", 256);
        if (externalId != null && scimUserMapper.findByExternalId(context.tenantId(), externalId) != null) {
            throw conflict("externalId already exists");
        }
        UmsAdmin admin = adminMapper.selectOne(Wrappers.<UmsAdmin>lambdaQuery()
                .eq(UmsAdmin::getUsername, userName).last("LIMIT 1"));
        UmsRole scimRole = roleMapper.selectOne(Wrappers.<UmsRole>lambdaQuery()
                .eq(UmsRole::getDescription, SCIM_ROLE_DESCRIPTION)
                .eq(UmsRole::getStatus, 1)
                .last("LIMIT 1"));
        if (scimRole == null) throw new IllegalStateException("SCIM member role migration is missing");

        boolean reuseDeletedScimAccount = false;
        if (admin != null) {
            boolean hasScimRole = adminRoleMapper.selectCount(Wrappers.<UmsAdminRoleRelation>lambdaQuery()
                    .eq(UmsAdminRoleRelation::getAdminId, admin.getId())
                    .eq(UmsAdminRoleRelation::getRoleId, scimRole.getId())) > 0;
            boolean hasIdentity = scimUserMapper.selectCount(Wrappers.<MonitorScimUser>lambdaQuery()
                    .eq(MonitorScimUser::getAdminId, admin.getId())) > 0;
            if (!hasScimRole || hasIdentity || !Objects.equals(admin.getStatus(), 0)) {
                throw conflict("userName already exists");
            }
            reuseDeletedScimAccount = true;
        }

        boolean active = activeValue(input, true);
        String displayName = firstText(input, "displayName", input.path("name").path("formatted").asText(null), userName);
        String email = primaryEmail(input);
        if (!reuseDeletedScimAccount) {
            admin = new UmsAdmin();
            admin.setUsername(userName);
            admin.setPassword(passwordEncoder.encode(randomPassword()));
            admin.setCreateTime(new Date());
            admin.setStatus(1);
        }
        admin.setEmail(email);
        admin.setNickName(displayName);
        if (reuseDeletedScimAccount) {
            adminMapper.updateById(admin);
        } else {
            adminMapper.insert(admin);
            UmsAdminRoleRelation roleRelation = new UmsAdminRoleRelation();
            roleRelation.setAdminId(admin.getId());
            roleRelation.setRoleId(scimRole.getId());
            adminRoleMapper.insert(roleRelation);
        }

        MonitorScimUser identity = new MonitorScimUser();
        identity.setScimId(UUID.randomUUID().toString());
        identity.setTenantId(context.tenantId());
        identity.setAdminId(admin.getId());
        identity.setExternalId(externalId);
        identity.setActive(active ? 1 : 0);
        scimUserMapper.insert(identity);
        if (active) ensureTenantMember(context.tenantId(), admin.getId());
        audit(context, active ? "scim.user_created" : "scim.user_created_inactive", identity.getScimId(),
                Map.of("adminId", admin.getId()));
        adminService.getCacheService().delAdmin(admin.getId());
        adminService.getCacheService().delResourceList(admin.getId());
        return userResource(identity);
    }

    @Transactional
    public ObjectNode replaceUser(MonitorScimContext context, String scimId, JsonNode input) {
        MonitorScimUser identity = requireUser(context.tenantId(), scimId);
        updateUser(context, identity, input, true);
        return userResource(identity);
    }

    @Transactional
    public ObjectNode patchUser(MonitorScimContext context, String scimId, JsonNode input) {
        MonitorScimUser identity = requireUser(context.tenantId(), scimId);
        ArrayNode operations = requireOperations(input);
        ObjectNode changes = objectMapper.createObjectNode();
        for (JsonNode operation : operations) {
            String op = operation.path("op").asText("").toLowerCase(Locale.ROOT);
            String path = operation.path("path").asText("").toLowerCase(Locale.ROOT);
            JsonNode value = operation.get("value");
            if ("remove".equals(op)) {
                if ("active".equals(path)) changes.put("active", false);
                else if ("emails".equals(path)) changes.set("emails", objectMapper.createArrayNode());
                else throw badRequest("unsupported SCIM User PATCH remove path");
            } else if ("replace".equals(op) || "add".equals(op)) {
                if (StringUtils.hasText(path)) {
                    String key = switch (path) {
                        case "username", "displayname", "externalid", "active", "emails", "name.formatted" -> path;
                        default -> EMAIL_VALUE_PATH.matcher(path).matches() ? "email-value" : null;
                    };
                    if (key == null) throw badRequest("unsupported SCIM User PATCH path");
                    String jsonKey = switch (key) {
                        case "username" -> "userName";
                        case "displayname" -> "displayName";
                        case "externalid" -> "externalId";
                        case "name.formatted" -> "displayName";
                        case "email-value" -> "emails";
                        default -> key;
                    };
                    if ("email-value".equals(key) && value != null && value.isValueNode()) {
                        ArrayNode emails = objectMapper.createArrayNode();
                        emails.addObject().put("value", value.asText());
                        changes.set(jsonKey, emails);
                    } else {
                        changes.set(jsonKey, value);
                    }
                } else if (value != null && value.isObject()) {
                    value.fields().forEachRemaining(entry -> changes.set(entry.getKey(), entry.getValue()));
                } else {
                    throw badRequest("SCIM User PATCH operation requires path or object value");
                }
            } else throw badRequest("unsupported SCIM PATCH operation");
        }
        updateUser(context, identity, changes, false);
        return userResource(identity);
    }

    @Transactional
    public void deleteUser(MonitorScimContext context, String scimId) {
        MonitorScimUser identity = requireUser(context.tenantId(), scimId);
        boolean wasActive = Objects.equals(identity.getActive(), 1);
        List<Long> tenantProjectIds = projectsForMembershipCleanup(
                context.tenantId(), identity.getAdminId(), wasActive);
        setUserActive(context, identity, false, tenantProjectIds);
        scimUserMapper.deleteById(identity.getScimId());
        audit(context, "scim.user_deleted", scimId, Map.of("adminId", identity.getAdminId()));
    }

    public JsonNode listGroups(MonitorScimContext context, int startIndex, int count, String filter) {
        List<ObjectNode> resources = scimGroupMapper.selectList(Wrappers.<MonitorScimGroup>lambdaQuery()
                        .eq(MonitorScimGroup::getTenantId, context.tenantId())
                        .orderByAsc(MonitorScimGroup::getScimId)).stream()
                .map(this::groupResource)
                .filter(resource -> matchesFilter(resource, filter))
                .toList();
        return listResponse(resources, startIndex, count);
    }

    public ObjectNode getGroup(MonitorScimContext context, String scimId) {
        MonitorScimGroup group = findGroup(context.tenantId(), scimId);
        if (group == null) throw notFound("Group");
        return groupResource(group);
    }

    @Transactional
    public ObjectNode createGroup(MonitorScimContext context, JsonNode input) {
        String displayName = requiredText(input, "displayName", 128);
        String externalId = optionalText(input, "externalId", 256);
        if (externalId != null && scimGroupMapper.selectCount(Wrappers.<MonitorScimGroup>lambdaQuery()
                .eq(MonitorScimGroup::getTenantId, context.tenantId())
                .eq(MonitorScimGroup::getExternalId, externalId)) > 0) {
            throw conflict("externalId already exists");
        }
        MonitorTeam team = new MonitorTeam();
        team.setTenantId(context.tenantId());
        team.setName(displayName);
        team.setTeamKey(teamKey(displayName));
        team.setIsDefault(0);
        teamMapper.insert(team);

        MonitorScimGroup group = new MonitorScimGroup();
        group.setScimId(UUID.randomUUID().toString());
        group.setTenantId(context.tenantId());
        group.setTeamId(team.getId());
        group.setExternalId(externalId);
        scimGroupMapper.insert(group);
        replaceGroupMembers(context, group, input.path("members"));
        audit(context, "scim.group_created", group.getScimId(), Map.of("teamId", team.getId()));
        return groupResource(group);
    }

    @Transactional
    public ObjectNode replaceGroup(MonitorScimContext context, String scimId, JsonNode input) {
        MonitorScimGroup group = requireGroup(context.tenantId(), scimId);
        String displayName = requiredText(input, "displayName", 128);
        String externalId = optionalText(input, "externalId", 256);
        ensureGroupExternalIdAvailable(context.tenantId(), externalId, scimId);
        MonitorTeam team = teamMapper.selectById(group.getTeamId());
        if (team == null || !Objects.equals(team.getTenantId(), context.tenantId())) throw notFound("Group");
        team.setName(displayName);
        teamMapper.updateById(team);
        group.setExternalId(externalId);
        scimGroupMapper.updateById(group);
        replaceGroupMembers(context, group, input.path("members"));
        audit(context, "scim.group_updated", scimId, Map.of("teamId", group.getTeamId()));
        return groupResource(group);
    }

    @Transactional
    public ObjectNode patchGroup(MonitorScimContext context, String scimId, JsonNode input) {
        MonitorScimGroup group = requireGroup(context.tenantId(), scimId);
        ObjectNode replacement = objectMapper.createObjectNode();
        ObjectNode current = groupResource(group);
        replacement.set("displayName", current.get("displayName"));
        replacement.set("externalId", current.get("externalId"));
        ArrayNode members = objectMapper.createArrayNode();
        current.path("members").forEach(member -> members.add(member.deepCopy()));
        ArrayNode operations = requireOperations(input);
        for (JsonNode operation : operations) {
            String op = operation.path("op").asText("").toLowerCase(Locale.ROOT);
            String path = operation.path("path").asText("");
            JsonNode value = operation.get("value");
            if ("replace".equals(op) && "displayName".equalsIgnoreCase(path)) {
                replacement.set("displayName", value);
            } else if (("add".equals(op) || "replace".equals(op)) && "members".equalsIgnoreCase(path)) {
                if ("replace".equals(op)) members.removeAll();
                appendMembers(members, value);
            } else if (("add".equals(op) || "replace".equals(op)) && GROUP_MEMBER_PATH.matcher(path).matches()) {
                Matcher matcher = GROUP_MEMBER_PATH.matcher(path);
                matcher.matches();
                String memberId = matcher.group(1);
                for (int index = members.size() - 1; index >= 0; index--) {
                    if (memberId.equals(members.get(index).path("value").asText())) members.remove(index);
                }
                appendMembers(members, value);
            } else if ("remove".equals(op) && GROUP_MEMBER_PATH.matcher(path).matches()) {
                Matcher matcher = GROUP_MEMBER_PATH.matcher(path);
                matcher.matches();
                String removeId = matcher.group(1);
                for (int index = members.size() - 1; index >= 0; index--) {
                    if (removeId.equals(members.get(index).path("value").asText())) members.remove(index);
                }
            } else if ("remove".equals(op) && "members".equalsIgnoreCase(path)) {
                members.removeAll();
            } else {
                throw badRequest("unsupported SCIM Group PATCH operation");
            }
        }
        replacement.set("members", members);
        return replaceGroup(context, scimId, replacement);
    }

    @Transactional
    public void deleteGroup(MonitorScimContext context, String scimId) {
        MonitorScimGroup group = requireGroup(context.tenantId(), scimId);
        Long projectCount = projectMapper.selectCount(Wrappers.<MonitorProject>lambdaQuery()
                .eq(MonitorProject::getTenantId, context.tenantId())
                .eq(MonitorProject::getTeamId, group.getTeamId()));
        Set<Long> managedAdminIds = scimUserMapper.selectList(Wrappers.<MonitorScimUser>lambdaQuery()
                        .eq(MonitorScimUser::getTenantId, context.tenantId()))
                .stream().map(MonitorScimUser::getAdminId).collect(Collectors.toSet());
        if (!managedAdminIds.isEmpty()) {
            teamMemberMapper.delete(Wrappers.<MonitorTeamMember>lambdaQuery()
                    .eq(MonitorTeamMember::getTenantId, context.tenantId())
                    .eq(MonitorTeamMember::getTeamId, group.getTeamId())
                    .in(MonitorTeamMember::getAdminId, managedAdminIds));
        }
        scimGroupMapper.deleteById(group.getScimId());
        boolean preserveTeam = projectCount != null && projectCount > 0;
        if (!preserveTeam) teamMapper.deleteById(group.getTeamId());
        audit(context, "scim.group_deleted", scimId,
                Map.of("teamId", group.getTeamId(), "projectsPreserved", preserveTeam));
    }

    private void updateUser(MonitorScimContext context, MonitorScimUser identity, JsonNode input, boolean replace) {
        UmsAdmin admin = adminMapper.selectById(identity.getAdminId());
        if (admin == null) throw notFound("User");
        boolean active = activeValue(input, replace ? false : Objects.equals(identity.getActive(), 1));
        boolean wasActive = Objects.equals(identity.getActive(), 1);
        List<Long> tenantProjectIds = active && wasActive ? null
                : projectsForMembershipCleanup(context.tenantId(), identity.getAdminId(), !active && wasActive);
        String userName = input.has("userName") ? requiredText(input, "userName", 64)
                : replace ? requiredText(input, "userName", 64) : admin.getUsername();
        if (userName != null && !Objects.equals(userName, admin.getUsername())) {
            UmsAdmin existing = adminMapper.selectOne(Wrappers.<UmsAdmin>lambdaQuery()
                    .eq(UmsAdmin::getUsername, userName).ne(UmsAdmin::getId, admin.getId()).last("LIMIT 1"));
            if (existing != null) throw conflict("userName already exists");
            admin.setUsername(userName);
        }
        String externalId = input.has("externalId") ? optionalText(input, "externalId", 256) : replace ? null : identity.getExternalId();
        ensureUserExternalIdAvailable(context.tenantId(), externalId, identity.getScimId());
        identity.setExternalId(externalId);
        String email = input.has("emails") ? primaryEmail(input) : replace ? null : admin.getEmail();
        if (email != null && email.length() > 100) throw badRequest("email exceeds mall-tiny account length limit");
        admin.setEmail(email);
        String displayName = input.has("displayName") ? requiredText(input, "displayName", 200)
                : replace ? admin.getUsername() : admin.getNickName();
        admin.setNickName(displayName);
        adminMapper.updateById(admin);
        scimUserMapper.updateById(identity);
        setUserActive(context, identity, active, tenantProjectIds);
        adminService.getCacheService().delAdmin(admin.getId());
        adminService.getCacheService().delResourceList(admin.getId());
        audit(context, "scim.user_updated", identity.getScimId(), Map.of("active", active));
    }

    private List<Long> projectsForMembershipCleanup(Long tenantId, Long adminId, boolean protectLastOwner) {
        if (tenantMapper.lockTenant(tenantId) == null) throw notFound("Tenant");
        List<Long> projectIds = projectMapper.selectList(Wrappers.<MonitorProject>lambdaQuery()
                        .eq(MonitorProject::getTenantId, tenantId))
                .stream()
                .map(MonitorProject::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!protectLastOwner || projectIds.isEmpty()) return projectIds;

        List<MonitorProjectMember> projectOwners = projectMemberMapper.selectList(
                Wrappers.<MonitorProjectMember>lambdaQuery()
                        .in(MonitorProjectMember::getProjectId, projectIds)
                        .eq(MonitorProjectMember::getRole, "OWNER")
                        .last("FOR UPDATE"));
        boolean removesLastProjectOwner = projectOwners.stream()
                .filter(owner -> Objects.equals(owner.getAdminId(), adminId))
                .anyMatch(owner -> projectOwners.stream()
                        .filter(other -> Objects.equals(other.getProjectId(), owner.getProjectId()))
                        .count() <= 1);
        if (removesLastProjectOwner) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "transfer project ownership before deactivating the last project owner");
        }
        return projectIds;
    }

    private void setUserActive(MonitorScimContext context, MonitorScimUser identity, boolean active,
                               List<Long> tenantProjectIds) {
        boolean wasActive = Objects.equals(identity.getActive(), 1);
        if (active) {
            if (!wasActive) clearTenantProjectMemberships(context.tenantId(), identity.getAdminId(), tenantProjectIds);
            if (!wasActive) {
                identity.setActive(1);
                scimUserMapper.updateById(identity);
            }
            ensureTenantMember(context.tenantId(), identity.getAdminId());
        } else {
            clearTenantProjectMemberships(context.tenantId(), identity.getAdminId(), tenantProjectIds);
            tenantMemberMapper.delete(Wrappers.<MonitorTenantMember>lambdaQuery()
                    .eq(MonitorTenantMember::getTenantId, context.tenantId())
                    .eq(MonitorTenantMember::getAdminId, identity.getAdminId()));
            if (wasActive) {
                identity.setActive(0);
                scimUserMapper.updateById(identity);
            }
        }
    }

    private void clearTenantProjectMemberships(Long tenantId, Long adminId, List<Long> tenantProjectIds) {
        teamMemberMapper.delete(Wrappers.<MonitorTeamMember>lambdaQuery()
                .eq(MonitorTeamMember::getTenantId, tenantId)
                .eq(MonitorTeamMember::getAdminId, adminId));
        if (tenantProjectIds != null && !tenantProjectIds.isEmpty()) {
            projectMemberMapper.delete(Wrappers.<MonitorProjectMember>lambdaQuery()
                    .in(MonitorProjectMember::getProjectId, tenantProjectIds)
                    .eq(MonitorProjectMember::getAdminId, adminId));
        }
    }

    private void ensureTenantMember(Long tenantId, Long adminId) {
        MonitorTenantMember member = tenantMemberMapper.selectOne(Wrappers.<MonitorTenantMember>lambdaQuery()
                .eq(MonitorTenantMember::getTenantId, tenantId)
                .eq(MonitorTenantMember::getAdminId, adminId).last("LIMIT 1"));
        if (member == null) {
            member = new MonitorTenantMember();
            member.setTenantId(tenantId);
            member.setAdminId(adminId);
            member.setRole("MEMBER");
            tenantMemberMapper.insert(member);
        }
    }

    private ObjectNode userResource(MonitorScimUser identity) {
        UmsAdmin admin = adminMapper.selectById(identity.getAdminId());
        if (admin == null) throw notFound("User");
        ObjectNode user = objectMapper.createObjectNode();
        user.putArray("schemas").add(USER_SCHEMA);
        user.put("id", identity.getScimId());
        if (identity.getExternalId() != null) user.put("externalId", identity.getExternalId());
        user.put("userName", admin.getUsername());
        if (admin.getNickName() != null) {
            user.put("displayName", admin.getNickName());
            user.putObject("name").put("formatted", admin.getNickName());
        }
        if (admin.getEmail() != null) user.putArray("emails").addObject().put("value", admin.getEmail()).put("primary", true);
        user.put("active", Objects.equals(identity.getActive(), 1));
        ObjectNode meta = user.putObject("meta");
        meta.put("resourceType", "User");
        meta.put("location", RESOURCE_BASE + "/Users/" + identity.getScimId());
        if (identity.getCreateTime() != null) meta.put("created", identity.getCreateTime().toInstant().toString());
        if (identity.getUpdateTime() != null) meta.put("lastModified", identity.getUpdateTime().toInstant().toString());
        return user;
    }

    private ObjectNode groupResource(MonitorScimGroup group) {
        MonitorTeam team = teamMapper.selectById(group.getTeamId());
        if (team == null) throw notFound("Group");
        ObjectNode resource = objectMapper.createObjectNode();
        resource.putArray("schemas").add(GROUP_SCHEMA);
        resource.put("id", group.getScimId());
        if (group.getExternalId() != null) resource.put("externalId", group.getExternalId());
        resource.put("displayName", team.getName());
        ArrayNode members = resource.putArray("members");
        List<MonitorTeamMember> teamMembers = teamMemberMapper.selectList(Wrappers.<MonitorTeamMember>lambdaQuery()
                .eq(MonitorTeamMember::getTeamId, team.getId()).orderByAsc(MonitorTeamMember::getId));
        Map<Long, MonitorScimUser> identities = scimUserMapper.selectList(Wrappers.<MonitorScimUser>lambdaQuery()
                        .eq(MonitorScimUser::getTenantId, group.getTenantId()))
                .stream().collect(Collectors.toMap(MonitorScimUser::getAdminId, identity -> identity));
        for (MonitorTeamMember member : teamMembers) {
            MonitorScimUser identity = identities.get(member.getAdminId());
            if (identity == null) continue;
            ObjectNode value = members.addObject();
            value.put("value", identity.getScimId());
            value.put("$ref", RESOURCE_BASE + "/Users/" + identity.getScimId());
            value.put("type", "User");
        }
        ObjectNode meta = resource.putObject("meta");
        meta.put("resourceType", "Group");
        meta.put("location", RESOURCE_BASE + "/Groups/" + group.getScimId());
        return resource;
    }

    private void replaceGroupMembers(MonitorScimContext context, MonitorScimGroup group, JsonNode memberNodes) {
        Set<String> requested = new LinkedHashSet<>();
        if (memberNodes != null && memberNodes.isArray()) {
            for (JsonNode member : memberNodes) {
                String scimUserId = member.path("value").asText("");
                if (!StringUtils.hasText(scimUserId)) throw badRequest("Group member value is required");
                MonitorScimUser identity = findUser(context.tenantId(), scimUserId);
                if (identity == null) {
                    throw badRequest("Group members must be Users provisioned in the same tenant");
                }
                requested.add(scimUserId);
            }
        }
        Map<String, MonitorScimUser> tenantUsers = scimUserMapper.selectList(Wrappers.<MonitorScimUser>lambdaQuery()
                        .eq(MonitorScimUser::getTenantId, context.tenantId()))
                .stream().collect(Collectors.toMap(MonitorScimUser::getScimId, identity -> identity));
        List<MonitorTeamMember> current = teamMemberMapper.selectList(Wrappers.<MonitorTeamMember>lambdaQuery()
                .eq(MonitorTeamMember::getTeamId, group.getTeamId()));
        Set<Long> requestedAdminIds = requested.stream().map(tenantUsers::get).filter(Objects::nonNull)
                .map(MonitorScimUser::getAdminId).collect(Collectors.toSet());
        Set<Long> managedAdminIds = tenantUsers.values().stream()
                .map(MonitorScimUser::getAdminId).collect(Collectors.toSet());
        for (MonitorTeamMember member : current) {
            if (managedAdminIds.contains(member.getAdminId()) && !requestedAdminIds.contains(member.getAdminId())) {
                teamMemberMapper.deleteById(member.getId());
            }
        }
        for (Long adminId : requestedAdminIds) {
            MonitorTeamMember member = teamMemberMapper.selectOne(Wrappers.<MonitorTeamMember>lambdaQuery()
                    .eq(MonitorTeamMember::getTeamId, group.getTeamId())
                    .eq(MonitorTeamMember::getAdminId, adminId).last("LIMIT 1"));
            if (member == null) {
                member = new MonitorTeamMember();
                member.setTenantId(context.tenantId());
                member.setTeamId(group.getTeamId());
                member.setAdminId(adminId);
                member.setRole("MEMBER");
                teamMemberMapper.insert(member);
            }
        }
    }

    private void appendMembers(ArrayNode target, JsonNode values) {
        if (values == null || values.isNull()) return;
        if (values.isArray()) values.forEach(item -> target.add(item.deepCopy()));
        else if (values.isObject()) target.add(values.deepCopy());
        else throw badRequest("Group members must be an array of member objects");
    }

    private void ensureGroupExternalIdAvailable(Long tenantId, String externalId, String currentScimId) {
        if (externalId == null) return;
        MonitorScimGroup existing = scimGroupMapper.selectOne(Wrappers.<MonitorScimGroup>lambdaQuery()
                .eq(MonitorScimGroup::getTenantId, tenantId)
                .eq(MonitorScimGroup::getExternalId, externalId).last("LIMIT 1"));
        if (existing != null && !Objects.equals(existing.getScimId(), currentScimId)) throw conflict("externalId already exists");
    }

    private void ensureUserExternalIdAvailable(Long tenantId, String externalId, String currentScimId) {
        if (externalId == null) return;
        MonitorScimUser existing = scimUserMapper.findByExternalId(tenantId, externalId);
        if (existing != null && !Objects.equals(existing.getScimId(), currentScimId)) throw conflict("externalId already exists");
    }

    private void audit(MonitorScimContext context, String action, String resourceId, Map<String, Object> details) {
        MonitorTenantAuditLog log = new MonitorTenantAuditLog();
        log.setTenantId(context.tenantId());
        log.setActorAdminId(context.createdBy());
        log.setAction(action);
        log.setResourceType(action.contains("group") ? "scim_group" : "scim_user");
        log.setResourceId(resourceId);
        try {
            log.setDetailJson(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize SCIM audit details", e);
        }
        auditMapper.insert(log);
    }

    private MonitorScimUser requireUser(Long tenantId, String scimId) {
        MonitorScimUser user = findUser(tenantId, scimId);
        if (user == null) throw notFound("User");
        return user;
    }

    private MonitorScimUser findUser(Long tenantId, String scimId) {
        return scimUserMapper.selectOne(Wrappers.<MonitorScimUser>lambdaQuery()
                .eq(MonitorScimUser::getTenantId, tenantId).eq(MonitorScimUser::getScimId, scimId).last("LIMIT 1"));
    }

    private MonitorScimGroup requireGroup(Long tenantId, String scimId) {
        MonitorScimGroup group = findGroup(tenantId, scimId);
        if (group == null) throw notFound("Group");
        return group;
    }

    private MonitorScimGroup findGroup(Long tenantId, String scimId) {
        return scimGroupMapper.selectOne(Wrappers.<MonitorScimGroup>lambdaQuery()
                .eq(MonitorScimGroup::getTenantId, tenantId).eq(MonitorScimGroup::getScimId, scimId).last("LIMIT 1"));
    }

    private ObjectNode listResponse(List<ObjectNode> resources, int startIndex, int count) {
        int safeStart = Math.max(1, startIndex);
        int safeCount = Math.max(0, Math.min(MAX_PAGE_SIZE, count));
        int from = Math.min(resources.size(), safeStart - 1);
        int to = Math.min(resources.size(), from + safeCount);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("schemas").add(LIST_SCHEMA);
        response.put("totalResults", resources.size());
        response.put("startIndex", safeStart);
        response.put("itemsPerPage", to - from);
        ArrayNode page = response.putArray("Resources");
        for (int index = from; index < to; index++) page.add(resources.get(index));
        return response;
    }

    private boolean matchesFilter(ObjectNode resource, String filter) {
        if (!StringUtils.hasText(filter)) return true;
        Matcher matcher = FILTER.matcher(filter.trim());
        if (!matcher.matches()) throw badRequest("only SCIM eq filters are supported");
        String field = matcher.group(1);
        String value = matcher.group(2);
        JsonNode actual = resource.get(field);
        return actual != null && actual.asText("").equalsIgnoreCase(value);
    }

    private ArrayNode requireOperations(JsonNode input) {
        JsonNode operations = input.get("Operations");
        if (operations == null || !operations.isArray()) throw badRequest("SCIM PATCH requires Operations array");
        return (ArrayNode) operations;
    }

    private String requiredText(JsonNode node, String field, int maxLength) {
        String value = optionalText(node, field, maxLength);
        if (!StringUtils.hasText(value)) throw badRequest(field + " is required");
        return value;
    }

    private String optionalText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw badRequest(field + " must be a string");
        String text = value.asText().trim();
        if (text.length() > maxLength) throw badRequest(field + " exceeds maximum length " + maxLength);
        return text.isEmpty() ? null : text;
    }

    private String primaryEmail(JsonNode node) {
        JsonNode emails = node.path("emails");
        if (!emails.isArray()) return null;
        String fallback = null;
        for (JsonNode email : emails) {
            String value = optionalText(email, "value", 100);
            if (value == null) continue;
            if (email.path("primary").asBoolean(false)) return value;
            if (fallback == null) fallback = value;
        }
        return fallback;
    }

    private boolean activeValue(JsonNode node, boolean fallback) {
        JsonNode active = node.get("active");
        if (active == null) return fallback;
        if (!active.isBoolean()) throw badRequest("active must be a boolean");
        return active.booleanValue();
    }

    private String firstText(JsonNode node, String field, String fallback, String finalFallback) {
        String value = optionalText(node, field, 200);
        if (value != null) return value;
        return StringUtils.hasText(fallback) ? fallback : finalFallback;
    }

    private String randomPassword() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String teamKey(String displayName) {
        String slug = displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        return (slug.isEmpty() ? "scim-team" : slug) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseStatusException notFound(String resource) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, resource + " not found");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
