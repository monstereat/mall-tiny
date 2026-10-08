<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { monitorApi, type MonitorTraceSpan, type MonitorTraceSummary } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const router = useRouter();
const hours = ref(24);
const environment = ref('');
const release = ref('');
const pageNum = ref(1);
const pageSize = ref(20);
const records = ref<MonitorTraceSummary[]>([]);
const total = ref(0);
const loading = ref(false);
const spanDetails = ref<Record<string, {
  loading: boolean;
  loaded: boolean;
  spans: MonitorTraceSpan[];
  error: string;
}>>({});

function formatTime(value?: string) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

async function search(resetPage = false) {
  if (resetPage) pageNum.value = 1;
  if (!projects.currentKey) {
    records.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  try {
    const result = await monitorApi.traces(projects.currentKey, {
      hours: hours.value,
      environment: environment.value.trim() || undefined,
      release: release.value.trim() || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    });
    records.value = result.records;
    total.value = result.total;
  } catch (error) {
    records.value = [];
    total.value = 0;
    ElMessage.error(error instanceof Error ? error.message : 'Trace 查询失败');
  } finally {
    loading.value = false;
  }
}

function openLogs(traceId: string) {
  router.push({ path: '/logs', query: { traceId } });
}

function spanKey(traceId: string) {
  return `${projects.currentKey}:${traceId}`;
}

async function loadSpans(traceId: string, retry = false) {
  const key = spanKey(traceId);
  const detail = spanDetails.value[key] ??= { loading: false, loaded: false, spans: [], error: '' };
  if (detail.loading || (detail.loaded && !retry)) return;
  detail.loading = true;
  detail.error = '';
  try {
    detail.spans = await monitorApi.traceSpans(projects.currentKey, traceId);
    detail.loaded = true;
  } catch (error) {
    detail.error = error instanceof Error ? error.message : 'Span 查询失败';
  } finally {
    detail.loading = false;
  }
}

function onExpandChange(row: MonitorTraceSummary, expandedRows: MonitorTraceSummary[]) {
  if (expandedRows.some(item => item.traceId === row.traceId)) void loadSpans(row.traceId);
}

function sortedSpans(spans: MonitorTraceSpan[]) {
  return [...spans].sort((a, b) => a.startTime - b.startTime);
}

function spanStyle(span: MonitorTraceSpan, spans: MonitorTraceSpan[]) {
  const traceStart = Math.min(...spans.map(item => item.startTime));
  const traceEnd = Math.max(...spans.map(item => item.startTime + Math.max(item.durationMs, 0)));
  const traceDuration = Math.max(traceEnd - traceStart, 1);
  const left = Math.min(100, Math.max(0, ((span.startTime - traceStart) / traceDuration) * 100));
  const width = Math.min(100 - left, Math.max(0.5, (Math.max(span.durationMs, 0) / traceDuration) * 100));
  return { left: `${left}%`, width: `${width}%` };
}

watch(() => projects.currentKey, () => {
  spanDetails.value = {};
  void search(true);
}, { immediate: true });
</script>

<template>
  <section>
    <h1 class="page-title">Trace 概览</h1>
    <div class="panel">
      <p class="scope-note">
        此页面展示所选时间范围内有事件记录的 Trace 汇总；展开详情可查看 Browser HTTP client 与通过项目 OTLP 接入的服务端 Spans 及 waterfall。
      </p>
      <el-form inline @submit.prevent="search(true)">
        <el-form-item label="时间范围">
          <el-select v-model="hours" style="width: 140px" @change="search(true)">
            <el-option label="最近 24 小时" :value="24" />
            <el-option label="最近 7 天" :value="168" />
            <el-option label="最近 30 天" :value="720" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境">
          <el-input v-model="environment" clearable placeholder="全部环境" style="width: 180px" @keyup.enter="search(true)" />
        </el-form-item>
        <el-form-item label="Release">
          <el-input v-model="release" clearable placeholder="全部 Release" style="width: 220px" @keyup.enter="search(true)" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="search(true)">查询</el-button>
      </el-form>

      <el-table v-loading="loading" :data="records" row-key="traceId" empty-text="当前条件下没有有事件记录的 Trace" @expand-change="onExpandChange">
        <el-table-column type="expand" width="44">
          <template #default="scope">
            <div class="span-detail">
              <p class="span-scope">Span 查询限定在当前项目；Browser 与服务端字段分开展示，不返回 OTLP attributes/tags。</p>
              <div v-if="spanDetails[spanKey(scope.row.traceId)]?.loading" class="span-state">正在加载 Span…</div>
              <el-alert
                v-else-if="spanDetails[spanKey(scope.row.traceId)]?.error"
                :title="spanDetails[spanKey(scope.row.traceId)].error"
                type="error"
                show-icon
                :closable="false"
              >
                <template #default>
                  <el-button link type="primary" @click="loadSpans(scope.row.traceId, true)">重试</el-button>
                </template>
              </el-alert>
              <el-empty
                v-else-if="spanDetails[spanKey(scope.row.traceId)]?.loaded && !spanDetails[spanKey(scope.row.traceId)].spans.length"
                description="此 Trace 没有已存储的 Browser 或服务端 Spans"
                :image-size="56"
              />
              <template v-else-if="spanDetails[spanKey(scope.row.traceId)]?.loaded">
                <div class="waterfall-head"><span>Span 操作</span><span>相对时间轴</span><span>耗时</span></div>
                <div v-for="span in sortedSpans(spanDetails[spanKey(scope.row.traceId)].spans)" :key="span.spanId" class="waterfall-row">
                  <div class="span-name" :title="span.description || span.op">
                    <el-tag size="small" :type="span.source === 'server' ? 'warning' : 'info'">{{ span.source === 'server' ? 'Server' : 'Browser' }}</el-tag>
                    <span>{{ span.description || span.op }}</span>
                    <small v-if="span.source === 'server'">{{ span.serviceName || 'unknown service' }} · {{ span.kind || 'unspecified' }}</small>
                  </div>
                  <div class="waterfall-track">
                    <div
                      class="waterfall-bar"
                      :class="{ 'waterfall-bar-error': span.status === 'error' }"
                      :style="spanStyle(span, spanDetails[spanKey(scope.row.traceId)].spans)"
                      :title="`${formatTime(new Date(span.startTime).toISOString())} · ${span.durationMs} ms · ${span.status}`"
                    />
                  </div>
                  <span class="span-duration">{{ span.durationMs }} ms</span>
                </div>
                <el-table :data="sortedSpans(spanDetails[spanKey(scope.row.traceId)].spans)" size="small" class="span-table" row-key="spanId">
                  <el-table-column prop="spanId" label="Span ID" min-width="150" show-overflow-tooltip />
                  <el-table-column prop="parentSpanId" label="Parent ID" min-width="150" show-overflow-tooltip>
                    <template #default="spanScope">{{ spanScope.row.parentSpanId || '-' }}</template>
                  </el-table-column>
                  <el-table-column label="来源 / 类型" width="150">
                    <template #default="spanScope">
                      <el-tag :type="spanScope.row.source === 'server' ? 'warning' : 'info'" size="small">
                        {{ spanScope.row.source === 'server' ? 'Server' : 'Browser' }}
                      </el-tag>
                      <span v-if="spanScope.row.source === 'server'" class="status-code">{{ spanScope.row.kind }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="状态 / HTTP" width="170">
                    <template #default="spanScope">
                      <el-tag :type="spanScope.row.status === 'error' ? 'danger' : 'success'" size="small">
                        {{ spanScope.row.status === 'error' ? '失败' : '正常' }}
                      </el-tag>
                      <span v-if="spanScope.row.statusCode" class="status-code">{{ spanScope.row.statusCode }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" min-width="180">
                    <template #default="spanScope">
                      <span>{{ spanScope.row.op || '-' }}</span>
                      <small v-if="spanScope.row.source === 'server'" class="span-service">{{ spanScope.row.serviceName || 'unknown service' }}</small>
                    </template>
                  </el-table-column>
                  <el-table-column label="开始时间" min-width="180">
                    <template #default="spanScope">{{ formatTime(new Date(spanScope.row.startTime).toISOString()) }}</template>
                  </el-table-column>
                  <el-table-column label="耗时" width="110">
                    <template #default="spanScope">{{ spanScope.row.durationMs }} ms</template>
                  </el-table-column>
                </el-table>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Trace ID" min-width="260" show-overflow-tooltip>
          <template #default="scope">
            <el-button link type="primary" @click="openLogs(scope.row.traceId)">{{ scope.row.traceId }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="eventCount" label="事件数" width="100" />
        <el-table-column label="首次事件" min-width="180">
          <template #default="scope">{{ formatTime(scope.row.firstEventAt) }}</template>
        </el-table-column>
        <el-table-column label="最近事件" min-width="180">
          <template #default="scope">{{ formatTime(scope.row.lastEventAt) }}</template>
        </el-table-column>
        <el-table-column prop="environment" label="环境" min-width="120" show-overflow-tooltip />
        <el-table-column prop="release" label="Release" min-width="160" show-overflow-tooltip />
        <el-table-column label="信号" min-width="180">
          <template #default="scope">
            <el-tag v-for="signal in scope.row.signalTypes" :key="signal" size="small" class="signal-tag">{{ signal }}</el-tag>
            <span v-if="!scope.row.signalTypes?.length">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="scope">
            <el-button link type="primary" @click="openLogs(scope.row.traceId)">查看关联日志</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="search()"
          @size-change="search(true)"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.scope-note{margin:0 0 16px;color:#64748b;font-size:13px}.signal-tag{margin:0 6px 4px 0}.pagination{display:flex;justify-content:flex-end;margin-top:16px}.span-detail{padding:8px 16px 16px}.span-scope{margin:0 0 12px;color:#64748b;font-size:12px}.span-state{padding:16px;color:#64748b}.waterfall-head,.waterfall-row{display:grid;grid-template-columns:minmax(180px,25%) 1fr 90px;gap:12px;align-items:center}.waterfall-head{padding:6px 0;color:#64748b;font-size:12px}.waterfall-row{min-height:28px;border-top:1px solid #f1f5f9}.span-name{display:flex;align-items:center;gap:6px;min-width:0;overflow:hidden;font-size:12px}.span-name>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.span-name small,.span-service{color:#64748b;font-size:11px}.waterfall-track{position:relative;height:18px;background:repeating-linear-gradient(90deg,#f8fafc 0,#f8fafc calc(25% - 1px),#e2e8f0 calc(25% - 1px),#e2e8f0 25%)}.waterfall-bar{position:absolute;top:4px;height:10px;min-width:3px;border-radius:3px;background:#3b82f6}.waterfall-bar-error{background:#ef4444}.span-duration{font-size:12px;color:#475569}.span-table{margin-top:12px}.status-code{margin-left:8px;color:#475569;font-size:12px}.span-service{display:block}
</style>
