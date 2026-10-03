<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { monitorApi, type MonitorLogEntry } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const route = useRoute();
const projects = useProjectStore();
const traceId = ref(typeof route.query.traceId === 'string' ? route.query.traceId : '');
const textQuery = ref('');
const userId = ref('');
const tagKey = ref('');
const tagValue = ref('');
const hours = ref(24);
const entries = ref<MonitorLogEntry[]>([]);
const loading = ref(false);

function formatTags(value?: string) {
  if (!value) return '-';
  try {
    return value.split(',').map(token => {
      const separator = token.indexOf('.');
      if (separator < 1) return null;
      const decode = (part: string) => {
        const base64 = part.replaceAll('-', '+').replaceAll('_', '/')
          .padEnd(Math.ceil(part.length / 4) * 4, '=');
        const binary = atob(base64);
        return new TextDecoder().decode(Uint8Array.from(binary, char => char.charCodeAt(0)));
      };
      return `${decode(token.slice(0, separator))}=${decode(token.slice(separator + 1))}`;
    }).filter((tag): tag is string => Boolean(tag)).join(', ') || '-';
  } catch {
    return '-';
  }
}

async function search() {
  if (!projects.currentKey) {
    entries.value = [];
    return;
  }
  loading.value = true;
  try {
    const result = await monitorApi.logs(
      projects.currentKey, traceId.value.trim() || undefined, hours.value, 200, textQuery.value.trim() || undefined,
      userId.value.trim() || undefined, tagKey.value.trim() || undefined, tagValue.value.trim() || undefined
    );
    entries.value = result.entries;
  } catch (error) {
    entries.value = [];
    ElMessage.error(error instanceof Error ? error.message : '日志查询失败');
  } finally {
    loading.value = false;
  }
}

watch(() => route.query.traceId, value => {
  traceId.value = typeof value === 'string' ? value : '';
  void search();
}, { immediate: true });
watch(() => projects.currentKey, () => void search());
</script>

<template>
  <section>
    <h1 class="page-title">Application Logs</h1>
    <div class="panel">
      <el-form inline @submit.prevent="search">
        <el-form-item label="Trace ID">
          <el-input v-model="traceId" clearable placeholder="可选：输入 32 位 Trace ID" style="width:300px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="日志文本">
          <el-input v-model="textQuery" clearable placeholder="包含文本" style="width:260px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="User ID"><el-input v-model="userId" clearable /></el-form-item>
        <el-form-item label="Tag key"><el-input v-model="tagKey" clearable /></el-form-item>
        <el-form-item label="Tag value"><el-input v-model="tagValue" clearable /></el-form-item>
        <el-form-item label="时间范围">
          <el-select v-model="hours" style="width:150px">
            <el-option label="最近 1 小时" :value="1" />
            <el-option label="最近 24 小时" :value="24" />
            <el-option label="最近 3 天" :value="72" />
            <el-option label="最近 7 天" :value="168" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="search">查询</el-button>
      </el-form>
      <el-empty v-if="!loading && entries.length === 0" description="按当前项目、时间范围、Trace ID 或日志文本查询" />
      <el-table v-else v-loading="loading" :data="entries" row-key="timestamp">
        <el-table-column prop="timestamp" label="时间" width="220" />
        <el-table-column label="日志" min-width="500">
          <template #default="scope"><pre class="log-line">{{ scope.row.line }}</pre></template>
        </el-table-column>
        <el-table-column label="User ID" width="180" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.metadata.monitor_user_id || '-' }}</template>
        </el-table-column>
        <el-table-column label="Tags" width="260" show-overflow-tooltip>
          <template #default="scope">{{ formatTags(scope.row.metadata.monitor_tags) }}</template>
        </el-table-column>
        <el-table-column label="Span ID" width="180">
          <template #default="scope">{{ scope.row.metadata.span_id || scope.row.metadata.spanId || scope.row.labels.span_id || scope.row.labels.spanId || '-' }}</template>
        </el-table-column>
      </el-table>
      <div v-if="entries.length" class="log-count">显示最近 {{ entries.length }} 条，最多 200 条。</div>
    </div>
  </section>
</template>

<style scoped>
.log-line{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace}.log-count{margin-top:12px;color:#64748b;font-size:12px}
</style>
