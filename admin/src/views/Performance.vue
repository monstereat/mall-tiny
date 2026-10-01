<script setup lang="ts">
import * as echarts from 'echarts';
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type PerformanceData } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const data = ref<PerformanceData | null>(null);
const chartEl = ref<HTMLDivElement>();
let chart: echarts.ECharts | undefined;

async function load() {
  if (!projects.currentKey) return;
  try {
    data.value = await monitorApi.performance(projects.currentKey);
    await nextTick();
    render();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  }
}

function render() {
  if (!chartEl.value || !data.value) return;
  chart ??= echarts.init(chartEl.value);
  const buckets = [...new Set(data.value.trend.map(i => String(i.bucket)))];
  const metrics = [...new Set(data.value.trend.map(i => i.metric))];
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: metrics },
    xAxis: { type: 'category', data: buckets },
    yAxis: { type: 'value' },
    series: metrics.map(metric => ({
      name: metric,
      type: 'line',
      smooth: true,
      data: buckets.map(bucket => data.value?.trend.find(i => String(i.bucket) === bucket && i.metric === metric)?.value ?? null)
    }))
  });
}
watch(() => projects.currentKey, load, { immediate: true });
onBeforeUnmount(() => chart?.dispose());
</script>

<template>
  <section>
    <h1 class="page-title">Performance</h1>
    <div class="panel">
      <el-table :data="data?.summary || []">
        <el-table-column prop="metric" label="Metric" />
        <el-table-column prop="avgValue" label="AVG" />
        <el-table-column prop="p75" label="P75" />
        <el-table-column prop="p95" label="P95" />
        <el-table-column prop="samples" label="Samples" />
      </el-table>
    </div>
    <div class="panel"><div ref="chartEl" style="height:340px" /></div>
    <div class="panel">
      <h3>最近性能事件</h3>
      <el-table :data="data?.recent || []">
        <el-table-column prop="event_time" label="时间" width="190" />
        <el-table-column prop="metric" label="Metric" width="100" />
        <el-table-column prop="value" label="Value" width="120" />
        <el-table-column prop="release" label="Release" width="140" />
        <el-table-column prop="page_url" label="页面" min-width="240" show-overflow-tooltip />
      </el-table>
    </div>
  </section>
</template>
