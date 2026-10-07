<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type MonitorRelease, type MonitorReleaseHealth } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const rows = ref<MonitorRelease[]>([]);
const healthRows = ref<MonitorReleaseHealth[]>([]);
const hours = ref(168);
const loading = ref(false);
const healthByRelease = computed(() => new Map(
  healthRows.value.map(row => [`${row.release}\u0000${row.environment}`, row])
));

function healthFor(row: MonitorRelease): MonitorReleaseHealth | undefined {
  return healthByRelease.value.get(`${row.version}\u0000${row.environment}`);
}

async function load() {
  if (!projects.currentKey) return;
  loading.value = true;
  try {
    [rows.value, healthRows.value] = await Promise.all([
      monitorApi.releases(projects.currentKey),
      monitorApi.releaseHealth(projects.currentKey, hours.value)
    ]);
  }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : '加载失败'); }
  finally { loading.value = false; }
}
watch([() => projects.currentKey, hours], load, { immediate: true });
</script>

<template>
  <section>
    <h1 class="page-title">Releases</h1>
    <div class="panel">
      <div class="release-health-toolbar">
        <span>会话健康度</span>
        <el-select v-model="hours" style="width: 140px">
          <el-option :value="24" label="最近 24 小时" />
          <el-option :value="168" label="最近 7 天" />
          <el-option :value="720" label="最近 30 天" />
        </el-select>
      </div>
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="version" label="Version" />
        <el-table-column prop="environment" label="Environment" />
        <el-table-column prop="gitCommit" label="Git Commit" min-width="180" />
        <el-table-column prop="branchName" label="Branch" />
        <el-table-column prop="sourceMapStatus" label="SourceMap" />
        <el-table-column prop="deployTime" label="Deploy Time" width="190" />
        <el-table-column label="Sessions" width="120">
          <template #default="scope">{{ healthFor(scope.row)?.sessions ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="Crash-free sessions" width="170">
          <template #default="scope">
            {{ healthFor(scope.row)?.sessions ? `${healthFor(scope.row)?.crashFreeSessionsRate.toFixed(2)}%` : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="Crash-free users" width="160">
          <template #default="scope">
            {{ healthFor(scope.row)?.users ? `${healthFor(scope.row)?.crashFreeUsersRate.toFixed(2)}%` : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="Unhandled errors" width="150">
          <template #default="scope">{{ healthFor(scope.row)?.unhandledErrors ?? 0 }}</template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>

<style scoped>
.release-health-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  color: var(--text-secondary);
}
</style>
