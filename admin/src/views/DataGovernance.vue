<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { monitorApi, type MonitorDataDeletionJob } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const jobs = ref<MonitorDataDeletionJob[]>([]);
const preview = ref<MonitorDataDeletionJob>();
const loading = ref(false);
const submitting = ref(false);
const form = reactive({ from: '', to: '', userId: '' });
let pollTimer: number | undefined;

const previewCounts = computed<Record<string, number | string>>(() => {
  try { return preview.value ? JSON.parse(preview.value.previewCountsJson) : {}; }
  catch { return {}; }
});

function toLocalInput(date: Date) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function resetRange() {
  const now = Date.now();
  form.from = toLocalInput(new Date(now - 60 * 60_000));
  form.to = toLocalInput(new Date(now - 60_000));
}

async function load() {
  if (!projects.currentKey) { jobs.value = []; preview.value = undefined; return; }
  loading.value = true;
  try { jobs.value = await monitorApi.dataDeletionJobs(projects.currentKey); }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : '数据删除任务加载失败'); }
  finally { loading.value = false; }
}

async function createPreview() {
  if (!projects.currentKey || !form.from || !form.to) return;
  submitting.value = true;
  try {
    preview.value = await monitorApi.previewDataDeletion(projects.currentKey, {
      from: new Date(form.from).toISOString(),
      to: new Date(form.to).toISOString(),
      ...(form.userId.trim() ? { userId: form.userId.trim() } : {})
    });
    ElMessage.success('删除范围预览已生成，有效期 30 分钟');
    await load();
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '无法生成删除预览'); }
  finally { submitting.value = false; }
}

async function executePreview() {
  if (!projects.currentKey || !preview.value?.previewToken) return;
  const counts = Object.entries(previewCounts.value)
    .filter(([key, value]) => key !== 'countSemantics' && key !== 'replayObjectsCountSemantics' && Number(value) > 0)
    .map(([key, value]) => `${key}: ${value}`)
    .join('\n');
  try {
    await ElMessageBox.confirm(
      `即将永久删除此范围内的数据。该操作无法撤销。\n\n${counts || '预览范围内没有记录'}\n\n是否继续？`,
      '执行数据删除',
      { type: 'warning', confirmButtonText: '永久删除', cancelButtonText: '取消', distinguishCancelAndClose: true }
    );
    submitting.value = true;
    const queued = await monitorApi.executeDataDeletion(projects.currentKey, preview.value.id, preview.value.previewToken);
    preview.value = queued;
    ElMessage.success(`删除任务 #${queued.id} 已进入队列`);
    await load();
    startPolling(queued.id);
  } catch (e) {
    if (e instanceof Error && e.message && e.message !== 'cancel' && e.message !== 'close') {
      ElMessage.error(e.message);
    }
  } finally { submitting.value = false; }
}

function startPolling(jobId: number) {
  if (pollTimer) window.clearInterval(pollTimer);
  pollTimer = window.setInterval(async () => {
    if (!projects.currentKey) return;
    try {
      const latest = await monitorApi.dataDeletionJob(projects.currentKey, jobId);
      jobs.value = jobs.value.map(job => job.id === jobId ? latest : job);
      if (preview.value?.id === jobId) preview.value = latest;
      if (latest.status === 'COMPLETED' || latest.status === 'FAILED') {
        if (pollTimer) window.clearInterval(pollTimer);
        pollTimer = undefined;
        await load();
        if (latest.status === 'COMPLETED') ElMessage.success(`删除任务 #${jobId} 已完成`);
        else ElMessage.error(latest.errorMessage || `删除任务 #${jobId} 失败`);
      }
    } catch (e) { ElMessage.error(e instanceof Error ? e.message : '任务状态查询失败'); }
  }, 3000);
}

async function retry(job: MonitorDataDeletionJob) {
  if (!projects.currentKey) return;
  try {
    const queued = await monitorApi.retryDataDeletion(projects.currentKey, job.id);
    jobs.value = jobs.value.map(item => item.id === job.id ? queued : item);
    startPolling(job.id);
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '重试任务失败'); }
}

function statusType(status: string) {
  if (status === 'COMPLETED') return 'success';
  if (status === 'FAILED') return 'danger';
  if (status === 'RUNNING' || status === 'QUEUED') return 'warning';
  return 'info';
}

watch(() => projects.currentKey, () => { preview.value = undefined; void load(); });
onMounted(() => { resetRange(); void load(); });
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer); });
</script>

<template>
  <div class="governance-page">
    <div class="page-header">
      <div><h2>数据治理</h2><p>按项目、时间和用户预览并执行数据删除。仅项目 Owner 可操作。</p></div>
    </div>

    <el-alert
      title="删除会清除原始事件、Replay 对象及其索引，并重算 Issue 与聚合数据。"
      description="预览有效期为 30 分钟。用户范围最多覆盖 14 天，并包含该用户关联会话中的遥测及会话标记；未指定用户时，时间范围最多覆盖 90 天。"
      type="warning" :closable="false" show-icon class="notice"
    />

    <el-card shadow="never" class="range-card">
      <template #header><strong>创建删除预览</strong></template>
      <el-form label-position="top">
        <div class="form-row">
          <el-form-item label="开始时间"><el-date-picker v-model="form.from" type="datetime" value-format="YYYY-MM-DDTHH:mm" placeholder="选择开始时间" /></el-form-item>
          <el-form-item label="结束时间"><el-date-picker v-model="form.to" type="datetime" value-format="YYYY-MM-DDTHH:mm" placeholder="选择结束时间" /></el-form-item>
          <el-form-item label="用户 ID（可选）"><el-input v-model="form.userId" maxlength="128" placeholder="删除该用户及其关联会话" /></el-form-item>
        </div>
        <el-button type="primary" :loading="submitting" :disabled="!projects.currentKey || !form.from || !form.to" @click="createPreview">预览影响范围</el-button>
      </el-form>
    </el-card>

    <el-card v-if="preview" shadow="never" class="preview-card">
      <template #header><div class="preview-header"><strong>预览 #{{ preview.id }}</strong><el-tag :type="statusType(preview.status)">{{ preview.status }}</el-tag></div></template>
      <div class="scope">项目 {{ preview.projectKey }} · {{ new Date(preview.rangeStart).toLocaleString() }} 至 {{ new Date(preview.rangeEnd).toLocaleString() }}<template v-if="preview.userId"> · 用户 {{ preview.userId }}</template></div>
      <el-descriptions border :column="3" class="counts">
        <el-descriptions-item v-for="key in ['error_event','performance_event','behavior_event','replay_event','metric_event','profile_event','replayObjects']" :key="key" :label="key">{{ previewCounts[key] ?? 0 }}</el-descriptions-item>
      </el-descriptions>
      <p class="semantics">预览为生成时的精确计数；执行使用新快照，不包含快照之后到达的数据。</p>
      <el-button type="danger" :loading="submitting" :disabled="preview.status !== 'PREVIEW'" @click="executePreview">执行永久删除</el-button>
    </el-card>

    <el-card shadow="never" class="jobs-card">
      <template #header><strong>最近任务</strong></template>
      <el-table v-loading="loading" :data="jobs" stripe>
        <el-table-column prop="id" label="任务" width="85" />
        <el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="stage" label="阶段" min-width="190" />
        <el-table-column label="范围" min-width="300"><template #default="scope">{{ new Date(scope.row.rangeStart).toLocaleString() }} — {{ new Date(scope.row.rangeEnd).toLocaleString() }}<span v-if="scope.row.userId"> · {{ scope.row.userId }}</span></template></el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="180" />
        <el-table-column label="操作" width="110"><template #default="scope"><el-button v-if="scope.row.status === 'FAILED'" link type="primary" @click="retry(scope.row)">重试</el-button></template></el-table-column>
        <template #empty><el-empty description="当前项目没有数据删除任务" /></template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page-header{margin-bottom:18px}.page-header h2{margin:0 0 6px}.page-header p{margin:0;color:#64748b}.notice{margin-bottom:16px}.range-card,.preview-card{margin-bottom:16px}.form-row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:16px}.form-row :deep(.el-date-editor){width:100%}.preview-header{display:flex;align-items:center;justify-content:space-between}.scope{margin-bottom:16px;color:#475569}.counts{margin-bottom:12px}.semantics{color:#64748b;font-size:13px}
</style>
