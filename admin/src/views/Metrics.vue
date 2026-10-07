<script setup lang="ts">
import * as echarts from 'echarts';
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type MetricData, type MonitorRelease } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const data = ref<MetricData | null>(null);
const releases = ref<MonitorRelease[]>([]);
const chartEl = ref<HTMLDivElement>();
const hours = ref(24);
const environment = ref('');
const release = ref('');
const name = ref('');
const loading = ref(false);
let chart: echarts.ECharts | undefined;

async function loadReleases() {
  if (!projects.currentKey) {
    releases.value = [];
    return;
  }
  try { releases.value = await monitorApi.releases(projects.currentKey); }
  catch { releases.value = []; }
}

async function load() {
  if (!projects.currentKey) return;
  loading.value = true;
  try {
    data.value = await monitorApi.metrics(
      projects.currentKey, hours.value, environment.value, release.value, name.value.trim()
    );
    await nextTick();
    render();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Metrics 查询失败');
  } finally {
    loading.value = false;
  }
}

function render() {
  if (!chartEl.value || !data.value) return;
  chart ??= echarts.init(chartEl.value);
  const buckets = [...new Set(data.value.trend.map(item => String(item.bucket)))];
  const metrics = [...new Set(data.value.trend.map(item => `${item.name} (${item.metricType})`))];
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: metrics },
    xAxis: { type: 'category', data: buckets },
    yAxis: { type: 'value' },
    series: metrics.map(metric => ({
      name: metric,
      type: 'line',
      smooth: true,
      data: buckets.map(bucket => data.value?.trend.find(item =>
        String(item.bucket) === bucket && `${item.name} (${item.metricType})` === metric)?.value ?? null)
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
      <h1 class="page-title" style="margin:0">Application Metrics</h1>
      <div class="filters">
        <el-select v-model="hours" style="width:140px">
          <el-option label="最近 1 小时" :value="1" />
          <el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" />
          <el-option label="最近 30 天" :value="720" />
        </el-select>
        <el-select v-model="environment" clearable placeholder="全部环境" style="width:150px">
          <el-option label="production" value="production" />
          <el-option label="staging" value="staging" />
          <el-option label="development" value="development" />
        </el-select>
        <el-select v-model="release" clearable filterable placeholder="全部 Release" style="width:190px">
          <el-option v-for="item in releases" :key="item.id" :label="item.version" :value="item.version" />
        </el-select>
        <el-input v-model="name" clearable placeholder="Metric 名称" style="width:200px" @keyup.enter="load" />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="data?.summary || []">
        <el-table-column prop="name" label="Metric" min-width="200" />
        <el-table-column prop="metricType" label="类型" width="130" />
        <el-table-column prop="unit" label="单位" width="100" />
        <el-table-column prop="samples" label="样本" width="100" />
        <el-table-column prop="sum" label="Sum" width="130" />
        <el-table-column prop="avg" label="Avg" width="130" />
        <el-table-column prop="p50" label="P50" width="130" />
        <el-table-column prop="p95" label="P95" width="130" />
        <el-table-column prop="min" label="Min" width="120" />
        <el-table-column prop="max" label="Max" width="120" />
      </el-table>
      <el-empty v-if="!loading && !data?.summary.length" description="暂无自定义 Metrics；可通过 Browser SDK recordMetric 上报" />
    </div>

    <div class="panel"><div ref="chartEl" class="chart" /></div>

    <div class="panel">
      <h3>最近样本</h3>
      <el-table v-loading="loading" :data="data?.recent || []">
        <el-table-column prop="event_time" label="时间" width="210" />
        <el-table-column prop="name" label="Metric" min-width="200" />
        <el-table-column prop="metricType" label="类型" width="130" />
        <el-table-column prop="value" label="Value" width="130" />
        <el-table-column prop="unit" label="单位" width="100" />
        <el-table-column prop="tags" label="Dimensions" min-width="240" show-overflow-tooltip />
        <el-table-column label="Exemplar Trace" min-width="260" show-overflow-tooltip>
          <template #default="scope">
            <el-link v-if="scope.row.trace_id" type="primary" :href="`/logs?traceId=${encodeURIComponent(scope.row.trace_id)}`" @click.stop>{{ scope.row.trace_id }}</el-link>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>

<style scoped>
.filters{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.chart{height:340px}
</style>
