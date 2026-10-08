<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { monitorApi, type MonitorProjectMember, type ProjectCredentials } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const dialog = ref(false);
const credentials = ref<ProjectCredentials | null>(null);
const form = reactive({ name: '', projectKey: '', platform: 'web' });
const memberDialog = ref(false);
const memberProjectKey = ref('');
const members = ref<MonitorProjectMember[]>([]);
const memberForm = reactive<{ adminId: number | undefined; role: 'MEMBER' | 'VIEWER' }>({ adminId: undefined, role: 'MEMBER' });

async function openMembers(projectKey: string) {
  try {
    memberProjectKey.value = projectKey;
    members.value = await monitorApi.projectMembers(projectKey);
    memberForm.adminId = undefined;
    memberForm.role = 'MEMBER';
    memberDialog.value = true;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '无法读取项目成员');
  }
}

async function saveMember() {
  if (!memberForm.adminId) {
    ElMessage.warning('请输入已获得监控后台权限的管理员 ID');
    return;
  }
  try {
    await monitorApi.saveProjectMember(memberProjectKey.value, {
      adminId: memberForm.adminId,
      role: memberForm.role
    });
    members.value = await monitorApi.projectMembers(memberProjectKey.value);
    memberForm.adminId = undefined;
    ElMessage.success('成员权限已保存');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存成员权限失败');
  }
}

async function updateMemberRole(member: MonitorProjectMember, role: 'MEMBER' | 'VIEWER') {
  try {
    await monitorApi.saveProjectMember(memberProjectKey.value, { adminId: member.adminId, role });
    members.value = await monitorApi.projectMembers(memberProjectKey.value);
    ElMessage.success('成员角色已更新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新成员角色失败');
    members.value = await monitorApi.projectMembers(memberProjectKey.value);
  }
}

async function transferOwner(member: MonitorProjectMember) {
  try {
    await ElMessageBox.confirm(
      `将项目 Owner 转移给管理员 ${member.adminId}？当前 Owner 将降为普通成员。`,
      '转移项目 Owner',
      { confirmButtonText: '确认转移', cancelButtonText: '取消', type: 'warning' }
    );
    await monitorApi.transferProjectOwner(memberProjectKey.value, member.adminId);
    memberDialog.value = false;
    members.value = [];
    ElMessage.success('项目 Owner 已转移');
  } catch (e) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '转移 Owner 失败');
  }
}

async function removeMember(member: MonitorProjectMember) {
  try {
    await ElMessageBox.confirm(`移除管理员 ${member.adminId} 的项目访问权限？`, '移除成员');
    await monitorApi.removeProjectMember(memberProjectKey.value, member.adminId);
    members.value = members.value.filter(item => item.adminId !== member.adminId);
    ElMessage.success('成员已移除');
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  }
}

async function createProject() {
  try {
    credentials.value = await monitorApi.createProject({ ...form });
    ElMessage.success('项目创建成功，请立即保存密钥');
    dialog.value = false;
    await projects.load();
    projects.setCurrent(credentials.value.project.projectKey);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  }
}

async function rotate(projectKey: string) {
  try {
    await ElMessageBox.confirm('轮换后旧 Ingest/Release Key 将立即失效，确认继续？', '轮换密钥');
    credentials.value = await monitorApi.rotateProjectKeys(projectKey);
    ElMessage.success('密钥已轮换，请立即保存新密钥');
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  }
}
</script>

<template>
  <section>
    <div class="toolbar">
      <h1 class="page-title" style="margin:0">Projects</h1>
      <el-button type="primary" @click="dialog=true">创建项目</el-button>
    </div>

    <div v-if="credentials" class="panel">
      <el-alert type="warning" :closable="false" title="密钥只在创建/轮换时展示，请立即保存。" />
      <el-descriptions :column="1" border style="margin-top:14px">
        <el-descriptions-item label="Project Key">{{ credentials.project.projectKey }}</el-descriptions-item>
        <el-descriptions-item label="Ingest Key"><code>{{ credentials.ingestKey }}</code></el-descriptions-item>
        <el-descriptions-item label="Release Key"><code>{{ credentials.releaseKey }}</code></el-descriptions-item>
      </el-descriptions>
      <el-button style="margin-top:12px" @click="credentials=null">我已保存</el-button>
    </div>

    <div class="panel">
      <el-table :data="projects.projects">
        <el-table-column prop="name" label="项目" />
        <el-table-column prop="projectKey" label="Project Key" min-width="180" />
        <el-table-column prop="platform" label="Platform" width="120" />
        <el-table-column label="操作" width="250">
          <template #default="{ row }">
            <el-button link type="primary" @click="projects.setCurrent(row.projectKey)">进入</el-button>
            <el-button link type="primary" @click="openMembers(row.projectKey)">成员管理</el-button>
            <el-button link type="danger" @click="rotate(row.projectKey)">轮换密钥</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="创建监控项目" width="520px">
      <el-form label-width="100px">
        <el-form-item label="项目名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="Project Key"><el-input v-model="form.projectKey" placeholder="例如 dcrm-web" /></el-form-item>
        <el-form-item label="Platform">
          <el-select v-model="form.platform" style="width:100%">
            <el-option label="Web" value="web" />
            <el-option label="H5" value="h5" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="createProject">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="memberDialog" :title="`项目成员 · ${memberProjectKey}`" width="620px">
      <el-form inline>
        <el-form-item label="管理员 ID">
          <el-input-number v-model="memberForm.adminId" :min="1" :precision="0" placeholder="管理员 ID" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="memberForm.role" style="width:140px">
            <el-option label="成员（可写）" value="MEMBER" />
            <el-option label="查看者（只读）" value="VIEWER" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="saveMember">添加/保存</el-button></el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" title="仅可邀请已获得 mall-tiny 监控后台 RBAC 权限的启用管理员。" />
      <el-table :data="members" style="margin-top:14px">
        <el-table-column prop="adminId" label="管理员 ID" />
        <el-table-column label="角色" width="200">
          <template #default="{ row }">
            <el-tag v-if="row.role === 'OWNER'" type="warning">所有者</el-tag>
            <el-select v-else :model-value="row.role" @change="(role: 'MEMBER' | 'VIEWER') => updateMemberRole(row, role)">
              <el-option label="成员（可写）" value="MEMBER" />
              <el-option label="查看者（只读）" value="VIEWER" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <template v-if="row.role !== 'OWNER'">
              <el-button link type="warning" @click="transferOwner(row)">转移 Owner</el-button>
              <el-button link type="danger" @click="removeMember(row)">移除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </section>
</template>
