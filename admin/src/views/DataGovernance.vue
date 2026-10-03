<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { monitorApi, type MonitorDataDeletionJob, type MonitorDataScrubbingSettings } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const jobs = ref<MonitorDataDeletionJob[]>([]);
const preview = ref<MonitorDataDeletionJob>();
const scrubbingSettings = ref<MonitorDataScrubbingSettings>();
const loading = ref(false);
const submitting = ref(false);
const settingsSaving = ref(false);
const scrubForm = reactive({ scrubEmails: false, scrubCreditCards: false, scrubIpAddresses: false, scrubPhoneNumbers: false, scrubChineseIdNumbers: false, customSensitiveFieldText: '' });
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
  if (!projects.currentKey) { jobs.value = []; preview.value = undefined; scrubbingSettings.value = undefined; return; }
  loading.value = true;
  try {
    jobs.value = await monitorApi.dataDeletionJobs(projects.currentKey);
    scrubbingSettings.value = await monitorApi.dataScrubbingSettings(projects.currentKey);
    scrubForm.scrubEmails = scrubbingSettings.value.scrubEmails;
    scrubForm.scrubCreditCards = scrubbingSettings.value.scrubCreditCards;
    scrubForm.scrubIpAddresses = scrubbingSettings.value.scrubIpAddresses;
    scrubForm.scrubPhoneNumbers = scrubbingSettings.value.scrubPhoneNumbers;
    scrubForm.scrubChineseIdNumbers = scrubbingSettings.value.scrubChineseIdNumbers;
    scrubForm.customSensitiveFieldText = scrubbingSettings.value.customSensitiveFields.join('\n');
  }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : '数据删除任务加载失败'); }
  finally { loading.value = false; }
}

async function saveScrubbingSettings() {
  if (!projects.currentKey || !scrubbingSettings.value?.canModify) return;
  settingsSaving.value = true;
  try {
    const customSensitiveFields = [...new Set(scrubForm.customSensitiveFieldText
      .split(/[\n,，;；]+/)
      .map(field => field.trim())
      .filter(Boolean))];
    scrubbingSettings.value = await monitorApi.saveDataScrubbingSettings(projects.currentKey, {
      scrubEmails: scrubForm.scrubEmails,
      scrubCreditCards: scrubForm.scrubCreditCards,
      scrubIpAddresses: scrubForm.scrubIpAddresses,
      scrubPhoneNumbers: scrubForm.scrubPhoneNumbers,
      scrubChineseIdNumbers: scrubForm.scrubChineseIdNumbers,
      customSensitiveFields
    });
    scrubForm.customSensitiveFieldText = scrubbingSettings.value.customSensitiveFields.join('\n');
    ElMessage.success('数据脱敏设置已保存；新上报事件将应用设置');
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '数据脱敏设置保存失败'); }
  finally { settingsSaving.value = false; }
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
      description="预览有效期为 30 分钟。用户范围最多覆盖 14 天，并包含该用户关联会话中的遥测及会话标记；未指定用户时，时间范围最多覆盖 90 天。为支持 Logs 用户筛选和用户范围删除，带 userId 的 accepted event log 会在 Loki structured metadata 中保存原始 userId（最多 128 字符）；只有这类事件日志带有该关联，其他日志不会按 userId 匹配。用户范围删除会提交对应 Loki 请求并等待全部请求达到 processed。任务完成表示匹配日志已从查询结果中排除；底层 chunk/object 由 Loki 延迟异步清理，不保证在任务完成时已物理擦除。"
      type="warning" :closable="false" show-icon class="notice"
    />

    <el-card shadow="never" class="scrubbing-card">
      <template #header><strong>遥测数据脱敏</strong></template>
      <p class="scrubbing-description">服务器始终过滤常见凭据。以下项目规则会在新事件入库前应用于事件内容、设备信息和页面 URL，已有数据不回溯修改。Issue AI 外发数据和返回建议会始终强制遮蔽邮箱、银行卡、IP、手机号和身份证号，并额外应用项目自定义敏感字段规则。</p>
      <el-form label-position="top" class="scrubbing-form">
        <el-form-item label="电子邮箱地址">
          <el-switch v-model="scrubForm.scrubEmails" :disabled="!scrubbingSettings?.canModify || settingsSaving" />
        </el-form-item>
        <el-form-item label="通过校验的银行卡号">
          <el-switch v-model="scrubForm.scrubCreditCards" :disabled="!scrubbingSettings?.canModify || settingsSaving" />
        </el-form-item>
        <el-form-item label="IPv4 / IPv6 地址">
          <el-switch v-model="scrubForm.scrubIpAddresses" :disabled="!scrubbingSettings?.canModify || settingsSaving" />
        </el-form-item>
        <el-form-item label="中国大陆手机号">
          <el-switch v-model="scrubForm.scrubPhoneNumbers" :disabled="!scrubbingSettings?.canModify || settingsSaving" />
        </el-form-item>
        <el-form-item label="中国居民身份证号（18 位校验有效）">
          <el-switch v-model="scrubForm.scrubChineseIdNumbers" :disabled="!scrubbingSettings?.canModify || settingsSaving" />
        </el-form-item>
        <el-form-item label="自定义敏感字段名">
          <el-input v-model="scrubForm.customSensitiveFieldText" type="textarea" :rows="3" maxlength="8192" show-word-limit
            :disabled="!scrubbingSettings?.canModify || settingsSaving" placeholder="每行一个，例如：phoneNumber、national_id、bank-account" />
          <div class="field-hint">最多 64 个名称，每个最多 64 个字符；支持字母、数字、下划线和连字符。匹配时忽略大小写、下划线和连字符。</div>
        </el-form-item>
      </el-form>
      <div class="scrubbing-footer">
        <span v-if="!scrubbingSettings?.canModify">只有项目 Owner 可以修改。</span>
        <el-button v-if="scrubbingSettings?.canModify" type="primary" :loading="settingsSaving" @click="saveScrubbingSettings">保存脱敏规则</el-button>
      </div>
    </el-card>

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
        <el-descriptions-item label="Loki logs">{{ previewCounts.lokiLogs ?? '将按项目、用户和时间范围提交异步删除' }}</el-descriptions-item>
      </el-descriptions>
      <p class="semantics">事件预览为生成时的精确计数；执行使用新快照，不包含快照之后到达的数据。任务完成表示 Loki compactor 已将匹配日志从查询结果中排除；底层 chunk/object 的物理清理由 Loki 延迟异步执行，不保证在任务完成时已经擦除。</p>
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
.page-header{margin-bottom:18px}.page-header h2{margin:0 0 6px}.page-header p{margin:0;color:#64748b}.notice{margin-bottom:16px}.scrubbing-card,.range-card,.preview-card{margin-bottom:16px}.scrubbing-description{margin:0 0 16px;color:#64748b}.scrubbing-form{display:flex;gap:42px;flex-wrap:wrap}.scrubbing-form :deep(.el-form-item){margin-bottom:12px}.scrubbing-form :deep(.el-textarea){width:min(100%,480px)}.field-hint{margin-top:6px;color:#64748b;font-size:12px}.scrubbing-footer{display:flex;align-items:center;justify-content:space-between;color:#64748b;font-size:13px}.form-row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:16px}.form-row :deep(.el-date-editor){width:100%}.preview-header{display:flex;align-items:center;justify-content:space-between}.scope{margin-bottom:16px;color:#475569}.counts{margin-bottom:12px}.semantics{color:#64748b;font-size:13px}
</style>
