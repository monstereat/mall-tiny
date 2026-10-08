<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { monitorApi, type MonitorBusinessAnalytics } from '../api/monitor';
import { useAuthStore } from '../stores/auth';
import { useProjectStore } from '../stores/project';

const auth = useAuthStore();
const projects = useProjectStore();
const hours = ref(24);
const environment = ref('');
const release = ref('');
const page = ref('');
const event = ref('');
const data = ref<MonitorBusinessAnalytics | null>(null);
const loading = ref(false);
const error = ref('');
let requestGeneration = 0;
let contextGeneration = 0;

function number(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '暂无数据';
  return new Intl.NumberFormat('zh-CN').format(value);
}

function seconds(milliseconds: number | null | undefined) {
  if (milliseconds == null || !Number.isFinite(milliseconds)) return '暂无数据';
  return `${(milliseconds / 1000).toFixed(2)} 秒`;
}

function isCurrentRequest(generation: number, projectKey: string, token: string) {
  return generation === requestGeneration
    && projects.currentKey === projectKey
    && auth.token === token
    && localStorage.getItem('monitor-token') === token;
}

async function search() {
  const generation = ++requestGeneration;
  const projectKey = projects.currentKey;
  const token = auth.token;
  if (!projectKey || !token || localStorage.getItem('monitor-token') !== token) {
    data.value = null;
    loading.value = false;
    error.value = '';
    return;
  }

  loading.value = true;
  error.value = '';
  try {
    const result = await monitorApi.business(projectKey, {
      hours: hours.value,
      environment: environment.value.trim() || undefined,
      release: release.value.trim() || undefined,
      page: page.value.trim() || undefined,
      event: event.value.trim() || undefined
    });
    if (!isCurrentRequest(generation, projectKey, token)) return;
    data.value = result;
  } catch (cause) {
    if (!isCurrentRequest(generation, projectKey, token)) return;
    data.value = null;
    error.value = cause instanceof Error ? cause.message : '业务分析加载失败';
  } finally {
    if (isCurrentRequest(generation, projectKey, token)) loading.value = false;
  }
}

function resetForContextChange() {
  const generation = ++contextGeneration;
  requestGeneration++;
  const projectKey = projects.currentKey;
  const token = auth.token;
  data.value = null;
  loading.value = false;
  error.value = '';
  environment.value = '';
  release.value = '';
  page.value = '';
  event.value = '';
  if (projectKey && token) {
    queueMicrotask(() => {
      if (generation === contextGeneration && projects.currentKey === projectKey
        && auth.token === token && localStorage.getItem('monitor-token') === token) void search();
    });
  }
}

watch([() => projects.currentKey, () => auth.token], resetForContextChange, {
  immediate: true,
  flush: 'sync'
});
watch([hours, environment, release, page, event], () => {
  requestGeneration++;
  data.value = null;
  loading.value = false;
  error.value = '';
}, { flush: 'sync' });

onBeforeUnmount(() => {
  requestGeneration++;
  contextGeneration++;
});
</script>

<template>
  <section>
    <div class="business-header">
      <h1 class="page-title">业务分析</h1>
      <el-button type="primary" :loading="loading" @click="search">查询</el-button>
    </div>

    <div class="panel">
      <el-form inline @submit.prevent="search">
        <el-form-item label="时间范围">
          <el-select v-model="hours" style="width:140px">
            <el-option label="最近 1 小时" :value="1" />
            <el-option label="最近 24 小时" :value="24" />
            <el-option label="最近 3 天" :value="72" />
            <el-option label="最近 7 天" :value="168" />
          </el-select>
        </el-form-item>
        <el-form-item label="Environment">
          <el-input v-model="environment" clearable placeholder="全部环境" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="Release">
          <el-input v-model="release" clearable placeholder="全部版本" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="页面">
          <el-input v-model="page" clearable placeholder="页面 URL" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="业务事件">
          <el-input v-model="event" clearable placeholder="事件名称" @keyup.enter="search" />
        </el-form-item>
      </el-form>
      <el-alert
        title="PV 和已知用户 UV 只按精确采样的 page_view 计算；已知用户 UV 不含匿名用户。会话按筛选后的 page_view 与业务事件去重。采样率低于 1 的 page_view 和业务事件不计入精确统计；页面停留时长累计页面可见期间上报的增量。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="business-error" />
    </div>

    <div v-loading="loading">
      <div v-if="data" class="business-summary">
        <div class="metric-card">
          <div class="metric-label">PV</div>
          <div class="metric-value">{{ number(data.summary.pv) }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">已知用户 UV</div>
          <div class="metric-value">{{ number(data.summary.knownUserUv) }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">会话</div>
          <div class="metric-value">{{ number(data.summary.sessions) }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">业务事件</div>
          <div class="metric-value">{{ number(data.summary.businessEventCount) }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">页面可见停留时长</div>
          <div class="metric-value metric-value-small">{{ seconds(data.summary.visibleDwellMs) }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">平均可见停留时长</div>
          <div class="metric-value metric-value-small">{{ seconds(data.summary.avgVisibleDwellMs) }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">未纳入精确统计的抽样事件</div>
          <div class="metric-value">{{ number(data.summary.excludedSampled) }}</div>
        </div>
      </div>

      <div v-if="data?.rankingNote" class="panel ranking-note">{{ data.rankingNote }}</div>

      <div class="panel">
        <div class="section-heading">
          <h2>页面排行</h2>
          <el-tag v-if="data?.pagesTruncated" type="warning">仅展示前 100 项</el-tag>
        </div>
        <el-table :data="data?.pages || []" row-key="pageUrl" empty-text="暂无页面数据">
          <el-table-column prop="pageUrl" label="页面 URL" min-width="260" show-overflow-tooltip />
          <el-table-column prop="pageViews" label="PV" width="100" />
          <el-table-column prop="uniqueUsers" label="已知用户 UV" width="130" />
          <el-table-column prop="sessions" label="会话" width="100" />
          <el-table-column label="可见停留时长" width="150">
            <template #default="scope">{{ seconds(scope.row.visibleDwellMs) }}</template>
          </el-table-column>
          <el-table-column label="平均可见停留" width="150">
            <template #default="scope">{{ seconds(scope.row.avgVisibleDwellMs) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="section-heading">
          <h2>业务事件排行</h2>
          <el-tag v-if="data?.eventsTruncated" type="warning">仅展示前 100 项</el-tag>
        </div>
        <el-table :data="data?.events || []" row-key="event" empty-text="暂无业务事件">
          <el-table-column prop="event" label="事件名称" min-width="220" show-overflow-tooltip />
          <el-table-column prop="eventCount" label="事件数" width="120" />
          <el-table-column prop="uniqueUsers" label="已知用户 UV" width="130" />
          <el-table-column prop="sessions" label="会话" width="120" />
        </el-table>
      </div>

      <div class="panel">
        <h2>小时趋势</h2>
        <el-table :data="data?.hourly || []" row-key="bucket" empty-text="暂无趋势数据">
          <el-table-column prop="bucket" label="小时" min-width="220" />
          <el-table-column prop="pageViews" label="PV" width="120" />
          <el-table-column prop="eventCount" label="业务事件数" width="140" />
        </el-table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.business-header{display:flex;align-items:center;justify-content:space-between}.business-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(175px,1fr));gap:14px;margin-bottom:18px}.metric-value-small{font-size:22px}.business-error{margin-top:12px}.section-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}.section-heading h2,.panel h2{font-size:16px;margin:0}.ranking-note{color:#64748b;font-size:13px}
</style>
