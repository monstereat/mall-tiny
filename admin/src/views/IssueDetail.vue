<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { monitorApi, type IssueActivity, type IssueAiAnalysis, type IssueDetail, type SourceMapStackFrame } from '../api/monitor';
import { useProjectStore } from '../stores/project';

type Breadcrumb = {
  type?: string;
  timestamp?: number;
  data?: Record<string, unknown>;
};

const route = useRoute();
const projects = useProjectStore();
const canWrite = computed(() => Boolean(projects.projects.find(item => item.projectKey === projects.currentKey)?.canWrite));
const detail = ref<IssueDetail | null>(null);
const sourceFrames = ref<SourceMapStackFrame[]>([]);
const loading = ref(false);
const resolvingSource = ref(false);
const analyzingIssue = ref(false);
const aiAnalysis = ref<IssueAiAnalysis | null>(null);
const activities = ref<IssueActivity[]>([]);
const activityHasMore = ref(false);
const nextActivityBeforeId = ref<number>();
const activityLoading = ref(false);
const activityWriting = ref(false);
const comment = ref('');
let issueLoadRequestId = 0;
let activityRequestId = 0;
let sourceResolveRequestId = 0;
let aiAnalysisRequestId = 0;

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
function sourceContext(frame: SourceMapStackFrame) {
  if (!frame.sourceContent || !frame.line) return [];
  const lines = frame.sourceContent.split('\n');
  const line = Math.max(1, frame.line);
  const start = Math.max(1, line - 4);
  const end = Math.min(lines.length, line + 4);
  return lines.slice(start - 1, end).map((value, index) => ({
    no: start + index,
    value,
    active: start + index === line
  }));
}

function isCurrentIssueLoad(requestId: number, projectKey: string, issueId: string) {
  return requestId === issueLoadRequestId
    && projects.currentKey === projectKey
    && String(route.params.id) === issueId;
}

async function load() {
  const requestId = ++issueLoadRequestId;
  const projectKey = projects.currentKey;
  const issueId = String(route.params.id);
  activityRequestId++;
  sourceResolveRequestId++;
  aiAnalysisRequestId++;
  analyzingIssue.value = false;
  loading.value = Boolean(projectKey);
  detail.value = null;
  sourceFrames.value = [];
  aiAnalysis.value = null;
  activities.value = [];
  activityHasMore.value = false;
  nextActivityBeforeId.value = undefined;
  activityLoading.value = false;
  activityWriting.value = false;
  resolvingSource.value = false;
  comment.value = '';
  if (!projectKey) return;
  try {
    const loadedDetail = await monitorApi.issue(projectKey, issueId);
    if (!isCurrentIssueLoad(requestId, projectKey, issueId)) return;
    detail.value = loadedDetail;
    await Promise.all([resolveSource(false), loadActivities(true)]);
  } catch (e) {
    if (isCurrentIssueLoad(requestId, projectKey, issueId)) {
      ElMessage.error(e instanceof Error ? e.message : '加载失败');
    }
  } finally {
    if (isCurrentIssueLoad(requestId, projectKey, issueId)) loading.value = false;
  }
}

async function loadActivities(reset = false) {
  const pageRequestId = ++activityRequestId;
  const loadRequestId = issueLoadRequestId;
  const projectKey = projects.currentKey;
  const issueId = String(route.params.id || '');
  if (!projectKey || !issueId) return;
  const isCurrentRequest = () => pageRequestId === activityRequestId
    && isCurrentIssueLoad(loadRequestId, projectKey, issueId);
  activityLoading.value = true;
  try {
    const page = await monitorApi.issueActivities(
      projectKey,
      issueId,
      reset ? undefined : nextActivityBeforeId.value,
      50
    );
    if (!isCurrentRequest()) return;
    activities.value = reset ? page.records : [...activities.value, ...page.records];
    activityHasMore.value = page.hasMore;
    nextActivityBeforeId.value = page.nextBeforeId;
  } catch (e) {
    if (isCurrentRequest()) {
      ElMessage.error(e instanceof Error ? e.message : '加载 Issue 活动失败');
    }
  } finally {
    if (isCurrentRequest()) activityLoading.value = false;
  }
}

async function addComment() {
  const text = comment.value.trim();
  if (!canWrite.value || !projects.currentKey || !detail.value?.issue || !text || activityWriting.value) return;
  const requestId = issueLoadRequestId;
  const projectKey = projects.currentKey;
  const issue = detail.value.issue;
  const issueId = String(issue.id);
  const isCurrentRequest = () => isCurrentIssueLoad(requestId, projectKey, issueId)
    && String(detail.value?.issue?.id) === issueId;
  activityWriting.value = true;
  try {
    await monitorApi.addIssueComment(projectKey, issue.id, text);
    if (!isCurrentRequest()) return;
    comment.value = '';
    await loadActivities(true);
    if (isCurrentRequest()) ElMessage.success('评论已添加');
  } catch (e) {
    if (isCurrentRequest()) ElMessage.error(e instanceof Error ? e.message : '评论添加失败');
  } finally {
    if (isCurrentRequest()) activityWriting.value = false;
  }
}

function statusLabel(status?: string) {
  return ({ unresolved: '未解决', resolved: '已解决', ignored: '已忽略' } as Record<string, string>)[status || ''] || status || '-';
}

async function analyzeIssue() {
  if (!projects.currentKey || !detail.value?.issue) return;
  const requestId = ++aiAnalysisRequestId;
  const projectKey = projects.currentKey;
  const issueId = detail.value.issue.id;
  analyzingIssue.value = true;
  aiAnalysis.value = null;
  try {
    const result = await monitorApi.analyzeIssue(projectKey, issueId);
    if (requestId !== aiAnalysisRequestId || projects.currentKey !== projectKey
      || String(route.params.id) !== String(issueId) || detail.value?.issue.id !== issueId) return;
    aiAnalysis.value = result;
  } catch (e) {
    if (requestId === aiAnalysisRequestId && projects.currentKey === projectKey
      && String(route.params.id) === String(issueId) && detail.value?.issue.id === issueId) {
      ElMessage.error(e instanceof Error ? e.message : 'AI 分析失败，请检查服务配置');
    }
  } finally {
    if (requestId === aiAnalysisRequestId) analyzingIssue.value = false;
  }
}

async function updateStatus(status: 'unresolved' | 'resolved' | 'ignored') {
  if (!canWrite.value || !projects.currentKey || !detail.value?.issue) return;
  const requestId = issueLoadRequestId;
  const projectKey = projects.currentKey;
  const issue = detail.value.issue;
  const issueId = String(issue.id);
  const isCurrentRequest = () => isCurrentIssueLoad(requestId, projectKey, issueId)
    && String(detail.value?.issue?.id) === issueId;
  try {
    const updatedIssue = await monitorApi.updateIssueStatus(projectKey, issue.id, status);
    if (!isCurrentRequest()) return;
    detail.value.issue = updatedIssue;
    await loadActivities(true);
    if (isCurrentRequest()) ElMessage.success('Issue 状态已更新');
  } catch (e) {
    if (isCurrentRequest()) ElMessage.error(e instanceof Error ? e.message : '状态更新失败');
  }
}

async function resolveSource(showMessage = true) {
  const sourceRequestId = ++sourceResolveRequestId;
  const loadRequestId = issueLoadRequestId;
  const projectKey = projects.currentKey;
  const issueId = String(route.params.id || '');
  if (!projectKey || !issueId || !latest.value) return;
  const isCurrentRequest = () => sourceRequestId === sourceResolveRequestId
    && isCurrentIssueLoad(loadRequestId, projectKey, issueId);
  const data = errorData.value;
  const bundleFile = String(data.file || '');
  const line = Number(data.line || 0);
  const column = Number(data.column || 0);
  const stack = typeof data.stack === 'string' ? data.stack : '';
  if (!latest.value.release || (!stack && (!bundleFile || !line))) {
    if (showMessage) ElMessage.warning('当前事件缺少 release 或可解析的错误调用栈');
    return;
  }

  resolvingSource.value = true;
  try {
    const frames = await monitorApi.resolveSourceMapStack(projectKey, {
      version: latest.value.release,
      environment: latest.value.environment || 'production',
      stack,
      file: bundleFile,
      line: line || undefined,
      column: column || undefined
    });
    if (!isCurrentRequest()) return;
    sourceFrames.value = frames;
    if (showMessage && !sourceFrames.value.some(frame => frame.mapped)) {
      ElMessage.warning('没有找到可用的 SourceMap 映射，已保留原始调用栈位置');
    }
  } catch (e) {
    if (showMessage && isCurrentRequest()) {
      ElMessage.error(e instanceof Error ? e.message : 'SourceMap 解析失败');
    }
  } finally {
    if (isCurrentRequest()) resolvingSource.value = false;
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
        <div v-if="canWrite">
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
          <el-button type="primary" :loading="analyzingIssue" @click="analyzeIssue">AI 分析 Issue</el-button>
          <el-button type="primary" :loading="resolvingSource" @click="resolveSource(true)">重新还原调用栈</el-button>
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
        <el-descriptions-item label="Trace ID">
          <span>{{ latest.trace_id || '-' }}</span>
          <el-button
            v-if="latest.trace_id"
            link
            type="primary"
            style="margin-left:8px"
            @click="$router.push({ path: '/logs', query: { traceId: latest.trace_id } })"
          >查看日志</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="时间">{{ latest.event_time }}</el-descriptions-item>
      </el-descriptions>

      <h4>Stack / Error Data</h4>
      <pre class="mono">{{ errorData.stack || JSON.stringify(errorData, null, 2) }}</pre>
    </div>

    <div v-if="aiAnalysis" class="panel">
      <div class="toolbar">
        <h3 style="margin:0">AI 排查建议</h3>
        <el-tag :type="aiAnalysis.severity === 'critical' || aiAnalysis.severity === 'high' ? 'danger' : 'info'">
          {{ aiAnalysis.severity }} · 置信度 {{ Math.round(aiAnalysis.confidence * 100) }}%
        </el-tag>
      </div>
      <p>{{ aiAnalysis.summary }}</p>
      <div v-if="aiAnalysis.possibleCauses.length">
        <strong>可能原因</strong>
        <ul><li v-for="(item,index) in aiAnalysis.possibleCauses" :key="`cause-${index}`">{{ item }}</li></ul>
      </div>
      <div v-if="aiAnalysis.recommendations.length">
        <strong>建议操作</strong>
        <ul><li v-for="(item,index) in aiAnalysis.recommendations" :key="`recommendation-${index}`">{{ item }}</li></ul>
      </div>
      <div v-if="aiAnalysis.evidence.length">
        <strong>依据</strong>
        <ul><li v-for="(item,index) in aiAnalysis.evidence" :key="`evidence-${index}`">{{ item }}</li></ul>
      </div>
      <el-alert
        v-if="aiAnalysis.limitations.length"
        title="部分证据源不可用或不完整；缺失内容表示未知，不代表没有相关证据。"
        type="warning"
        :closable="false"
        style="margin-top:12px"
      >
        <ul class="ai-limitations"><li v-for="(item,index) in aiAnalysis.limitations" :key="`limitation-${index}`">{{ item }}</li></ul>
      </el-alert>
    </div>

    <div v-if="detail?.issue" class="panel">
      <div class="toolbar">
        <h3 style="margin:0">Issue 活动</h3>
        <el-button v-if="activityHasMore" :loading="activityLoading" @click="loadActivities(false)">加载更早活动</el-button>
      </div>
      <div v-if="canWrite" style="display:flex;gap:10px;align-items:flex-start;margin:12px 0">
        <el-input v-model="comment" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="写一条 Issue 处理备注" />
        <el-button type="primary" :loading="activityWriting" :disabled="!comment.trim()" @click="addComment">评论</el-button>
      </div>
      <el-empty v-if="!activityLoading && !activities.length" description="暂无评论或状态变更" />
      <el-timeline v-else>
        <el-timeline-item v-for="item in activities" :key="item.id" :timestamp="new Date(item.createTime).toLocaleString()">
          <template v-if="item.activityType === 'comment'">
            <strong>{{ item.actorName }}</strong><span> 留下评论</span>
            <p style="white-space:pre-wrap;margin:8px 0 0">{{ item.commentText }}</p>
          </template>
          <template v-else>
            <strong>{{ item.actorName }}</strong><span> 将状态从 {{ statusLabel(item.previousStatus) }} 更新为 {{ statusLabel(item.newStatus) }}</span>
          </template>
        </el-timeline-item>
      </el-timeline>
    </div>

    <el-alert
      v-if="detail?.issue"
      title="点击 AI 分析后，裁剪并脱敏的错误消息/堆栈、Release/环境、事件时间、Issue 统计、Breadcrumb 类型、Trace ID 关联的同项目遥测与日志、SourceMap 源码片段，以及 Replay 片段数和时间范围会发送到管理员配置的 AI 服务。系统会过滤常见邮箱、Bearer/API Key 等内容；不会发送 userId、sessionId、页面 URL 或 Replay 原始内容。证据源会标记为可用、部分可用、无匹配或不可用；查询失败或跳过会作为分析限制展示，缺失内容不代表没有相关证据。数据留存规则取决于所选服务；OpenAI 的 store=false 关闭 Responses 应用状态存储，但默认滥用监控日志仍可能保留请求内容最长 30 天，具体以组织数据控制设置为准。"
      type="info"
      :closable="false"
      style="margin-top:12px"
    />

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

    <div v-if="sourceFrames.length" class="panel">
      <h3>SourceMap 调用栈还原</h3>
      <div v-for="frame in sourceFrames" :key="frame.index" class="stack-frame">
        <div class="stack-frame-title">
          <strong>#{{ frame.index + 1 }} {{ frame.function || '(anonymous)' }}</strong>
          <el-tag size="small" :type="frame.mapped ? 'success' : 'info'">
            {{ frame.mapped ? '已还原' : '原始位置' }}
          </el-tag>
        </div>
        <p v-if="frame.mapped" class="stack-location">
          {{ frame.source }}:{{ frame.line }}:{{ frame.column }}
          <span v-if="frame.name"> · {{ frame.name }}</span>
        </p>
        <p v-else class="stack-location">{{ frame.raw }}</p>
        <div v-if="frame.mapped && sourceContext(frame).length" class="mono source-code">
          <div
            v-for="line in sourceContext(frame)"
            :key="line.no"
            :class="{ active: line.active }"
          >
            <span class="line-no">{{ line.no }}</span>
            <span>{{ line.value }}</span>
          </div>
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
.stack-frame+.stack-frame{margin-top:18px;padding-top:18px;border-top:1px solid #e5e7eb}
.stack-frame-title{display:flex;align-items:center;gap:10px}
.stack-location{margin:8px 0;color:#475569;overflow-wrap:anywhere}
</style>
