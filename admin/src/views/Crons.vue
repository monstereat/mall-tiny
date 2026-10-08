<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { monitorApi, type MonitorCron, type MonitorCronCheckIn } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const crons = ref<MonitorCron[]>([]);
const loading = ref(false);
const editorVisible = ref(false);
const historyVisible = ref(false);
const editing = ref<MonitorCron | null>(null);
const selected = ref<MonitorCron | null>(null);
const checkIns = ref<MonitorCronCheckIn[]>([]);
const form = reactive({
  name: '', slug: '', scheduleType: 'interval' as 'interval' | 'crontab', schedule: '1h',
  timezone: 'UTC', checkinMarginSeconds: 60, maxRuntimeSeconds: 1800,
  failureThreshold: 1, recoveryThreshold: 1, status: 'active' as 'active' | 'disabled'
});

async function load() {
  if (!projects.currentKey) { crons.value = []; return; }
  loading.value = true;
  try { crons.value = await monitorApi.crons(projects.currentKey); }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : 'Cron monitors 加载失败'); }
  finally { loading.value = false; }
}

function openCreate() {
  editing.value = null;
  Object.assign(form, { name: '', slug: '', scheduleType: 'interval', schedule: '1h', timezone: 'UTC',
    checkinMarginSeconds: 60, maxRuntimeSeconds: 1800, failureThreshold: 1, recoveryThreshold: 1, status: 'active' });
  editorVisible.value = true;
}

function openEdit(cron: MonitorCron) {
  editing.value = cron;
  Object.assign(form, {
    name: cron.name, slug: cron.slug, scheduleType: cron.scheduleType, schedule: cron.schedule,
    timezone: cron.timezone, checkinMarginSeconds: cron.checkinMarginSeconds,
    maxRuntimeSeconds: cron.maxRuntimeSeconds, failureThreshold: cron.failureThreshold,
    recoveryThreshold: cron.recoveryThreshold, status: cron.status
  });
  editorVisible.value = true;
}

async function save() {
  if (!projects.currentKey) return;
  const payload = { ...form };
  try {
    if (editing.value) await monitorApi.updateCron(projects.currentKey, editing.value.id, payload);
    else await monitorApi.createCron(projects.currentKey, payload);
    editorVisible.value = false;
    ElMessage.success('Cron monitor 已保存');
    await load();
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : 'Cron monitor 保存失败'); }
}

async function remove(cron: MonitorCron) {
  if (!projects.currentKey) return;
  try {
    await ElMessageBox.confirm(`删除 ${cron.name} 及其 Check-in 历史？`, '删除 Cron monitor');
    await monitorApi.deleteCron(projects.currentKey, cron.id);
    ElMessage.success('Cron monitor 已删除');
    await load();
  } catch (e) { if (e instanceof Error && e.message) ElMessage.error(e.message); }
}

async function showHistory(cron: MonitorCron) {
  if (!projects.currentKey) return;
  selected.value = cron;
  historyVisible.value = true;
  try { checkIns.value = await monitorApi.cronCheckIns(projects.currentKey, cron.id); }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : 'Check-in 历史加载失败'); }
}

function tagType(status: string) {
  if (status === 'ok' || status === 'active') return 'success';
  if (status === 'warning' || status === 'in_progress') return 'warning';
  if (status === 'error' || status === 'missed' || status === 'timed_out') return 'danger';
  return 'info';
}

watch(() => projects.currentKey, load);
onMounted(load);
</script>

<template>
  <div class="crons-page">
    <div class="page-header">
      <div><h2>Cron Monitors</h2><p>跟踪定时任务是否按计划运行、完成或超时。Check-in 使用当前项目的 Ingest Key。</p></div>
      <el-button type="primary" :disabled="!projects.currentKey" @click="openCreate">新建 Monitor</el-button>
    </div>
    <el-table v-loading="loading" :data="crons" stripe>
      <el-table-column prop="name" label="任务" min-width="160" />
      <el-table-column prop="slug" label="Slug" min-width="140" />
      <el-table-column label="Schedule" min-width="180"><template #default="scope"><code>{{ scope.row.scheduleType }} · {{ scope.row.schedule }}</code><small>{{ scope.row.timezone }}</small></template></el-table-column>
      <el-table-column label="健康状态" width="140"><template #default="scope"><el-tag :type="tagType(scope.row.healthStatus)">{{ scope.row.healthStatus }}</el-tag></template></el-table-column>
      <el-table-column label="Monitor" width="110"><template #default="scope"><el-tag :type="tagType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="lastCheckinAt" label="最近 Check-in" min-width="190" />
      <el-table-column prop="nextCheckinAt" label="下次预期" min-width="190" />
      <el-table-column label="操作" width="190" fixed="right"><template #default="scope"><el-button link @click="showHistory(scope.row)">历史</el-button><el-button link @click="openEdit(scope.row)">编辑</el-button><el-button link type="danger" @click="remove(scope.row)">删除</el-button></template></el-table-column>
      <template #empty><el-empty description="当前项目还没有 Cron monitor" /></template>
    </el-table>

    <el-dialog v-model="editorVisible" :title="editing ? '编辑 Cron monitor' : '新建 Cron monitor'" width="560px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="128" /></el-form-item>
        <el-form-item label="Slug"><el-input v-model="form.slug" maxlength="128" :disabled="Boolean(editing)" /></el-form-item>
        <div class="form-row"><el-form-item label="Schedule 类型"><el-select v-model="form.scheduleType"><el-option label="Interval" value="interval" /><el-option label="Crontab" value="crontab" /></el-select></el-form-item><el-form-item label="Schedule"><el-input v-model="form.schedule" :placeholder="form.scheduleType === 'interval' ? '1h, 30m, 1d' : '0 9 * * 1-5'" /></el-form-item></div>
        <el-form-item label="时区（IANA）"><el-input v-model="form.timezone" placeholder="UTC / Asia/Shanghai" /></el-form-item>
        <div class="form-row"><el-form-item label="漏报宽限（秒）"><el-input-number v-model="form.checkinMarginSeconds" :min="60" :max="2419200" /></el-form-item><el-form-item label="最大运行时长（秒）"><el-input-number v-model="form.maxRuntimeSeconds" :min="60" :max="2419200" /></el-form-item></div>
        <div class="form-row"><el-form-item label="失败阈值"><el-input-number v-model="form.failureThreshold" :min="1" :max="100" /></el-form-item><el-form-item label="恢复阈值"><el-input-number v-model="form.recoveryThreshold" :min="1" :max="100" /></el-form-item></div>
        <el-form-item label="状态"><el-select v-model="form.status"><el-option label="启用" value="active" /><el-option label="停用" value="disabled" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="editorVisible = false">取消</el-button><el-button type="primary" :disabled="!form.name || !form.slug || !form.schedule" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="historyVisible" :title="`${selected?.name || 'Cron'} Check-in 历史`" width="900px">
      <el-table :data="checkIns" stripe>
        <el-table-column prop="startedAt" label="开始时间" min-width="190" />
        <el-table-column prop="completedAt" label="结束时间" min-width="190" />
        <el-table-column prop="environment" label="环境" width="120" />
        <el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="tagType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="durationMs" label="耗时（ms）" width="130" />
        <el-table-column prop="message" label="详情" min-width="180" />
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-header h2{margin:0 0 6px}.page-header p{margin:0;color:#64748b}.page-header small{display:block;color:#94a3b8}.form-row{display:grid;grid-template-columns:1fr 1fr;gap:16px}.crons-page :deep(.el-form-item){margin-bottom:14px}
</style>
