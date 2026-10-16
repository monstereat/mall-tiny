<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  monitorApi,
  type MonitorDeadLetterRecordView,
  type MonitorDeadLetterSummaryView
} from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const sourceTopics = [
  { label: '错误事件 · monitor-error-v1', value: 'monitor-error-v1' },
  { label: '性能事件 · monitor-performance-v1', value: 'monitor-performance-v1' },
  { label: '用户行为 · monitor-behavior-v1', value: 'monitor-behavior-v1' },
  { label: '会话回放 · monitor-replay-v1', value: 'monitor-replay-v1' },
  { label: '指标事件 · monitor-metric-v1', value: 'monitor-metric-v1' },
  { label: '性能采样 · monitor-profile-v1', value: 'monitor-profile-v1' },
  { label: '链路 Span · monitor-span-v1', value: 'monitor-span-v1' }
];
const location = reactive({ sourceTopic: 'monitor-error-v1', partition: 0, offset: '' });
const deadLetters = ref<MonitorDeadLetterSummaryView[]>([]);
const record = ref<MonitorDeadLetterRecordView>();
const listLoading = ref(false);
const loading = ref(false);
const replaying = ref(false);
let inspectSequence = 0;
let listSequence = 0;

const payloadText = computed(() => {
  if (!record.value) return '';
  try { return JSON.stringify(JSON.parse(record.value.payload), null, 2); }
  catch { return record.value.payload; }
});

const locationMatchesRecord = computed(() => Boolean(record.value
  && record.value.sourceTopic === location.sourceTopic
  && record.value.deadLetterPartition === location.partition
  && String(record.value.deadLetterOffset) === location.offset.trim()));

function eventTypeLabel(type: string) {
  return ({
    ERROR: '错误事件', PERFORMANCE: '性能事件', BEHAVIOR: '用户行为', REPLAY: '会话回放',
    METRIC: '指标事件', PROFILE: '性能采样', SPAN: '链路 Span'
  } as Record<string, string>)[type] || type;
}

function formatTimestamp(timestamp: number) {
  return timestamp > 0 ? new Date(timestamp).toLocaleString() : '未知';
}

async function loadDeadLetters() {
  const requestSequence = ++listSequence;
  const projectKey = projects.currentKey;
  const sourceTopic = location.sourceTopic;
  if (!projectKey) {
    deadLetters.value = [];
    listLoading.value = false;
    return;
  }

  listLoading.value = true;
  try {
    const result = await monitorApi.listDeadLetters(projectKey, sourceTopic, 100);
    // 项目或主题切换后，丢弃旧请求的结果，防止列表串到另一个项目。
    if (requestSequence === listSequence
      && projectKey === projects.currentKey
      && sourceTopic === location.sourceTopic) deadLetters.value = result;
  } catch (error) {
    if (requestSequence === listSequence && projectKey === projects.currentKey) {
      deadLetters.value = [];
      ElMessage.error(error instanceof Error ? error.message : '死信列表加载失败');
    }
  } finally {
    if (requestSequence === listSequence) listLoading.value = false;
  }
}

function inspectSummary(summary: MonitorDeadLetterSummaryView) {
  location.partition = summary.deadLetterPartition;
  location.offset = String(summary.deadLetterOffset);
  void inspect();
}

async function inspect() {
  if (!projects.currentKey) {
    record.value = undefined;
    ElMessage.warning('请先选择项目');
    return;
  }
  const offset = Number(location.offset.trim());
  if (!Number.isSafeInteger(location.partition) || location.partition < 0
    || !Number.isSafeInteger(offset) || offset < 0) {
    ElMessage.warning('分区和偏移量必须是非负整数');
    return;
  }

  const requestSequence = ++inspectSequence;
  const projectKey = projects.currentKey;
  loading.value = true;
  record.value = undefined;
  try {
    const result = await monitorApi.inspectDeadLetter(projectKey, location.sourceTopic, location.partition, offset);
    // 项目切换或发起新查询后，忽略旧请求的迟到响应，避免把其他项目的内容留在页面上。
    if (requestSequence === inspectSequence && projectKey === projects.currentKey) record.value = result;
  } catch (error) {
    if (requestSequence === inspectSequence && projectKey === projects.currentKey) {
      ElMessage.error(error instanceof Error ? error.message : '死信查询失败');
    }
  } finally {
    if (requestSequence === inspectSequence) loading.value = false;
  }
}

async function replay() {
  if (!projects.currentKey || !record.value || !locationMatchesRecord.value || record.value.replayed) return;
  const projectKey = projects.currentKey;
  const inspectedRecord = record.value;
  try {
    await ElMessageBox.confirm(
      `将把事件 ${record.value.eventId} 重新发送到正常主题 ${record.value.sourceTopic}，由消费者重新处理。原死信记录会保留。请先确认失败原因已经修复。`,
      '重放死信',
      { type: 'warning', confirmButtonText: '确认重放', cancelButtonText: '取消', distinguishCancelAndClose: true }
    );
    if (projects.currentKey !== projectKey || record.value !== inspectedRecord) return;
    replaying.value = true;
    const result = await monitorApi.replayDeadLetter(projectKey, {
      sourceTopic: location.sourceTopic,
      partition: location.partition,
      offset: Number(location.offset.trim())
    });
    if (projects.currentKey === projectKey && record.value === inspectedRecord) {
      record.value = result;
      ElMessage.success('消息已重新发送到正常主题');
      void loadDeadLetters();
    }
  } catch (error) {
    if (error instanceof Error && error.message && error.message !== 'cancel' && error.message !== 'close') {
      ElMessage.error(error.message);
    }
  } finally {
    replaying.value = false;
  }
}

watch([() => projects.currentKey, () => location.sourceTopic], () => {
  listSequence++;
  inspectSequence++;
  loading.value = false;
  deadLetters.value = [];
  record.value = undefined;
  void loadDeadLetters();
}, { immediate: true });
</script>

<template>
  <section class="dead-letters-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">Kafka 死信</h1>
        <p>查看单条失败消息，并在修复原因后将它重新发送到原来的正常主题。</p>
      </div>
    </div>

    <el-alert
      title="页面会列出所选项目最近的死信"
      description="列表最多显示最近 100 条；点击“查看”可检查原始事件并重放。较早的记录仍可通过分区和偏移量精确查询。只有项目 Owner 可以查看和重放，重放不会删除原死信记录。"
      type="info" :closable="false" show-icon class="notice"
    />

    <el-card shadow="never" class="list-card">
      <template #header>
        <div class="list-header">
          <strong>最近死信</strong>
          <el-button :loading="listLoading" :disabled="!projects.currentKey" @click="loadDeadLetters">刷新</el-button>
        </div>
      </template>
      <el-table v-loading="listLoading" :data="deadLetters" empty-text="当前项目和主题没有可显示的死信">
        <el-table-column label="失败时间" min-width="160">
          <template #default="{ row }">{{ formatTimestamp(row.deadLetterTimestamp) }}</template>
        </el-table-column>
        <el-table-column label="事件类型" min-width="120">
          <template #default="{ row }">{{ eventTypeLabel(row.eventType) }}</template>
        </el-table-column>
        <el-table-column prop="eventId" label="事件编号" min-width="180" show-overflow-tooltip />
        <el-table-column label="失败原因" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.failureMessage || row.failureClass || 'Kafka 未提供具体错误信息' }}</template>
        </el-table-column>
        <el-table-column label="死信位置" min-width="170">
          <template #default="{ row }">分区 {{ row.deadLetterPartition }} · 偏移量 {{ row.deadLetterOffset }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="inspectSummary(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="lookup-card">
      <template #header><strong>按位置查询</strong></template>
      <p class="lookup-hint">用于查询列表范围以外的旧记录；分区和偏移量可从 Kafka 运维工具获取。</p>
      <el-form label-position="top" @submit.prevent="inspect">
        <div class="lookup-fields">
          <el-form-item label="原始主题">
            <el-select v-model="location.sourceTopic" filterable>
              <el-option v-for="topic in sourceTopics" :key="topic.value" :label="topic.label" :value="topic.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="死信分区">
            <el-input-number v-model="location.partition" :min="0" :precision="0" controls-position="right" />
          </el-form-item>
          <el-form-item label="死信偏移量">
            <el-input v-model="location.offset" inputmode="numeric" placeholder="例如 123" @keyup.enter="inspect" />
          </el-form-item>
          <el-form-item label=" ">
            <el-button type="primary" :loading="loading" :disabled="!projects.currentKey" @click="inspect">查看死信</el-button>
          </el-form-item>
        </div>
      </el-form>
    </el-card>

    <el-card v-if="record" shadow="never" class="record-card">
      <template #header>
        <div class="record-header">
          <strong>死信详情</strong>
          <el-tag v-if="record.replayed" type="success">已重放</el-tag>
        </div>
      </template>

      <el-descriptions border :column="2" class="record-meta">
        <el-descriptions-item label="死信主题">{{ record.deadLetterTopic }}</el-descriptions-item>
        <el-descriptions-item label="死信位置">分区 {{ record.deadLetterPartition }} · 偏移量 {{ record.deadLetterOffset }}</el-descriptions-item>
        <el-descriptions-item label="原始主题">{{ record.sourceTopic }}</el-descriptions-item>
        <el-descriptions-item label="原始位置">分区 {{ record.originalPartition }} · 偏移量 {{ record.originalOffset }}</el-descriptions-item>
        <el-descriptions-item label="事件编号">{{ record.eventId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="事件类型">{{ eventTypeLabel(record.eventType) }}</el-descriptions-item>
        <el-descriptions-item label="失败时间">{{ formatTimestamp(record.deadLetterTimestamp) }}</el-descriptions-item>
        <el-descriptions-item label="失败类型">{{ record.failureClass || '-' }}</el-descriptions-item>
        <el-descriptions-item label="失败原因" :span="2">{{ record.failureMessage || 'Kafka 未提供具体错误信息' }}</el-descriptions-item>
      </el-descriptions>

      <div class="payload-heading">原始事件内容</div>
      <pre class="payload">{{ payloadText }}</pre>

      <el-alert
        v-if="record.replayed"
        title="消息已重新发送"
        :description="`正常主题位置：分区 ${record.replayTargetPartition ?? '-'} · 偏移量 ${record.replayTargetOffset ?? '-'}`"
        type="success" :closable="false" show-icon class="replay-result"
      />
      <div class="record-actions">
        <el-button
          type="warning" :loading="replaying"
          :disabled="!locationMatchesRecord || record.replayed"
          @click="replay"
        >重放到正常主题</el-button>
        <span>重放前先修复失败原因；同一条死信短期内不能重复重放。</span>
      </div>
    </el-card>
  </section>
</template>

<style scoped>
.page-header{margin-bottom:18px}.page-header h1{margin:0 0 6px}.page-header p{margin:0;color:#64748b}.notice{margin-bottom:16px}.list-card,.lookup-card{margin-bottom:16px}.list-header{display:flex;align-items:center;justify-content:space-between}.lookup-hint{margin:0 0 16px;color:#64748b;font-size:13px}.lookup-fields{display:grid;grid-template-columns:minmax(240px,2fr) minmax(130px,1fr) minmax(180px,1fr) auto;gap:16px;align-items:end}.lookup-fields :deep(.el-select),.lookup-fields :deep(.el-input-number){width:100%}.record-header{display:flex;align-items:center;justify-content:space-between}.record-meta{margin-bottom:20px}.payload-heading{margin:16px 0 8px;font-weight:600}.payload{max-height:440px;overflow:auto;margin:0;padding:16px;border-radius:6px;background:#0f172a;color:#e2e8f0;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace}.replay-result{margin-top:16px}.record-actions{display:flex;align-items:center;gap:12px;margin-top:16px;color:#64748b;font-size:13px}@media(max-width:900px){.lookup-fields{grid-template-columns:1fr 1fr}.record-meta{--el-descriptions-table-border:1px solid var(--el-border-color-lighter)}}@media(max-width:560px){.lookup-fields{grid-template-columns:1fr}.record-actions{align-items:flex-start;flex-direction:column}}
</style>
