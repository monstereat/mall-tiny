<script setup lang="ts">
import * as echarts from 'echarts';
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type ApiData } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const data = ref<ApiData | null>(null);
const chartEl = ref<HTMLDivElement>();
let chart: echarts.ECharts | undefined;

async function load() {
  if (!projects.currentKey) return;
  try {
    data.value = await monitorApi.apis(projects.currentKey);
    await nextTick();
    render();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  }
}
function render() {
  if (!chartEl.value || !data.value) return;
  chart ??= echarts.init(chartEl.value);
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['请求量','失败量','平均 RT'] },
    xAxis: { type: 'category', data: data.value.trend.map(i => String(i.bucket)) },
    yAxis: [{ type: 'value' }, { type: 'value' }],
    series: [
      { name:'请求量', type:'line', data:data.value.trend.map(i=>i.requests) },
      { name:'失败量', type:'line', data:data.value.trend.map(i=>i.failures) },
      { name:'平均 RT', type:'line', yAxisIndex:1, data:data.value.trend.map(i=>i.avgRt) }
    ]
  });
}
watch(() => projects.currentKey, load, { immediate: true });
onBeforeUnmount(() => chart?.dispose());
</script>

<template>
  <section>
    <h1 class="page-title">API Performance</h1>
    <div class="panel"><div ref="chartEl" style="height:320px" /></div>
    <div class="panel">
      <el-table :data="data?.summary || []">
        <el-table-column prop="url" label="URL" min-width="300" show-overflow-tooltip />
        <el-table-column prop="requests" label="请求量" width="100" />
        <el-table-column prop="failures" label="失败量" width="100" />
        <el-table-column prop="avgRt" label="AVG RT" width="120" />
        <el-table-column prop="p95" label="P95" width="120" />
      </el-table>
    </div>
  </section>
</template>
