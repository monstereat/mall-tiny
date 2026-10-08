<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { monitorApi, type MonitorExploreEvent, type MonitorSavedExploreQuery, type MonitorMetricFormulaResult } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const router = useRouter();
const hours = ref(24);
const type = ref('');
const environment = ref('');
const release = ref('');
const traceId = ref('');
const query = ref('');
const userId = ref('');
const tagKey = ref('');
const tagValue = ref('');
const groupBy = ref('signal');
const aggregationTagKey = ref('');
const aggregation = ref('count');
const aggregateField = ref('user');
const offset = ref(0);
const rows = ref<MonitorExploreEvent[]>([]);
const hasMore = ref(false);
const loading = ref(false);
const savedQueries = ref<MonitorSavedExploreQuery[]>([]);
const selectedSavedQueryId = ref<number>();
const saveDialog = ref(false);
const saveName = ref('');
const savingQuery = ref(false);
const aggregationBuckets = ref<Array<{ value: string; count: number }>>([]);
const metricNames = ref<string[]>([]);
const formulaEnabled = ref(false);
const formulaMetrics = ref<string[]>([]);
const formulaExpression = ref('a / b * 100');
const formulaPoints = ref<MonitorMetricFormulaResult['points']>([]);

function aggregationDimension() {
  return groupBy.value === 'tag' ? `tag.${aggregationTagKey.value.trim()}` : groupBy.value;
}

function isBasicNumericAggregation(value = aggregation.value) {
  return ['sum', 'avg', 'min', 'max'].includes(value);
}

function isNumericAggregation(value = aggregation.value) {
  return ['sum', 'avg', 'min', 'max', 'p50', 'p75', 'p95'].includes(value);
}

function isNumericAggregationAllowed(value: string) {
  if (['p50', 'p75', 'p95'].includes(value)) return ['performance', 'metric'].includes(type.value);
  if (!isBasicNumericAggregation(value)) return false;
  if (['performance', 'metric'].includes(type.value)) return true;
  return (type.value === 'logs' || !type.value) && hours.value <= 168
    && ['signal', 'environment', 'release', 'level'].includes(groupBy.value);
}

function normalizeAggregationSelection() {
  if (type.value === 'logs' && hours.value > 168) hours.value = 168;
  const logsOrMixedNumeric = type.value === 'logs' || (!type.value && isBasicNumericAggregation());
  if (logsOrMixedNumeric && !['signal', 'environment', 'release', 'level'].includes(groupBy.value)) {
    groupBy.value = 'signal';
  }
  if (!type.value && hours.value <= 168 && aggregation.value === 'count_unique'
    && aggregateField.value === 'user' && groupBy.value !== 'signal') {
    groupBy.value = 'signal';
  }
  if (isNumericAggregation(aggregation.value) && !isNumericAggregationAllowed(aggregation.value)) {
    aggregation.value = 'count';
  }
  if (aggregation.value === 'count_unique' && aggregateField.value === 'value') aggregateField.value = 'user';
  if (type.value === 'logs' && aggregation.value === 'count_unique') aggregateField.value = 'user';
  if (isNumericAggregation(aggregation.value) || (type.value === 'logs' && aggregation.value !== 'count_unique')) {
    aggregateField.value = 'value';
  }
}

function handleExploreSelectionChange() {
  normalizeAggregationSelection();
  void search();
}

function handleGroupByChange() {
  normalizeAggregationSelection();
  void search();
}

function groupingDisabled(group: string) {
  if (!type.value && hours.value <= 168 && aggregation.value === 'count_unique'
    && aggregateField.value === 'user' && group !== 'signal') return true;
  return !type.value && isBasicNumericAggregation() && hours.value <= 168
    && !['signal', 'environment', 'release', 'level'].includes(group);
}

function selectedMetricAliases() {
  return formulaMetrics.value.map((name, index) => `${String.fromCharCode(97 + index)} = ${name}`).join(' · ');
}

function handleSignalTypeChange() {
  const previousHours = hours.value;
  normalizeAggregationSelection();
  if (type.value === 'logs' && previousHours > 168) {
    ElMessage.info('Logs 查询和聚合最多支持最近 7 天，时间范围已调整为 7 天');
  }
  if (type.value !== 'metric') formulaEnabled.value = false;
  void search();
}

function handleAggregationChange() {
  normalizeAggregationSelection();
  void search();
}

async function search(reset = true) {
  if (!projects.currentKey) return;
  if (!type.value && hours.value <= 168 && aggregation.value === 'count_unique'
    && aggregateField.value === 'user' && groupBy.value !== 'signal') {
    ElMessage.warning('混合唯一用户统计目前只支持按信号分组');
    return;
  }
  if (type.value === 'logs' && hours.value > 168) {
    ElMessage.warning('Logs 查询和聚合最多支持最近 7 天');
    return;
  }
  if (isNumericAggregation(aggregation.value) && !isNumericAggregationAllowed(aggregation.value)) {
    ElMessage.warning('当前信号、时间范围或分组不支持该数值聚合');
    return;
  }
  if (type.value !== 'logs' && groupBy.value === 'tag' && !/^[A-Za-z0-9_.-]{1,64}$/.test(aggregationTagKey.value.trim())) {
    ElMessage.warning('按 Tag 聚合时请输入有效的 Tag key');
    return;
  }
  if (formulaEnabled.value && (type.value !== 'metric' || formulaMetrics.value.length < 2)) {
    ElMessage.warning('多指标公式需要至少选择两个 Metrics');
    return;
  }
  if (formulaEnabled.value && formulaExpression.value.length > 128) {
    ElMessage.warning('公式最多 128 个字符');
    return;
  }
  if (reset) offset.value = 0;
  loading.value = true;
  try {
    const params = {
      hours: hours.value, type: type.value, environment: environment.value,
      release: release.value, traceId: traceId.value.trim(), query: query.value.trim(),
      userId: userId.value, tagKey: tagKey.value, tagValue: tagValue.value,
      offset: offset.value
    };
    const formulaRequest = formulaEnabled.value ? monitorApi.exploreMetricFormula(projects.currentKey, {
      hours: hours.value, environment: environment.value, release: release.value,
      traceId: traceId.value.trim(), query: query.value.trim(), userId: userId.value,
      tagKey: tagKey.value, tagValue: tagValue.value,
      metricNames: formulaMetrics.value, formula: formulaExpression.value.trim()
    }) : Promise.resolve(null);
    const metricsRequest = type.value === 'metric'
      ? monitorApi.metrics(projects.currentKey, hours.value, environment.value, release.value).then(result => result.summary.map(item => item.name)).catch(() => [])
      : Promise.resolve([]);
    const [result, aggregate, formulas, names] = await Promise.all([
      monitorApi.explore(projects.currentKey, params),
      monitorApi.exploreAggregation(projects.currentKey, {
        ...params, groupBy: aggregationDimension(), aggregation: aggregation.value, field: aggregateField.value
      }),
      formulaRequest,
      metricsRequest
    ]);
    rows.value = result.events;
    hasMore.value = result.hasMore;
    aggregationBuckets.value = aggregate?.buckets || [];
    formulaPoints.value = formulas?.points || [];
    metricNames.value = names;
  } catch (error) {
    rows.value = [];
    hasMore.value = false;
    aggregationBuckets.value = [];
    formulaPoints.value = [];
    ElMessage.error(error instanceof Error ? error.message : 'Explore 查询失败');
  } finally {
    loading.value = false;
  }
}

async function loadSavedQueries() {
  if (!projects.currentKey) { savedQueries.value = []; return; }
  try { savedQueries.value = await monitorApi.savedExploreQueries(projects.currentKey); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存查询加载失败'); }
}

function applySavedQuery(id: number) {
  const saved = savedQueries.value.find(item => item.id === id);
  if (!saved) return;
  const criteria = saved.criteria;
  hours.value = criteria.hours;
  type.value = criteria.type || '';
  environment.value = criteria.environment || '';
  release.value = criteria.release || '';
  traceId.value = criteria.traceId || '';
  query.value = criteria.query || '';
  userId.value = criteria.userId || '';
  tagKey.value = criteria.tagKey || '';
  tagValue.value = criteria.tagValue || '';
  groupBy.value = criteria.groupBy?.startsWith('tag.') ? 'tag' : criteria.groupBy || 'signal';
  aggregationTagKey.value = criteria.groupBy?.startsWith('tag.') ? criteria.groupBy.slice(4) : '';
  aggregation.value = criteria.aggregation || 'count';
  aggregateField.value = criteria.field || 'user';
  normalizeAggregationSelection();
  formulaEnabled.value = Boolean(criteria.formula && criteria.formulaMetrics?.length);
  formulaExpression.value = criteria.formula || 'a / b * 100';
  formulaMetrics.value = criteria.formulaMetrics || [];
  void search();
}

async function saveCurrentQuery() {
  if (!projects.currentKey || !saveName.value.trim()) return;
  savingQuery.value = true;
  const criteria = {
    name: saveName.value.trim(), hours: hours.value, type: type.value || undefined,
    environment: environment.value || undefined, release: release.value || undefined,
    traceId: traceId.value.trim() || undefined, query: query.value.trim() || undefined,
    userId: userId.value.trim() || undefined, tagKey: tagKey.value.trim() || undefined,
    tagValue: tagValue.value.trim() || undefined, groupBy: aggregationDimension(),
    aggregation: aggregation.value, field: aggregateField.value,
    formula: formulaEnabled.value ? formulaExpression.value.trim() : undefined,
    formulaMetrics: formulaEnabled.value ? formulaMetrics.value : undefined
  };
  try {
    const saved = await monitorApi.createSavedExploreQuery(projects.currentKey, criteria);
    await loadSavedQueries();
    selectedSavedQueryId.value = saved.id;
    saveDialog.value = false;
    saveName.value = '';
    ElMessage.success('查询已保存到当前项目');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存查询失败'); }
  finally { savingQuery.value = false; }
}

async function updateSelectedQuery() {
  if (!projects.currentKey || selectedSavedQueryId.value == null) return;
  const saved = savedQueries.value.find(item => item.id === selectedSavedQueryId.value);
  if (!saved?.canModify) return;
  savingQuery.value = true;
  const criteria = {
    name: saved.name, hours: hours.value, type: type.value || undefined,
    environment: environment.value || undefined, release: release.value || undefined,
    traceId: traceId.value.trim() || undefined, query: query.value.trim() || undefined,
    userId: userId.value.trim() || undefined, tagKey: tagKey.value.trim() || undefined,
    tagValue: tagValue.value.trim() || undefined, groupBy: aggregationDimension(),
    aggregation: aggregation.value, field: aggregateField.value,
    formula: formulaEnabled.value ? formulaExpression.value.trim() : undefined,
    formulaMetrics: formulaEnabled.value ? formulaMetrics.value : undefined
  };
  try {
    const updated = await monitorApi.updateSavedExploreQuery(projects.currentKey, saved.id, criteria);
    savedQueries.value = savedQueries.value.map(item => item.id === updated.id ? updated : item);
    ElMessage.success('项目查询已更新');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '更新查询失败'); }
  finally { savingQuery.value = false; }
}

async function deleteSelectedQuery() {
  if (!projects.currentKey || selectedSavedQueryId.value == null) return;
  const saved = savedQueries.value.find(item => item.id === selectedSavedQueryId.value);
  if (!saved?.canModify) return;
  try {
    await ElMessageBox.confirm(`删除保存查询“${saved.name}”？`, '删除保存查询', { type: 'warning' });
    await monitorApi.deleteSavedExploreQuery(projects.currentKey, saved.id);
    savedQueries.value = savedQueries.value.filter(item => item.id !== saved.id);
    selectedSavedQueryId.value = undefined;
    ElMessage.success('保存查询已删除');
  } catch (error) {
    if (error instanceof Error && error.message !== 'cancel' && error.message !== 'close') {
      ElMessage.error(error.message || '删除保存查询失败');
    }
  }
}

function nextPage() {
  offset.value += 100;
  void search(false);
}

async function openIssue(row: MonitorExploreEvent) {
  if (!projects.currentKey || !row.fingerprint) return;
  try {
    const issue = await monitorApi.issueByFingerprint(projects.currentKey, row.fingerprint);
    if (issue) void router.push(`/issues/${issue.id}`);
    else ElMessage.info('该错误事件暂时没有关联的 Issue');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Issue 查询失败');
  }
}

watch(() => projects.currentKey, () => {
  offset.value = 0;
  selectedSavedQueryId.value = undefined;
  void loadSavedQueries();
  void search();
}, { immediate: true });
</script>

<template>
  <section>
    <h1 class="page-title">Explore</h1>
    <div class="panel">
      <el-form inline @submit.prevent="search()">
        <el-form-item label="保存查询">
          <el-select v-model="selectedSavedQueryId" clearable filterable placeholder="选择项目查询" style="width:220px" @change="applySavedQuery">
            <el-option v-for="item in savedQueries" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-button class="saved-query-action" @click="saveDialog = true">保存当前条件</el-button>
          <el-button v-if="savedQueries.find(item => item.id === selectedSavedQueryId)?.canModify" :loading="savingQuery" @click="updateSelectedQuery">更新所选查询</el-button>
          <el-button v-if="savedQueries.find(item => item.id === selectedSavedQueryId)?.canModify" type="danger" plain @click="deleteSelectedQuery">删除</el-button>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-select v-model="hours" style="width:140px" @change="handleExploreSelectionChange">
            <el-option label="最近 1 小时" :value="1" />
            <el-option label="最近 24 小时" :value="24" />
            <el-option label="最近 7 天" :value="168" />
            <el-option label="最近 30 天" :value="720" :disabled="type === 'logs' || (!type && isBasicNumericAggregation())" />
          </el-select>
        </el-form-item>
        <el-form-item label="信号">
          <el-select v-model="type" clearable placeholder="全部信号" style="width:150px" @change="handleSignalTypeChange">
            <el-option label="Error" value="error" />
            <el-option label="Performance" value="performance" />
            <el-option label="Behavior / Trace" value="behavior" />
            <el-option label="Replay" value="replay" />
            <el-option label="Metrics" value="metric" />
            <el-option label="Profiling" value="profile" />
            <el-option label="Logs" value="logs" />
          </el-select>
        </el-form-item>
        <el-form-item label="Environment"><el-input v-model="environment" clearable /></el-form-item>
        <el-form-item label="Release"><el-input v-model="release" clearable /></el-form-item>
        <el-form-item label="Trace ID"><el-input v-model="traceId" clearable /></el-form-item>
        <el-form-item label="查询语法"><el-input v-model="query" clearable placeholder='environment:production release:"1.2.3" level:error' @keyup.enter="search()" /></el-form-item>
        <el-form-item label="User ID"><el-input v-model="userId" clearable /></el-form-item>
        <el-form-item label="Tag key"><el-input v-model="tagKey" clearable /></el-form-item>
        <el-form-item label="Tag value"><el-input v-model="tagValue" clearable /></el-form-item>
        <el-button type="primary" :loading="loading" @click="search()">查询</el-button>
      </el-form>
      <div class="query-help">字段过滤默认以 AND 组合，也可用 <code>OR</code> 和括号分组；支持 <code>!值</code> 排除、引号值及 environment、release、trace、user、url、event、level 和 tag.&lt;key&gt;。OR/括号查询只写字段条件；普通文本可单独搜索事件内容。</div>
      <el-alert v-if="type === 'logs'" title="Logs 支持文本、Trace、Environment、Release、Level、User ID 和 Tag 筛选；可按信号、Environment、Release 或 Level 统计 count()、count_unique(user) 及 sum/avg/min/max(value)。唯一用户统计只覆盖带有 userId 元数据的日志；查询时间范围最多 7 天。" type="info" :closable="false" show-icon />
      <el-alert v-if="isNumericAggregation(aggregation) && (type === 'logs' || !type || type === 'performance' || type === 'metric')" title="数值聚合只统计 value 存在且可转换为有限数字的有效样本，avg 按有效样本计算。Logs 从日志消息 JSON 顶层的 value 读取；混合信号及不同指标的单位可能不同，请先筛选到同一指标和单位。" type="info" :closable="false" show-icon />
      <el-alert v-else-if="!type && hours <= 168 && aggregation === 'count_unique' && aggregateField === 'user'" title="混合唯一用户统计会精确合并 ClickHouse 与 Loki 的用户 ID，仅支持按信号分组和 Loki 可执行的筛选；每次查询最多处理 10,000 个 signal-user 组合，超出时请缩小范围。" type="info" :closable="false" show-icon />
      <el-alert v-if="!type && hours > 168" title="全部信号的 30 天查询和聚合不包含 Logs；Loki Logs 统计最多支持 7 天。" type="warning" :closable="false" show-icon />
      <div class="aggregation">
        <div class="aggregation-header">
          <strong>事件聚合</strong>
          <el-select v-model="groupBy" style="width:190px" @change="handleGroupByChange">
            <el-option label="按信号" value="signal" />
            <el-option label="按 Environment" value="environment" />
            <el-option label="按 Release" value="release" />
            <el-option v-if="type !== 'logs'" label="按 URL" value="url" :disabled="groupingDisabled('url')" />
            <el-option label="按 Level" value="level" />
            <el-option v-if="type !== 'logs'" label="按 Tag key" value="tag" :disabled="groupingDisabled('tag')" />
          </el-select>
          <el-input v-if="groupBy === 'tag'" v-model="aggregationTagKey" maxlength="64" placeholder="Tag key" style="width:140px" @change="search()" />
          <el-select v-model="aggregation" style="width:190px" @change="handleAggregationChange">
            <el-option label="事件数 count()" value="count" />
            <el-option :label="type === 'logs' ? '唯一用户 count_unique(user)' : '去重计数 count_unique()'" value="count_unique" :disabled="!type && hours <= 168 && groupBy !== 'signal'" />
            <el-option label="总和 sum(value)" value="sum" :disabled="!isNumericAggregationAllowed('sum')" />
            <el-option label="平均 avg(value)" value="avg" :disabled="!isNumericAggregationAllowed('avg')" />
            <el-option label="最小 min(value)" value="min" :disabled="!isNumericAggregationAllowed('min')" />
            <el-option label="最大 max(value)" value="max" :disabled="!isNumericAggregationAllowed('max')" />
            <el-option label="P50" value="p50" :disabled="!isNumericAggregationAllowed('p50')" />
            <el-option label="P75" value="p75" :disabled="!isNumericAggregationAllowed('p75')" />
            <el-option label="P95" value="p95" :disabled="!isNumericAggregationAllowed('p95')" />
          </el-select>
          <el-select v-if="aggregation === 'count_unique'" v-model="aggregateField" style="width:150px" :disabled="type === 'logs'" @change="search()">
            <el-option label="User" value="user" />
            <template v-if="type !== 'logs'">
            <el-option label="Event ID" value="event" />
            <el-option label="Trace ID" value="trace" />
            <el-option label="URL" value="url" />
            </template>
          </el-select>
        </div>
        <el-table :data="aggregationBuckets" size="small" max-height="260">
          <el-table-column prop="value" label="分组值" min-width="180" show-overflow-tooltip />
          <el-table-column prop="count" :label="isNumericAggregation(aggregation) ? '有效数值样本' : aggregation === 'count_unique' && aggregateField === 'user' ? '唯一用户数' : '样本数'" width="120" />
          <el-table-column v-if="aggregation !== 'count' && !(aggregation === 'count_unique' && aggregateField === 'user')" prop="aggregateValue" :label="aggregation === 'count_unique' ? '去重值' : aggregation" width="150" />
        </el-table>
        <div v-if="type === 'metric'" class="metric-formula">
          <el-checkbox v-model="formulaEnabled">多指标公式</el-checkbox>
          <template v-if="formulaEnabled">
            <div class="metric-formula-controls">
              <el-select v-model="formulaMetrics" multiple filterable collapse-tags placeholder="选择 2–5 个项目 Metrics" style="min-width:340px;max-width:520px">
                <el-option v-for="metric in metricNames" :key="metric" :label="metric" :value="metric" />
              </el-select>
              <el-input v-model="formulaExpression" maxlength="128" placeholder="a / b * 100" style="width:240px" />
              <el-button type="primary" :loading="loading" @click="search()">计算公式</el-button>
            </div>
            <div class="query-help">别名按所选顺序从 a 开始：{{ selectedMetricAliases() || '请选择 Metrics' }}。支持 +、-、*、/、括号和数字常量；只计算所有指标都有数据的小时，除零的点会省略。保存查询会保留指标和公式。</div>
            <el-table :data="formulaPoints" size="small" max-height="220">
              <el-table-column prop="bucket" label="小时" min-width="200" />
              <el-table-column prop="value" label="公式结果" min-width="160" />
            </el-table>
          </template>
        </div>
      </div>
    </div>

    <el-dialog v-model="saveDialog" title="保存 Explore 查询" width="420px">
      <el-input v-model="saveName" maxlength="100" show-word-limit placeholder="查询名称" @keyup.enter="saveCurrentQuery" />
      <template #footer>
        <el-button @click="saveDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingQuery" :disabled="!saveName.trim()" @click="saveCurrentQuery">保存</el-button>
      </template>
    </el-dialog>

    <div class="panel results">
      <el-table v-loading="loading" :data="rows" row-key="event_id">
        <el-table-column prop="event_time" label="时间" width="205" />
        <el-table-column label="信号" width="130">
          <template #default="scope"><el-tag>{{ scope.row.signal_type }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="title" label="事件" min-width="300" show-overflow-tooltip />
        <el-table-column prop="environment" label="环境" width="130" />
        <el-table-column prop="release" label="Release" width="150" show-overflow-tooltip />
        <el-table-column label="关联" width="190">
          <template #default="scope">
            <el-link v-if="scope.row.trace_id" type="primary" :href="`/logs?traceId=${encodeURIComponent(scope.row.trace_id)}`" @click.stop>Logs / Trace</el-link>
            <el-link v-if="scope.row.session_id" type="primary" :href="`/replays?sessionId=${encodeURIComponent(scope.row.session_id)}`" @click.stop>Replay</el-link>
            <span v-if="!scope.row.trace_id && !scope.row.session_id">-</span>
          </template>
        </el-table-column>
        <el-table-column label="Issue" width="90">
          <template #default="scope">
            <el-button v-if="scope.row.signal_type === 'error' && scope.row.fingerprint" link type="primary" @click="openIssue(scope.row)">查看</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column type="expand">
          <template #default="scope"><pre class="payload">{{ scope.row.payload }}</pre></template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <span>{{ offset + rows.length }} 条{{ hasMore ? '以上' : '' }}</span>
        <el-button :disabled="!hasMore || loading" @click="nextPage">下一页</el-button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.query-help{margin:-4px 0 14px;color:#64748b;font-size:12px}.saved-query-action{margin-left:8px}.aggregation{margin-top:16px}.aggregation-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}.aggregation-header strong{font-size:14px}.metric-formula{margin-top:18px;padding-top:14px;border-top:1px solid #e5e7eb}.metric-formula-controls{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:8px 0 12px}.results{margin-top:16px}.pagination{display:flex;align-items:center;justify-content:space-between;margin-top:14px;color:#64748b}.payload{max-height:360px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace}
</style>
