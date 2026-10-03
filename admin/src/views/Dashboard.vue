<script setup lang="ts">
import * as echarts from 'echarts';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  monitorApi,
  type DashboardData,
  type MonitorDashboard,
  type MonitorExploreAggregationResult,
  type MonitorMetricFormulaResult,
  type MonitorRelease,
  type MonitorSavedExploreQuery
} from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const data = ref<DashboardData | null>(null);
const releases = ref<MonitorRelease[]>([]);
const dashboards = ref<MonitorDashboard[]>([]);
const savedQueries = ref<MonitorSavedExploreQuery[]>([]);
const currentDashboardId = ref<number | null>(null);
const widgetData = ref<Record<number, { buckets?: MonitorExploreAggregationResult['buckets']; points?: MonitorMetricFormulaResult['points']; error?: string }>>({});
const loading = ref(false);
const editorVisible = ref(false);
const editorDashboardId = ref<number | null>(null);
const editorName = ref('');
const editorQueryIds = ref<number[]>([]);
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
const activeDashboard = computed(() => dashboards.value.find(item => item.id === currentDashboardId.value));
const canModifyProject = computed(() => Boolean(
  projects.projects.find(item => item.projectKey === projects.currentKey)?.canWrite
));
const activeWidgets = computed(() => (activeDashboard.value?.queryIds || [])
  .map(id => savedQueries.value.find(query => query.id === id))
  .filter((query): query is MonitorSavedExploreQuery => Boolean(query)));
const missingWidgetIds = computed(() => (activeDashboard.value?.queryIds || [])
  .filter(id => !savedQueries.value.some(query => query.id === id)));
const editorMissingWidgetIds = computed(() => {
  const dashboard = dashboards.value.find(item => item.id === editorDashboardId.value);
  return (dashboard?.queryIds || []).filter(id => !savedQueries.value.some(query => query.id === id));
});

async function loadOverview() {
  if (!projects.currentKey) return;
  data.value = await monitorApi.dashboard(projects.currentKey, hours.value, environment.value, release.value);
  await nextTick();
  renderChart();
}

async function loadWidgets() {
  if (!projects.currentKey || !activeDashboard.value) return;
  const queries = activeWidgets.value;
  const nextData: typeof widgetData.value = {};
  await Promise.all(queries.map(async saved => {
    const criteria = saved.criteria;
    try {
      if (criteria.formula && criteria.formulaMetrics?.length) {
        const result = await monitorApi.exploreMetricFormula(projects.currentKey, {
          hours: hours.value,
          environment: environment.value || criteria.environment,
          release: release.value || criteria.release,
          traceId: criteria.traceId,
          query: criteria.query,
          userId: criteria.userId,
          tagKey: criteria.tagKey,
          tagValue: criteria.tagValue,
          metricNames: criteria.formulaMetrics,
          formula: criteria.formula
        });
        nextData[saved.id] = { points: result.points };
      } else {
        const result = await monitorApi.exploreAggregation(projects.currentKey, {
          hours: hours.value,
          type: criteria.type,
          environment: environment.value || criteria.environment,
          release: release.value || criteria.release,
          traceId: criteria.traceId,
          query: criteria.query,
          userId: criteria.userId,
          tagKey: criteria.tagKey,
          tagValue: criteria.tagValue,
          groupBy: criteria.groupBy,
          aggregation: criteria.aggregation,
          field: criteria.field
        });
        nextData[saved.id] = { buckets: result.buckets };
      }
    } catch (e) {
      nextData[saved.id] = { error: e instanceof Error ? e.message : '加载失败' };
    }
  }));
  widgetData.value = nextData;
}

async function load() {
  if (!projects.currentKey) return;
  loading.value = true;
  try {
    if (currentDashboardId.value === null) await loadOverview();
    else await loadWidgets();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadProjectData() {
  if (!projects.currentKey) {
    currentDashboardId.value = null;
    dashboards.value = [];
    savedQueries.value = [];
    releases.value = [];
    data.value = null;
    widgetData.value = {};
    return;
  }
  currentDashboardId.value = null;
  try {
    const [dashboardList, queryList, releaseList] = await Promise.all([
      monitorApi.dashboards(projects.currentKey),
      monitorApi.savedExploreQueries(projects.currentKey),
      monitorApi.releases(projects.currentKey)
    ]);
    dashboards.value = dashboardList;
    savedQueries.value = queryList;
    releases.value = releaseList;
  } catch (e) {
    dashboards.value = [];
    savedQueries.value = [];
    releases.value = [];
    ElMessage.error(e instanceof Error ? e.message : '加载 Dashboard 失败');
  }
  await load();
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

function openCreate() {
  editorDashboardId.value = null;
  editorName.value = '';
  editorQueryIds.value = [];
  editorVisible.value = true;
}

function openEdit() {
  if (!activeDashboard.value) return;
  editorDashboardId.value = activeDashboard.value.id;
  editorName.value = activeDashboard.value.name;
  editorQueryIds.value = [...activeDashboard.value.queryIds];
  editorVisible.value = true;
}

async function saveDashboard() {
  if (!projects.currentKey) return;
  const payload = { name: editorName.value.trim(), queryIds: editorQueryIds.value };
  if (!payload.name) {
    ElMessage.warning('请输入 Dashboard 名称');
    return;
  }
  try {
    const saved = editorDashboardId.value === null
      ? await monitorApi.createDashboard(projects.currentKey, payload)
      : await monitorApi.updateDashboard(projects.currentKey, editorDashboardId.value, payload);
    const index = dashboards.value.findIndex(item => item.id === saved.id);
    if (index < 0) dashboards.value.push(saved);
    else dashboards.value[index] = saved;
    currentDashboardId.value = saved.id;
    editorVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  }
}

async function deleteDashboard() {
  if (!projects.currentKey || !activeDashboard.value) return;
  const projectKey = projects.currentKey;
  const target = activeDashboard.value;
  try {
    await ElMessageBox.confirm(`删除 Dashboard「${target.name}」？`, '删除 Dashboard', { type: 'warning' });
    await monitorApi.deleteDashboard(projectKey, target.id);
    dashboards.value = dashboards.value.filter(item => item.id !== target.id);
    if (currentDashboardId.value === target.id) {
      currentDashboardId.value = null;
      await load();
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

watch(() => projects.currentKey, () => void loadProjectData(), { immediate: true });
watch([currentDashboardId, hours, environment, release], () => void load());
onBeforeUnmount(() => chart?.dispose());
</script>

<template>
  <section v-loading="loading">
    <div class="toolbar">
      <h1 class="page-title" style="margin:0">Dashboard</h1>
      <div style="display:flex;gap:10px;align-items:center">
        <el-select v-model="currentDashboardId" style="width:210px" placeholder="选择 Dashboard">
          <el-option label="Overview" :value="null" />
          <el-option v-for="item in dashboards" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-button v-if="canModifyProject" @click="openCreate">新建</el-button>
        <el-button v-if="activeDashboard?.canModify" @click="openEdit">编辑</el-button>
        <el-button v-if="activeDashboard?.canModify" type="danger" plain @click="deleteDashboard">删除</el-button>
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

    <template v-if="currentDashboardId === null">
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
    </template>

    <template v-else-if="activeDashboard">
      <div v-if="!activeWidgets.length && !missingWidgetIds.length" class="panel">此 Dashboard 暂无 Widget。编辑并添加 Explore 保存查询。</div>
      <el-alert v-for="id in missingWidgetIds" :key="`missing-${id}`" class="panel" type="warning" :closable="false" :title="`引用的 Explore 保存查询 ${id} 已删除`" />
      <div v-for="item in activeWidgets" :key="item.id" class="panel">
        <h3>{{ item.name }} <small style="font-weight:normal;color:#909399">
          <template v-if="item.criteria.formula">{{ item.criteria.formula }}（{{ item.criteria.formulaMetrics?.join(' / ') }}）</template>
          <template v-else>{{ item.criteria.aggregation || 'count' }}<template v-if="item.criteria.aggregation === 'count_unique'">({{ item.criteria.field || 'user' }})</template> · {{ item.criteria.type || 'all signals' }} · {{ item.criteria.groupBy || 'signal' }}</template>
        </small></h3>
        <el-alert v-if="widgetData[item.id]?.error" type="error" :closable="false" :title="widgetData[item.id].error" />
        <el-table v-else-if="widgetData[item.id]?.buckets" :data="widgetData[item.id].buckets || []" size="small">
          <el-table-column prop="value" label="分组" />
          <el-table-column prop="count" label="样本数" width="110" />
          <el-table-column v-if="item.criteria.aggregation !== 'count'" prop="aggregateValue" :label="item.criteria.aggregation || '聚合值'" width="140" />
        </el-table>
        <el-table v-else-if="widgetData[item.id]?.points" :data="widgetData[item.id].points || []" size="small">
          <el-table-column prop="bucket" label="时间" />
          <el-table-column prop="value" label="公式值" width="140" />
        </el-table>
        <el-empty v-else description="暂无数据" :image-size="48" />
      </div>
    </template>
    <div v-else class="panel">Dashboard 不存在或没有访问权限。</div>

    <el-dialog v-model="editorVisible" :title="editorDashboardId === null ? '新建 Dashboard' : '编辑 Dashboard'" width="560px">
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="editorName" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="Explore 查询 Widget（按选中顺序排列，最多 20 个）">
          <el-select v-model="editorQueryIds" multiple filterable style="width:100%" placeholder="选择已保存的 Explore 查询">
            <el-option v-for="item in savedQueries" :key="item.id" :label="item.name" :value="item.id" />
            <el-option v-for="id in editorMissingWidgetIds" :key="`missing-${id}`" :label="`已删除的查询 #${id}`" :value="id" :disabled="!editorQueryIds.includes(id)" />
          </el-select>
          <div style="font-size:12px;color:#909399;margin-top:6px">先在 Explore 页面保存查询，再添加到 Dashboard。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDashboard">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>
