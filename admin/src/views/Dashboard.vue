<script setup lang="ts">
import * as echarts from 'echarts';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type DashboardData } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const data = ref<DashboardData | null>(null);
const loading = ref(false);
const chartEl = ref<HTMLDivElement>();
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
    data.value = await monitorApi.dashboard(projects.currentKey);
    await nextTick();
    renderChart();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
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
    series: [{ type: 'line', smooth: true, data: trend.map(i => Number(i.count)) }]
  });
}

watch(() => projects.currentKey, load, { immediate: true });
onBeforeUnmount(() => chart?.dispose());
</script>

<template>
  <section v-loading="loading">
    <h1 class="page-title">Dashboard</h1>
    <div class="metric-grid">
      <div v-for="[label,value] in metrics" :key="label" class="metric-card">
        <div class="metric-label">{{ label }}</div>
        <div class="metric-value">{{ value }}</div>
      </div>
    </div>
    <div class="panel">
      <h3>24 小时错误趋势</h3>
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
