<script setup lang="ts">
import * as echarts from 'echarts';
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type MonitorRelease, type PerformanceData } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const data = ref<PerformanceData | null>(null);
const releases = ref<MonitorRelease[]>([]);
const chartEl = ref<HTMLDivElement>();
const hours = ref(24);
const environment = ref('');
const release = ref('');
let chart: echarts.ECharts | undefined;

async function load() {
  if (!projects.currentKey) return;
  try {
    data.value = await monitorApi.performance(
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
        <el-table-column prop="environment" label="环境" width="120" />
        <el-table-column prop="metric" label="Metric" width="140" />
        <el-table-column prop="value" label="Value" width="120" />
        <el-table-column prop="release" label="Release" width="140" />
        <el-table-column prop="page_url" label="页面" min-width="240" show-overflow-tooltip />
      </el-table>
    </div>
  </section>
</template>
