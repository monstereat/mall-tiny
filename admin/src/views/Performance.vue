<script setup lang="ts">
import * as echarts from 'echarts';
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type MonitorRelease, type PerformanceData } from '../api/monitor';
import { useAuthStore } from '../stores/auth';
import { useProjectStore } from '../stores/project';

const auth = useAuthStore();
const projects = useProjectStore();
const data = ref<PerformanceData | null>(null);
const releases = ref<MonitorRelease[]>([]);
const loading = ref(false);
const chartEl = ref<HTMLDivElement>();
const hours = ref(24);
const environment = ref('');
const release = ref('');
let chart: echarts.ECharts | undefined;
let performanceRequestId = 0;
let releaseRequestId = 0;
let contextGeneration = 0;

function isCurrentContext(projectKey: string, token: string) {
  return projects.currentKey === projectKey
    && auth.token === token
    && localStorage.getItem('monitor-token') === token;
}

async function load() {
  const requestId = ++performanceRequestId;
  const projectKey = projects.currentKey;
  const token = auth.token;
  if (!projectKey || !token || localStorage.getItem('monitor-token') !== token) {
    data.value = null;
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await monitorApi.performance(
      projectKey,
      hours.value,
      environment.value,
      release.value
    );
    if (requestId !== performanceRequestId || !isCurrentContext(projectKey, token)) return;
    data.value = result;
    await nextTick();
    if (requestId === performanceRequestId && isCurrentContext(projectKey, token)) render();
  } catch (e) {
    if (requestId === performanceRequestId && isCurrentContext(projectKey, token)) {
      data.value = null;
      chart?.clear();
      ElMessage.error(e instanceof Error ? e.message : '加载失败');
    }
  } finally {
    if (requestId === performanceRequestId && isCurrentContext(projectKey, token)) loading.value = false;
  }
}

async function loadReleases() {
  const requestId = ++releaseRequestId;
  const projectKey = projects.currentKey;
  const token = auth.token;
  if (!projectKey || !token || localStorage.getItem('monitor-token') !== token) {
    releases.value = [];
    return;
  }
  try {
    const result = await monitorApi.releases(projectKey);
    if (requestId === releaseRequestId && isCurrentContext(projectKey, token)) releases.value = result;
  } catch {
    if (requestId === releaseRequestId && isCurrentContext(projectKey, token)) releases.value = [];
  }
}

function resetForContextChange() {
  const generation = ++contextGeneration;
  performanceRequestId++;
  releaseRequestId++;
  const projectKey = projects.currentKey;
  const token = auth.token;
  data.value = null;
  releases.value = [];
  loading.value = false;
  chart?.clear();
  environment.value = '';
  release.value = '';
  if (!projectKey || !token) return;
  queueMicrotask(() => {
    if (generation !== contextGeneration || !isCurrentContext(projectKey, token)) return;
    void loadReleases();
    void load();
  });
}

function isCls(metric: string) {
  return metric.toUpperCase() === 'CLS';
}

function performanceValue(metric: string, value: number) {
  if (!Number.isFinite(value)) return '暂无数据';
  return isCls(metric) ? value.toFixed(3) : `${value.toFixed(1)} ms`;
}

function milliseconds(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) ? '暂无数据' : `${value.toFixed(1)} ms`;
}

function bytes(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '暂无数据';
  if (value < 1024) return `${Math.round(value)} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function cacheStatusCounts(resource: NonNullable<PerformanceData['resources']>[number]) {
  return `命中 ${resource.cacheHitCount} · 网络传输 ${resource.cacheMissCount} · 旁路 ${resource.cacheBypassCount}`;
}

function cacheUnknownRatio(resource: NonNullable<PerformanceData['resources']>[number]) {
  if (!resource.count) return '暂无数据';
  return `${(resource.cacheUnknownCount / resource.count * 100).toFixed(1)}%`;
}

function resourceKey(resource: NonNullable<PerformanceData['resources']>[number]) {
  return `${resource.url}:${resource.type}`;
}

function render() {
  if (!chartEl.value || !data.value) return;
  chart ??= echarts.init(chartEl.value);
  const buckets = [...new Set(data.value.trend.map(i => String(i.bucket)))];
  const metrics = [...new Set(data.value.trend.map(i => i.metric))];
  const hasCls = metrics.some(isCls);
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: metrics },
    xAxis: { type: 'category', data: buckets },
    yAxis: hasCls
      ? [{ type: 'value', name: '时间 (ms)' }, { type: 'value', name: 'CLS', position: 'right' }]
      : [{ type: 'value', name: '时间 (ms)' }],
    series: metrics.map(metric => ({
      name: metric,
      type: 'line',
      smooth: true,
      yAxisIndex: hasCls && isCls(metric) ? 1 : 0,
      data: buckets.map(bucket => data.value?.trend.find(i => String(i.bucket) === bucket && i.metric === metric)?.value ?? null)
    }))
  }, true);
}

watch([() => projects.currentKey, () => auth.token], resetForContextChange, { immediate: true, flush: 'sync' });
watch([hours, environment, release], () => void load());
onBeforeUnmount(() => {
  contextGeneration++;
  performanceRequestId++;
  releaseRequestId++;
  chart?.dispose();
});
</script>

<template>
  <section>
    <div class="toolbar">
      <h1 class="page-title" style="margin:0">Performance</h1>
      <div style="display:flex;gap:10px">
        <el-select v-model="hours" style="width:130px">
          <el-option label="最近 1 小时" :value="1" />
          <el-option label="最近 6 小时" :value="6" />
          <el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" />
          <el-option label="最近 30 天" :value="720" />
        </el-select>
        <el-select v-model="environment" clearable placeholder="全部环境" style="width:140px">
          <el-option label="production" value="production" />
          <el-option label="staging" value="staging" />
          <el-option label="development" value="development" />
        </el-select>
        <el-select v-model="release" clearable filterable placeholder="全部 Release" style="width:190px">
          <el-option v-for="item in releases" :key="item.id" :label="item.version" :value="item.version" />
        </el-select>
      </div>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="data?.summary || []">
        <el-table-column prop="metric" label="Metric" />
        <el-table-column label="单位" width="90">
          <template #default="scope">{{ isCls(scope.row.metric) ? '分数' : 'ms' }}</template>
        </el-table-column>
        <el-table-column label="AVG" width="140">
          <template #default="scope">{{ performanceValue(scope.row.metric, scope.row.avgValue) }}</template>
        </el-table-column>
        <el-table-column label="P75" width="140">
          <template #default="scope">{{ performanceValue(scope.row.metric, scope.row.p75) }}</template>
        </el-table-column>
        <el-table-column label="P95" width="140">
          <template #default="scope">{{ performanceValue(scope.row.metric, scope.row.p95) }}</template>
        </el-table-column>
        <el-table-column prop="samples" label="Samples" />
      </el-table>
    </div>

    <div class="panel"><div ref="chartEl" style="height:340px" /></div>

    <div class="panel">
      <h3>资源诊断</h3>
      <p class="resource-note">阶段耗时均以毫秒展示，传输体积单独显示；缓存未知比例以该资源请求数为分母。暂无阶段数据会显示为“暂无数据”；网络传输可能包含协商缓存，不等于纯缓存未命中。</p>
      <el-table v-loading="loading" :data="data?.resources || []" :row-key="resourceKey" :empty-text="data?.resources ? '当前范围暂无资源数据' : '资源阶段数据尚未提供'">
        <el-table-column prop="url" label="资源 URL" min-width="260" show-overflow-tooltip />
        <el-table-column prop="type" label="类型" width="110" />
        <el-table-column prop="count" label="请求数" width="90" />
        <el-table-column label="总耗时 P75" width="125">
          <template #default="scope">{{ milliseconds(scope.row.p75DurationMs) }}</template>
        </el-table-column>
        <el-table-column label="传输体积" width="120">
          <template #default="scope">{{ bytes(scope.row.totalBytes) }}</template>
        </el-table-column>
        <el-table-column label="DNS P75" width="110">
          <template #default="scope">{{ milliseconds(scope.row.p75DnsMs) }}</template>
        </el-table-column>
        <el-table-column label="连接 P75" width="110">
          <template #default="scope">{{ milliseconds(scope.row.p75ConnectMs) }}</template>
        </el-table-column>
        <el-table-column label="TLS P75" width="110">
          <template #default="scope">{{ milliseconds(scope.row.p75TlsMs) }}</template>
        </el-table-column>
        <el-table-column label="请求阶段 P75" width="130">
          <template #default="scope">{{ milliseconds(scope.row.p75RequestMs) }}</template>
        </el-table-column>
        <el-table-column label="响应阶段 P75" width="130">
          <template #default="scope">{{ milliseconds(scope.row.p75ResponseMs) }}</template>
        </el-table-column>
        <el-table-column label="缓存状态" width="240">
          <template #default="scope">{{ cacheStatusCounts(scope.row) }}</template>
        </el-table-column>
        <el-table-column label="缓存未知比例" width="125">
          <template #default="scope">{{ cacheUnknownRatio(scope.row) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <h3>最近性能事件</h3>
      <el-table v-loading="loading" :data="data?.recent || []">
        <el-table-column prop="event_time" label="时间" width="190" />
        <el-table-column prop="environment" label="环境" width="120" />
        <el-table-column prop="metric" label="Metric" width="140" />
        <el-table-column label="Value" width="140">
          <template #default="scope">{{ performanceValue(scope.row.metric, scope.row.value) }}</template>
        </el-table-column>
        <el-table-column prop="release" label="Release" width="140" />
        <el-table-column prop="page_url" label="页面" min-width="240" show-overflow-tooltip />
      </el-table>
    </div>
  </section>
</template>

<style scoped>
.resource-note{color:#64748b;font-size:13px;margin:8px 0 14px}
</style>
