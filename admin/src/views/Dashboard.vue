<script setup lang="ts">
import * as echarts from 'echarts';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type DashboardData, type MonitorRelease } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const data = ref<DashboardData | null>(null);
const releases = ref<MonitorRelease[]>([]);
const loading = ref(false);
const chartEl = ref<HTMLDivElement>();
const hours = ref(24);
const environment = ref('');
const release = ref('');
let chart: echarts.ECharts | undefined;

const metrics = computed(() => [
  ['错误数', data.value?.errorCount ?? 0],
  ['影响用户', data.value?.affectedUsers ?? 0],
  ['API 事件', data.value?.apiEvents ?? 0],
  ['未解决 Issue', data.value?.unresolvedIssues ?? 0]
]);

async function load() {
  if (!projects.currentKey) return;
  loading.value = true;
  try {
    data.value = await monitorApi.dashboard(
      projects.currentKey,
      hours.value,
      environment.value,
      release.value
    );
    await nextTick();
    renderChart();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadReleases() {
  release.value = '';
  if (!projects.currentKey) {
    releases.value = [];
    return;
  }
  try {
    releases.value = await monitorApi.releases(projects.currentKey);
  } catch {
    releases.value = [];
  }
}

function renderChart() {
  if (!chartEl.value) return;
  chart ??= echarts.init(chartEl.value);
  const trend = data.value?.errorTrend ?? [];
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 42, right: 20, top: 20, bottom: 36 },
    xAxis: { type: 'category', data: trend.map(i => String(i.bucket)) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{ name: 'Errors', type: 'line', smooth: true, data: trend.map(i => Number(i.count)) }]
  });
}

watch(() => projects.currentKey, async () => {
  await loadReleases();
  await load();
}, { immediate: true });
watch([hours, environment, release], () => void load());
onBeforeUnmount(() => chart?.dispose());
</script>

<template>
  <section v-loading="loading">
    <div class="toolbar">
      <h1 class="page-title" style="margin:0">Dashboard</h1>
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

    <div class="metric-grid">
      <div v-for="[label,value] in metrics" :key="label" class="metric-card">
        <div class="metric-label">{{ label }}</div>
        <div class="metric-value">{{ value }}</div>
      </div>
    </div>

    <div class="panel">
      <h3>{{ hours }} 小时错误趋势</h3>
      <div ref="chartEl" style="height:320px" />
    </div>

    <div class="panel">
      <h3>Web Vitals 平均值</h3>
      <el-table :data="data?.webVitals || []">
        <el-table-column prop="metric" label="Metric" />
        <el-table-column prop="value" label="Value" />
      </el-table>
    </div>
  </section>
</template>
