<script setup lang="ts">
import * as echarts from 'echarts';
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type ApiData, type MonitorRelease } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const data = ref<ApiData | null>(null);
const releases = ref<MonitorRelease[]>([]);
const chartEl = ref<HTMLDivElement>();
const hours = ref(24);
const environment = ref('');
const release = ref('');
let chart: echarts.ECharts | undefined;

async function load() {
  if (!projects.currentKey) return;
  try {
    data.value = await monitorApi.apis(
      projects.currentKey,
      hours.value,
      environment.value,
      release.value
    );
    await nextTick();
    render();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
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

watch(() => projects.currentKey, async () => {
  await loadReleases();
  await load();
}, { immediate: true });
watch([hours, environment, release], () => void load());
onBeforeUnmount(() => chart?.dispose());
</script>

<template>
  <section>
    <div class="toolbar">
      <h1 class="page-title" style="margin:0">API Performance</h1>
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
