<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { monitorApi, type MonitorUptime, type MonitorUptimeCheck } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const monitors = ref<MonitorUptime[]>([]);
const checks = ref<MonitorUptimeCheck[]>([]);
const loading = ref(false);
const editorVisible = ref(false);
const historyVisible = ref(false);
const editing = ref<MonitorUptime | null>(null);
const selected = ref<MonitorUptime | null>(null);
const form = reactive({
  name: '', slug: '', url: '', method: 'GET' as 'GET' | 'HEAD', intervalSeconds: 60,
  timeoutMs: 5000, expectedStatusCode: 200, failureThreshold: 1, recoveryThreshold: 1,
  status: 'active' as 'active' | 'disabled'
});

async function load() {
  if (!projects.currentKey) { monitors.value = []; return; }
  loading.value = true;
  try { monitors.value = await monitorApi.uptimes(projects.currentKey); }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : 'Uptime monitors 加载失败'); }
  finally { loading.value = false; }
}

function openCreate() {
  editing.value = null;
  Object.assign(form, { name: '', slug: '', url: '', method: 'GET', intervalSeconds: 60,
    timeoutMs: 5000, expectedStatusCode: 200, failureThreshold: 1, recoveryThreshold: 1, status: 'active' });
  editorVisible.value = true;
}

function openEdit(monitor: MonitorUptime) {
  editing.value = monitor;
  Object.assign(form, {
    name: monitor.name, slug: monitor.slug, url: monitor.url, method: monitor.method,
    intervalSeconds: monitor.intervalSeconds, timeoutMs: monitor.timeoutMs,
    expectedStatusCode: monitor.expectedStatusCode, failureThreshold: monitor.failureThreshold,
    recoveryThreshold: monitor.recoveryThreshold, status: monitor.status
  });
  editorVisible.value = true;
}

async function save() {
  if (!projects.currentKey) return;
  const payload = { ...form };
  try {
    if (editing.value) await monitorApi.updateUptime(projects.currentKey, editing.value.id, payload);
    else await monitorApi.createUptime(projects.currentKey, payload);
    editorVisible.value = false;
    ElMessage.success('Uptime monitor 已保存');
    await load();
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : 'Uptime monitor 保存失败'); }
}

async function remove(monitor: MonitorUptime) {
  if (!projects.currentKey) return;
  try {
    await ElMessageBox.confirm(`删除 ${monitor.name} 及其检查历史？`, '删除 Uptime monitor');
    await monitorApi.deleteUptime(projects.currentKey, monitor.id);
    ElMessage.success('Uptime monitor 已删除');
    await load();
  } catch (e) { if (e instanceof Error && e.message) ElMessage.error(e.message); }
}

async function showHistory(monitor: MonitorUptime) {
  if (!projects.currentKey) return;
  selected.value = monitor;
  historyVisible.value = true;
  try { checks.value = await monitorApi.uptimeChecks(projects.currentKey, monitor.id); }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : '检查历史加载失败'); }
}

function tagType(status: string) {
  if (status === 'up' || status === 'active') return 'success';
  if (status === 'warning') return 'warning';
  if (status === 'down') return 'danger';
  return 'info';
}

watch(() => projects.currentKey, load);
onMounted(load);
</script>

<template>
  <div class="uptime-page">
    <div class="page-header">
      <div><h2>Uptime Monitors</h2><p>按固定间隔检查公开 HTTP/HTTPS 地址的状态码与响应时间。</p></div>
      <el-button type="primary" :disabled="!projects.currentKey" @click="openCreate">新建 Monitor</el-button>
    </div>
    <el-alert
      title="安全限制"
      description="仅允许不带认证信息的 GET/HEAD 公网检查；私网、环回、链路本地地址会被拒绝，HTTP 重定向不会跟随。"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />
    <el-table v-loading="loading" :data="monitors" stripe>
      <el-table-column prop="name" label="监控目标" min-width="150" />
      <el-table-column prop="url" label="URL" min-width="260" show-overflow-tooltip />
      <el-table-column label="健康状态" width="130"><template #default="scope"><el-tag :type="tagType(scope.row.currentStatus)">{{ scope.row.currentStatus }}</el-tag></template></el-table-column>
      <el-table-column label="最近检查" min-width="190"><template #default="scope">{{ scope.row.checkedAt ? new Date(scope.row.checkedAt).toLocaleString() : '尚无检查' }}</template></el-table-column>
      <el-table-column label="状态码 / 延迟" width="150"><template #default="scope">{{ scope.row.lastStatusCode || '—' }} / {{ scope.row.lastDurationMs ?? '—' }} ms</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="tagType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="190" fixed="right"><template #default="scope"><el-button link @click="showHistory(scope.row)">历史</el-button><el-button link @click="openEdit(scope.row)">编辑</el-button><el-button link type="danger" @click="remove(scope.row)">删除</el-button></template></el-table-column>
      <template #empty><el-empty description="当前项目还没有 Uptime monitor" /></template>
    </el-table>

    <el-dialog v-model="editorVisible" :title="editing ? '编辑 Uptime monitor' : '新建 Uptime monitor'" width="600px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="128" /></el-form-item>
        <el-form-item label="Slug"><el-input v-model="form.slug" maxlength="128" :disabled="Boolean(editing)" /></el-form-item>
        <el-form-item label="公网 URL"><el-input v-model="form.url" placeholder="https://example.com/health" /></el-form-item>
        <div class="form-row"><el-form-item label="方法"><el-select v-model="form.method"><el-option label="GET" value="GET"/><el-option label="HEAD" value="HEAD"/></el-select></el-form-item><el-form-item label="检查间隔（秒）"><el-input-number v-model="form.intervalSeconds" :min="30" :max="86400" /></el-form-item></div>
        <div class="form-row"><el-form-item label="超时（毫秒）"><el-input-number v-model="form.timeoutMs" :min="500" :max="30000" /></el-form-item><el-form-item label="预期状态码"><el-input-number v-model="form.expectedStatusCode" :min="100" :max="599" /></el-form-item></div>
        <div class="form-row"><el-form-item label="失败阈值"><el-input-number v-model="form.failureThreshold" :min="1" :max="100" /></el-form-item><el-form-item label="恢复阈值"><el-input-number v-model="form.recoveryThreshold" :min="1" :max="100" /></el-form-item></div>
        <el-form-item label="状态"><el-select v-model="form.status"><el-option label="启用" value="active"/><el-option label="停用" value="disabled"/></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="editorVisible = false">取消</el-button><el-button type="primary" :disabled="!form.name || !form.slug || !form.url" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="historyVisible" :title="`${selected?.name || 'Uptime'} 检查历史`" width="850px">
      <el-table :data="checks" stripe>
        <el-table-column prop="checkedAt" label="检查时间" min-width="190" />
        <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="tagType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="responseStatus" label="HTTP 状态码" width="130" />
        <el-table-column prop="durationMs" label="耗时（ms）" width="130" />
        <el-table-column prop="message" label="详情" min-width="220" />
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-header h2{margin:0 0 6px}.page-header p{margin:0;color:#64748b}.form-row{display:grid;grid-template-columns:1fr 1fr;gap:16px}.uptime-page :deep(.el-form-item){margin-bottom:14px}
</style>
