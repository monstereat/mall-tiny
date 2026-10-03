<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { monitorApi, type MonitorScimToken, type MonitorScimTokenCreated, type MonitorTeam, type MonitorTeamMember, type MonitorTenant, type MonitorTenantAuditLog, type MonitorTenantMember, type MonitorTenantRole, type MonitorTenantPermission, type MonitorTenantSamlConfig } from '../api/monitor';

const tenants = ref<MonitorTenant[]>([]);
const selectedId = ref<number>();
const teams = ref<MonitorTeam[]>([]);
const teamMembers = ref<Record<number, MonitorTeamMember[]>>({});
const members = ref<MonitorTenantMember[]>([]);
const roles = ref<MonitorTenantRole[]>([]);
const auditLogs = ref<MonitorTenantAuditLog[]>([]);
const scimTokens = ref<MonitorScimToken[]>([]);
const samlConfig = ref<MonitorTenantSamlConfig>();
const samlForm = reactive({ enabled: false, metadataXml: '', emailAttribute: 'email' });
const loading = ref(false);
const ownerMessage = ref('');
const tenantDialog = ref(false);
const teamDialog = ref(false);
const memberDialog = ref(false);
const teamMemberDialog = ref(false);
const tenantRoleDialog = ref(false);
const scimTokenDialog = ref(false);
const scimTokenValueDialog = ref(false);
const tenantForm = reactive({ name: '', tenantKey: '' });
const teamForm = reactive({ name: '', teamKey: '' });
const memberForm = reactive<{ adminId?: number; roleChoice: string }>({ roleChoice: 'builtin:MEMBER' });
const tenantRoleForm = reactive<{ id?: number; roleKey: string; name: string; permissions: MonitorTenantPermission[] }>({ roleKey: '', name: '', permissions: [] });
const teamMemberForm = reactive<{ teamId?: number; adminId?: number; role: MonitorTeamMember['role'] }>({ role: 'MEMBER' });
const scimTokenName = ref('');
const createdScimToken = ref<MonitorScimTokenCreated>();
const selectedTeam = computed(() => teams.value.find(item => item.id === teamMemberForm.teamId));
const selectedTenant = computed(() => tenants.value.find(item => item.id === selectedId.value));
const origin = window.location.origin;
const tenantPermissionOptions: Array<{ value: MonitorTenantPermission; label: string }> = [
  { value: 'TENANT_MEMBER_READ', label: '读取组织成员' },
  { value: 'TENANT_MEMBER_MANAGE', label: '管理非 Owner 组织成员' },
  { value: 'TEAM_MANAGE', label: '管理团队及团队成员' },
  { value: 'AUDIT_READ', label: '读取组织审计日志' },
  { value: 'SCIM_MANAGE', label: '管理 SCIM 凭据' },
  { value: 'ALERT_ROUTE_READ', label: '读取告警通知路由' },
  { value: 'ALERT_ROUTE_MANAGE', label: '管理告警通知路由' }
];

async function loadTenants() {
  loading.value = true;
  try {
    tenants.value = await monitorApi.tenants();
    if (!tenants.value.some(item => item.id === selectedId.value)) selectedId.value = tenants.value[0]?.id;
    if (selectedId.value) await loadTenantData();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '组织列表加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadTenantData() {
  if (!selectedId.value) return;
  const tenantId = selectedId.value;
  ownerMessage.value = '';
  const [teamResult, memberResult, auditResult, scimResult, samlResult, rolesResult] = await Promise.allSettled([
    monitorApi.tenantTeams(tenantId),
    monitorApi.tenantMembers(tenantId),
    monitorApi.tenantAuditLogs(tenantId),
    monitorApi.tenantScimTokens(tenantId),
    monitorApi.tenantSamlConfig(tenantId),
    monitorApi.tenantRoles(tenantId)
  ]);
  teams.value = teamResult.status === 'fulfilled' ? teamResult.value : [];
  const teamMemberResults = await Promise.allSettled(teams.value.map(team => monitorApi.tenantTeamMembers(tenantId, team.id)));
  teamMembers.value = Object.fromEntries(teams.value.map((team, index) => [
    team.id,
    teamMemberResults[index]?.status === 'fulfilled' ? teamMemberResults[index].value : []
  ]));
  members.value = memberResult.status === 'fulfilled' ? memberResult.value : [];
  roles.value = rolesResult.status === 'fulfilled' ? rolesResult.value : [];
  auditLogs.value = auditResult.status === 'fulfilled' ? auditResult.value : [];
  scimTokens.value = scimResult.status === 'fulfilled' ? scimResult.value : [];
  if (samlResult.status === 'fulfilled') {
    samlConfig.value = samlResult.value;
    Object.assign(samlForm, {
      enabled: samlResult.value.enabled,
      metadataXml: samlResult.value.metadataXml || '',
      emailAttribute: samlResult.value.emailAttribute || 'email'
    });
  } else {
    samlConfig.value = undefined;
  }
  if (memberResult.status === 'rejected' || auditResult.status === 'rejected'
      || scimResult.status === 'rejected' || samlResult.status === 'rejected'
      || rolesResult.status === 'rejected'
      || teamMemberResults.some(result => result.status === 'rejected')) {
    ownerMessage.value = '当前账号未获准读取部分组织管理信息；可用操作由 Tenant Owner 分配的组织角色决定。';
  }
}

async function saveSamlConfig() {
  if (!selectedId.value) return;
  try {
    samlConfig.value = await monitorApi.saveTenantSamlConfig(selectedId.value, { ...samlForm });
    ElMessage.success('SAML SSO 配置已保存');
    await loadTenantData();
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : 'SAML 配置保存失败'); }
}

async function createScimToken() {
  if (!selectedId.value || !scimTokenName.value.trim()) return;
  try {
    createdScimToken.value = await monitorApi.createTenantScimToken(selectedId.value, scimTokenName.value.trim());
    scimTokenName.value = '';
    scimTokenDialog.value = false;
    scimTokenValueDialog.value = true;
    await loadTenantData();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : 'SCIM token 创建失败');
  }
}

async function copyScimToken() {
  const token = createdScimToken.value?.value;
  if (!token) return;
  try {
    await navigator.clipboard.writeText(token);
    ElMessage.success('已复制');
  } catch {
    ElMessage.error('复制失败，请手动选择并复制 token');
  }
}

async function revokeScimToken(token: MonitorScimToken) {
  if (!selectedId.value) return;
  try {
    await ElMessageBox.confirm(`撤销 SCIM token「${token.name}」？已配置该 token 的身份提供方将立即停止同步。`, '撤销 SCIM token');
    await monitorApi.revokeTenantScimToken(selectedId.value, token.id);
    await loadTenantData();
    ElMessage.success('SCIM token 已撤销');
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  }
}

function manageTeamMembers(team: MonitorTeam) {
  teamMemberForm.teamId = team.id;
  teamMemberForm.adminId = undefined;
  teamMemberForm.role = 'MEMBER';
  teamMemberDialog.value = true;
}

async function saveTeamMember() {
  if (!selectedId.value || !teamMemberForm.teamId || !teamMemberForm.adminId) return;
  try {
    await monitorApi.saveTenantTeamMember(selectedId.value, teamMemberForm.teamId, {
      adminId: teamMemberForm.adminId,
      role: teamMemberForm.role
    });
    teamMemberForm.adminId = undefined;
    await loadTenantData();
    ElMessage.success('团队成员权限已保存');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '团队成员权限保存失败');
  }
}

async function removeTeamMember(member: MonitorTeamMember) {
  if (!selectedId.value || !teamMemberForm.teamId) return;
  try {
    await ElMessageBox.confirm(`移除管理员 ${member.adminId} 的团队访问权限？`, '移除团队成员');
    await monitorApi.removeTenantTeamMember(selectedId.value, teamMemberForm.teamId, member.adminId);
    await loadTenantData();
    ElMessage.success('团队成员已移除');
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  }
}

async function createTenant() {
  try {
    const tenant = await monitorApi.createTenant({ ...tenantForm });
    tenantForm.name = '';
    tenantForm.tenantKey = '';
    tenantDialog.value = false;
    await loadTenants();
    selectedId.value = tenant.id;
    await loadTenantData();
    ElMessage.success('组织已创建');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '组织创建失败');
  }
}

async function createTeam() {
  if (!selectedId.value) return;
  try {
    await monitorApi.createTenantTeam(selectedId.value, { ...teamForm });
    teamForm.name = '';
    teamForm.teamKey = '';
    teamDialog.value = false;
    await loadTenantData();
    ElMessage.success('团队已创建');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '团队创建失败');
  }
}

async function saveMember() {
  if (!selectedId.value || !memberForm.adminId) return;
  try {
    const customRoleId = memberForm.roleChoice.startsWith('custom:') ? Number(memberForm.roleChoice.slice(7)) : undefined;
    const role = customRoleId ? 'MEMBER' : memberForm.roleChoice.slice(8) as MonitorTenantMember['role'];
    await monitorApi.saveTenantMember(selectedId.value, { adminId: memberForm.adminId, role, customRoleId });
    memberDialog.value = false;
    memberForm.adminId = undefined;
    await loadTenantData();
    ElMessage.success('成员权限已保存');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '成员权限保存失败');
  }
}

function openMemberDialog() {
  memberForm.adminId = undefined;
  memberForm.roleChoice = 'builtin:MEMBER';
  memberDialog.value = true;
}

function openTenantRoleDialog(role?: MonitorTenantRole) {
  tenantRoleForm.id = role?.id;
  tenantRoleForm.roleKey = role?.roleKey ?? '';
  tenantRoleForm.name = role?.name ?? '';
  tenantRoleForm.permissions = role ? [...role.permissions] : [];
  tenantRoleDialog.value = true;
}

async function saveTenantRole() {
  if (!selectedId.value) return;
  try {
    const payload = { roleKey: tenantRoleForm.roleKey.trim(), name: tenantRoleForm.name.trim(), permissions: tenantRoleForm.permissions };
    if (tenantRoleForm.id) await monitorApi.updateTenantRole(selectedId.value, tenantRoleForm.id, payload);
    else await monitorApi.createTenantRole(selectedId.value, payload);
    tenantRoleDialog.value = false;
    await loadTenantData();
    ElMessage.success('自定义组织角色已保存');
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '角色保存失败'); }
}

async function deleteTenantRole(role: MonitorTenantRole) {
  if (!selectedId.value) return;
  try {
    await ElMessageBox.confirm(`删除角色「${role.name}」？已分配该角色的成员必须先改派。`, '删除组织角色');
    await monitorApi.deleteTenantRole(selectedId.value, role.id);
    await loadTenantData();
    ElMessage.success('自定义组织角色已删除');
  } catch (e) { if (e instanceof Error && e.message) ElMessage.error(e.message); }
}

function tenantMemberRoleLabel(member: MonitorTenantMember) {
  return roles.value.find(role => role.id === member.customRoleId)?.name ?? member.role;
}

function tenantRolePermissionLabels(permissions: MonitorTenantPermission[]) {
  return permissions.map(key => tenantPermissionOptions.find(option => option.value === key)?.label ?? key).join('、') || '无';
}

async function removeMember(member: MonitorTenantMember) {
  if (!selectedId.value) return;
  try {
    await ElMessageBox.confirm(`移除管理员 ${member.adminId} 的组织访问权限？`, '移除组织成员');
    await monitorApi.removeTenantMember(selectedId.value, member.adminId);
    await loadTenantData();
    ElMessage.success('组织成员已移除');
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  }
}

function formatDetails(raw: string) {
  try { return JSON.stringify(JSON.parse(raw)); } catch { return raw; }
}

watch(selectedId, () => { void loadTenantData(); });
onMounted(loadTenants);
</script>

<template>
  <div v-loading="loading" class="organization-page">
    <div class="page-header">
      <div>
        <h2>组织管理</h2>
        <p>管理 Tenant、团队、组织成员和变更审计。Issue 仍可直接解决或忽略，无需负责人分配。</p>
      </div>
      <el-button type="primary" @click="tenantDialog = true">创建组织</el-button>
    </div>

    <el-empty v-if="!tenants.length" description="当前账号尚未加入组织" />
    <template v-else>
      <el-card shadow="never" class="tenant-card">
        <div class="tenant-select">
          <span>组织</span>
          <el-select v-model="selectedId" style="width: 320px" placeholder="选择组织">
            <el-option v-for="tenant in tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" />
          </el-select>
          <code v-if="selectedTenant">{{ selectedTenant.tenantKey }}</code>
        </div>
      </el-card>

      <el-alert v-if="ownerMessage" :title="ownerMessage" type="info" :closable="false" class="owner-note" />

      <el-tabs>
        <el-tab-pane label="团队">
          <div class="tab-actions"><el-button type="primary" plain @click="teamDialog = true">新建团队</el-button></div>
          <el-table :data="teams" stripe>
            <el-table-column prop="name" label="名称" />
          <el-table-column prop="teamKey" label="Key" />
          <el-table-column label="类型"><template #default="scope">{{ scope.row.isDefault ? '默认团队' : '团队' }}</template></el-table-column>
          <el-table-column label="成员" width="130"><template #default="scope"><el-button link type="primary" @click="manageTeamMembers(scope.row)">管理成员 ({{ teamMembers[scope.row.id]?.length ?? 0 }})</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="成员">
          <div class="tab-actions"><el-button type="primary" plain @click="openMemberDialog">添加或更新成员</el-button></div>
          <el-table :data="members" stripe>
            <el-table-column prop="adminId" label="管理员 ID" />
            <el-table-column label="角色"><template #default="scope">{{ tenantMemberRoleLabel(scope.row) }}</template></el-table-column>
            <el-table-column label="操作" width="120"><template #default="scope"><el-button link type="danger" @click="removeMember(scope.row)">移除</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="自定义角色">
          <div class="tab-actions"><el-button type="primary" plain @click="openTenantRoleDialog()">创建角色</el-button></div>
          <el-alert title="角色仅控制组织管理能力。自定义角色不会获得 Owner、数据删除或 SAML 配置权限；成员项目访问级别固定按 MEMBER 处理。" type="info" :closable="false" class="owner-note" />
          <el-table :data="roles" stripe>
            <el-table-column prop="name" label="角色名称" />
            <el-table-column prop="roleKey" label="Role Key" />
            <el-table-column label="组织权限" min-width="280"><template #default="scope">{{ tenantRolePermissionLabels(scope.row.permissions) }}</template></el-table-column>
            <el-table-column label="操作" width="150"><template #default="scope"><el-button link type="primary" @click="openTenantRoleDialog(scope.row)">编辑</el-button><el-button link type="danger" @click="deleteTenantRole(scope.row)">删除</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="审计日志">
          <el-table :data="auditLogs" stripe>
            <el-table-column prop="createTime" label="时间" width="190" />
            <el-table-column prop="actorAdminId" label="操作者 ID" width="120" />
            <el-table-column prop="action" label="操作" width="190" />
            <el-table-column prop="resourceType" label="对象类型" width="120" />
            <el-table-column prop="resourceId" label="对象 ID" width="120" />
            <el-table-column label="变更详情" min-width="260"><template #default="scope"><code>{{ formatDetails(scope.row.detailJson) }}</code></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="SCIM 同步">
          <div class="tab-actions"><el-button type="primary" plain @click="scimTokenDialog = true">创建 SCIM token</el-button></div>
          <el-alert title="SCIM 2.0 端点使用组织级 Bearer token；明文只在创建时显示一次。" type="info" :closable="false" class="owner-note" />
          <el-table :data="scimTokens" stripe>
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="createTime" label="创建时间" width="190" />
            <el-table-column prop="lastUsedAt" label="最近同步" width="190" />
            <el-table-column label="状态" width="100"><template #default="scope">{{ scope.row.revokedAt ? '已撤销' : '有效' }}</template></el-table-column>
            <el-table-column label="操作" width="100"><template #default="scope"><el-button v-if="!scope.row.revokedAt" link type="danger" @click="revokeScimToken(scope.row)">撤销</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="SAML SSO">
          <el-alert title="粘贴 IdP metadata XML。登录只允许邮箱匹配到已启用且属于此组织的成员；SP 与 IdP 都需发布并启用 Single Logout，IdP 通过 POST 调用登出 URL。" type="info" :closable="false" class="owner-note" />
          <el-form label-position="top" class="saml-form">
            <el-form-item label="启用 SAML 登录"><el-switch v-model="samlForm.enabled" /></el-form-item>
            <el-form-item label="邮箱属性名"><el-input v-model="samlForm.emailAttribute" maxlength="128" placeholder="email 或 urn:oid 属性名" /></el-form-item>
            <el-form-item label="IdP Metadata XML"><el-input v-model="samlForm.metadataXml" type="textarea" :rows="12" maxlength="200000" show-word-limit placeholder="粘贴完整 IdP metadata XML" /></el-form-item>
          </el-form>
          <el-descriptions v-if="samlConfig" border :column="1" class="saml-endpoints">
            <el-descriptions-item label="SP Metadata URL"><a :href="samlConfig.metadataUrl" target="_blank" rel="noreferrer">{{ origin + samlConfig.metadataUrl }}</a></el-descriptions-item>
            <el-descriptions-item label="登录 URL"><code>{{ origin + samlConfig.loginUrl }}</code></el-descriptions-item>
            <el-descriptions-item label="Single Logout URL"><code>{{ origin + samlConfig.logoutUrl }}</code></el-descriptions-item>
          </el-descriptions>
          <el-button type="primary" :disabled="!samlForm.metadataXml.trim()" @click="saveSamlConfig">保存 SAML 设置</el-button>
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-dialog v-model="tenantDialog" title="创建组织" width="460px">
      <el-form label-position="top"><el-form-item label="组织名称"><el-input v-model="tenantForm.name" maxlength="128" /></el-form-item><el-form-item label="组织 Key"><el-input v-model="tenantForm.tenantKey" maxlength="64" /></el-form-item></el-form>
      <template #footer><el-button @click="tenantDialog = false">取消</el-button><el-button type="primary" :disabled="!tenantForm.name || !tenantForm.tenantKey" @click="createTenant">创建</el-button></template>
    </el-dialog>
    <el-dialog v-model="teamDialog" title="创建团队" width="460px">
      <el-form label-position="top"><el-form-item label="团队名称"><el-input v-model="teamForm.name" maxlength="128" /></el-form-item><el-form-item label="团队 Key"><el-input v-model="teamForm.teamKey" maxlength="64" /></el-form-item></el-form>
      <template #footer><el-button @click="teamDialog = false">取消</el-button><el-button type="primary" :disabled="!teamForm.name || !teamForm.teamKey" @click="createTeam">创建</el-button></template>
    </el-dialog>
    <el-dialog v-model="memberDialog" title="添加或更新组织成员" width="460px">
      <el-form label-position="top"><el-form-item label="已启用的管理员 ID"><el-input-number v-model="memberForm.adminId" :min="1" /></el-form-item><el-form-item label="组织角色"><el-select v-model="memberForm.roleChoice"><el-option label="Owner" value="builtin:OWNER" /><el-option label="Member" value="builtin:MEMBER" /><el-option label="Viewer" value="builtin:VIEWER" /><el-option v-for="role in roles" :key="role.id" :label="`自定义：${role.name}`" :value="`custom:${role.id}`" /></el-select></el-form-item><el-alert v-if="memberForm.roleChoice.startsWith('custom:')" title="自定义角色使用 MEMBER 项目级访问基线。" type="info" :closable="false" /></el-form>
      <template #footer><el-button @click="memberDialog = false">取消</el-button><el-button type="primary" :disabled="!memberForm.adminId" @click="saveMember">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="tenantRoleDialog" :title="tenantRoleForm.id ? '编辑组织角色' : '创建组织角色'" width="620px">
      <el-form label-position="top">
        <el-form-item label="角色名称"><el-input v-model="tenantRoleForm.name" maxlength="64" /></el-form-item>
        <el-form-item label="Role Key"><el-input v-model="tenantRoleForm.roleKey" maxlength="64" placeholder="例如 operations_admin" /></el-form-item>
        <el-form-item label="组织权限"><el-checkbox-group v-model="tenantRoleForm.permissions" class="permission-list"><el-checkbox v-for="option in tenantPermissionOptions" :key="option.value" :value="option.value">{{ option.label }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-alert title="成员管理权限不能修改或移除 Owner，也不能授予 Owner；非 Owner 管理员只能授予 MEMBER 基线。角色管理、数据删除和 SAML 设置始终限 Owner。" type="warning" :closable="false" />
      </el-form>
      <template #footer><el-button @click="tenantRoleDialog = false">取消</el-button><el-button type="primary" :disabled="!tenantRoleForm.name.trim() || !/^[a-z][a-z0-9_-]{1,63}$/.test(tenantRoleForm.roleKey.trim())" @click="saveTenantRole">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="teamMemberDialog" :title="`${selectedTeam?.name ?? '团队'}成员`" width="620px">
      <el-form inline>
        <el-form-item label="已启用管理员 ID"><el-input-number v-model="teamMemberForm.adminId" :min="1" /></el-form-item>
        <el-form-item label="团队角色"><el-select v-model="teamMemberForm.role"><el-option label="Member" value="MEMBER" /><el-option label="Viewer" value="VIEWER" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" :disabled="!teamMemberForm.adminId" @click="saveTeamMember">添加或更新</el-button></el-form-item>
      </el-form>
      <el-table :data="teamMembers[teamMemberForm.teamId ?? 0] ?? []" stripe>
        <el-table-column prop="adminId" label="管理员 ID" />
        <el-table-column prop="role" label="角色" />
        <el-table-column label="操作" width="100"><template #default="scope"><el-button link type="danger" @click="removeTeamMember(scope.row)">移除</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="teamMemberDialog = false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="scimTokenDialog" title="创建 SCIM token" width="460px">
      <el-form label-position="top"><el-form-item label="名称"><el-input v-model="scimTokenName" maxlength="128" placeholder="例如 Okta Production" /></el-form-item></el-form>
      <template #footer><el-button @click="scimTokenDialog = false">取消</el-button><el-button type="primary" :disabled="!scimTokenName.trim()" @click="createScimToken">创建</el-button></template>
    </el-dialog>
    <el-dialog v-model="scimTokenValueDialog" title="保存 SCIM 凭据" width="620px" :close-on-click-modal="false">
      <el-alert title="此 token 只显示这一次。请复制到身份提供方的 SCIM Bearer Token 设置中。" type="warning" :closable="false" class="owner-note" />
      <p>SCIM Base URL：<code>{{ createdScimToken?.baseUrl }}</code></p>
      <el-input :model-value="createdScimToken?.value" readonly>
        <template #append><el-button @click="copyScimToken">复制</el-button></template>
      </el-input>
      <template #footer><el-button type="primary" @click="scimTokenValueDialog = false; createdScimToken = undefined">我已保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-header h2{margin:0 0 6px}.page-header p{margin:0;color:#64748b}.tenant-card{margin-bottom:16px}.tenant-select{display:flex;align-items:center;gap:14px}.tenant-select code{color:#64748b}.owner-note{margin-bottom:16px}.tab-actions{display:flex;justify-content:flex-end;margin-bottom:12px}.saml-form{max-width:900px}.saml-endpoints{margin:16px 0}.permission-list{display:grid;grid-template-columns:1fr 1fr;gap:4px 14px}
</style>
