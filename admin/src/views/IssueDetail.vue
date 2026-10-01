<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { monitorApi, type IssueDetail, type SourcePosition } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const route = useRoute();
const projects = useProjectStore();
const detail = ref<IssueDetail | null>(null);
const source = ref<SourcePosition | null>(null);
const loading = ref(false);

function payload(raw: string): Record<string, any> {
  try { return JSON.parse(raw); } catch { return {}; }
}

const latest = computed(() => detail.value?.events?.[0]);
const latestPayload = computed(() => latest.value ? payload(latest.value.payload) : {});
const errorData = computed(() => latestPayload.value?.data || {});

async function load() {
  if (!projects.currentKey) return;
  loading.value = true;
  source.value = null;
  try {
    detail.value = await monitorApi.issue(projects.currentKey, String(route.params.id));
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

async function resolveSource() {
  if (!projects.currentKey || !latest.value) return;
  const data = errorData.value;
  const bundleFile = String(data.file || '').split('/').pop()?.split('?')[0] || '';
  const line = Number(data.line || 0);
  const column = Number(data.column || 0);
  if (!latest.value.release || !bundleFile || !line || !column) {
    ElMessage.warning('当前事件缺少 release / file / line / column');
    return;
  }
  try {
    source.value = await monitorApi.resolveSourceMap(projects.currentKey, {
      version: latest.value.release,
      bundleFile,
      line,
      column
    });
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : 'SourceMap 解析失败');
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
        <el-button type="primary" @click="resolveSource">SourceMap 定位源码</el-button>
        <el-button v-if="latest?.session_id" @click="$router.push('/replays?sessionId=' + latest.session_id)">查看 Replay</el-button>
      </div>
      <el-descriptions v-if="latest" :column="2" border>
        <el-descriptions-item label="页面">{{ latest.page_url || '-' }}</el-descriptions-item>
        <el-descriptions-item label="用户">{{ latest.user_id || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Session">{{ latest.session_id || '-' }}</el-descriptions-item>
        <el-descriptions-item label="时间">{{ latest.event_time }}</el-descriptions-item>
      </el-descriptions>
      <pre class="mono">{{ JSON.stringify(errorData, null, 2) }}</pre>
    </div>

    <div v-if="source" class="panel">
      <h3>SourceMap 解析结果</h3>
      <p><strong>{{ source.source }}:{{ source.line }}:{{ source.column }}</strong></p>
      <p v-if="source.name">Symbol: {{ source.name }}</p>
      <pre v-if="source.sourceContent" class="mono">{{ source.sourceContent }}</pre>
    </div>

    <div class="panel">
      <h3>最近事件</h3>
      <el-table :data="detail?.events || []">
        <el-table-column prop="event_time" label="时间" width="190" />
        <el-table-column prop="release" label="Release" width="140" />
        <el-table-column prop="session_id" label="Session" min-width="180" />
        <el-table-column prop="page_url" label="页面" min-width="220" show-overflow-tooltip />
      </el-table>
    </div>
  </section>
</template>