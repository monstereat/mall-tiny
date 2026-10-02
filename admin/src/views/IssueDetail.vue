<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { monitorApi, type IssueDetail, type SourcePosition } from '../api/monitor';
import { useProjectStore } from '../stores/project';

type Breadcrumb = {
  type?: string;
  timestamp?: number;
  data?: Record<string, unknown>;
};

const route = useRoute();
const projects = useProjectStore();
const detail = ref<IssueDetail | null>(null);
const source = ref<SourcePosition | null>(null);
const loading = ref(false);
const resolvingSource = ref(false);

function payload(raw: string): Record<string, any> {
  try { return JSON.parse(raw); } catch { return {}; }
}

function time(value?: number): string {
  return value ? new Date(value).toLocaleString() : '-';
}

const latest = computed(() => detail.value?.events?.[0]);
const latestPayload = computed(() => latest.value ? payload(latest.value.payload) : {});
const errorData = computed(() => latestPayload.value?.data || {});
const breadcrumbs = computed<Breadcrumb[]>(() =>
  Array.isArray(errorData.value?.breadcrumbs) ? errorData.value.breadcrumbs : []
);
const sourceContext = computed(() => {
  if (!source.value?.sourceContent) return [];
  const lines = source.value.sourceContent.split('\n');
  const line = Math.max(1, source.value.line);
  const start = Math.max(1, line - 4);
  const end = Math.min(lines.length, line + 4);
  return lines.slice(start - 1, end).map((value, index) => ({
    no: start + index,
    value,
    active: start + index === line
  }));
});

async function load() {
  if (!projects.currentKey) return;
  loading.value = true;
  source.value = null;
  try {
    detail.value = await monitorApi.issue(projects.currentKey, String(route.params.id));
    await resolveSource(false);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function updateStatus(status: 'unresolved' | 'resolved' | 'ignored') {
  if (!projects.currentKey || !detail.value?.issue) return;
  try {
    detail.value.issue = await monitorApi.updateIssueStatus(projects.currentKey, detail.value.issue.id, status);
    ElMessage.success('Issue 状态已更新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '状态更新失败');
  }
}

async function resolveSource(showMessage = true) {
  if (!projects.currentKey || !latest.value) return;
  const data = errorData.value;
  const bundleFile = String(data.file || '').split('/').pop()?.split('?')[0] || '';
  const line = Number(data.line || 0);
  const column = Number(data.column || 0);
  if (!latest.value.release || !bundleFile || !line || !column) {
    if (showMessage) ElMessage.warning('当前事件缺少 release / file / line / column');
    return;
  }

  resolvingSource.value = true;
  try {
    source.value = await monitorApi.resolveSourceMap(projects.currentKey, {
      version: latest.value.release,
      environment: latest.value.environment || 'production',
      bundleFile,
      line,
      column
    });
    if (showMessage && !source.value) ElMessage.warning('未找到对应 SourceMap 映射');
  } catch (e) {
    if (showMessage) ElMessage.error(e instanceof Error ? e.message : 'SourceMap 解析失败');
  } finally {
    resolvingSource.value = false;
  }
}

watch([() => projects.currentKey, () => route.params.id], load, { immediate: true });
</script>

<template>
  <section v-loading="loading">
    <el-page-header content="Issue Detail" @back="$router.push('/issues')" />

    <div v-if="detail?.issue" class="panel" style="margin-top:18px">
      <div class="toolbar">
        <h2 style="margin:0">{{ detail.issue.title }}</h2>
        <div>
          <el-button @click="updateStatus('unresolved')">重新打开</el-button>
          <el-button type="success" @click="updateStatus('resolved')">已解决</el-button>
          <el-button type="warning" @click="updateStatus('ignored')">忽略</el-button>
        </div>
      </div>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="次数">{{ detail.issue.eventCount }}</el-descriptions-item>
        <el-descriptions-item label="影响用户">{{ detail.issue.affectedUsers }}</el-descriptions-item>
        <el-descriptions-item label="Release">{{ detail.issue.latestRelease || '-' }}</el-descriptions-item>
        <el-descriptions-item label="First Seen">{{ detail.issue.firstSeen }}</el-descriptions-item>
        <el-descriptions-item label="Last Seen">{{ detail.issue.lastSeen }}</el-descriptions-item>
        <el-descriptions-item label="Fingerprint">{{ detail.issue.fingerprint.slice(0,16) }}...</el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="panel">
      <div class="toolbar">
        <h3 style="margin:0">最新错误现场</h3>
        <div>
          <el-button type="primary" :loading="resolvingSource" @click="resolveSource(true)">重新定位源码</el-button>
          <el-button
            v-if="latest?.session_id"
            @click="$router.push({ path: '/replays', query: { sessionId: latest.session_id, errorAt: latest.event_time } })"
          >查看错误前后 Replay</el-button>
        </div>
      </div>
      <el-descriptions v-if="latest" :column="3" border>
        <el-descriptions-item label="页面">{{ latest.page_url || '-' }}</el-descriptions-item>
        <el-descriptions-item label="用户">{{ latest.user_id || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Session">{{ latest.session_id || '-' }}</el-descriptions-item>
        <el-descriptions-item label="环境">{{ latest.environment || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Release">{{ latest.release || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Trace ID">{{ latest.trace_id || '-' }}</el-descriptions-item>
        <el-descriptions-item label="时间">{{ latest.event_time }}</el-descriptions-item>
      </el-descriptions>

      <h4>Stack / Error Data</h4>
      <pre class="mono">{{ errorData.stack || JSON.stringify(errorData, null, 2) }}</pre>
    </div>

    <div v-if="breadcrumbs.length" class="panel">
      <h3>Breadcrumb 行为轨迹</h3>
      <el-timeline>
        <el-timeline-item
          v-for="(item,index) in breadcrumbs"
          :key="index"
          :timestamp="time(item.timestamp)"
          placement="top"
        >
          <strong>{{ item.type || 'event' }}</strong>
          <pre class="mono" style="margin-top:8px">{{ JSON.stringify(item.data || {}, null, 2) }}</pre>
        </el-timeline-item>
      </el-timeline>
    </div>

    <div v-if="source" class="panel">
      <h3>SourceMap 源码定位</h3>
      <p><strong>{{ source.source }}:{{ source.line }}:{{ source.column }}</strong></p>
      <p v-if="source.name">Symbol: {{ source.name }}</p>
      <div v-if="sourceContext.length" class="mono source-code">
        <div
          v-for="line in sourceContext"
          :key="line.no"
          :class="{ active: line.active }"
        >
          <span class="line-no">{{ line.no }}</span>
          <span>{{ line.value }}</span>
        </div>
      </div>
    </div>

    <div class="panel">
      <h3>最近事件</h3>
      <el-table :data="detail?.events || []">
        <el-table-column prop="event_time" label="时间" width="190" />
        <el-table-column prop="environment" label="环境" width="120" />
        <el-table-column prop="release" label="Release" width="140" />
        <el-table-column prop="session_id" label="Session" min-width="180" />
        <el-table-column prop="page_url" label="页面" min-width="220" show-overflow-tooltip />
      </el-table>
    </div>
  </section>
</template>

<style scoped>
.source-code{overflow:auto;border:1px solid #e5e7eb;border-radius:6px;background:#0f172a;color:#e2e8f0;padding:10px}
.source-code>div{display:flex;min-height:24px;line-height:24px;white-space:pre}
.source-code>div.active{background:#7f1d1d}
.line-no{display:inline-block;width:58px;color:#64748b;text-align:right;margin-right:16px;user-select:none}
</style>
