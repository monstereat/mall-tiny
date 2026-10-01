<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type MonitorRelease } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const rows = ref<MonitorRelease[]>([]);
const loading = ref(false);

async function load() {
  if (!projects.currentKey) return;
  loading.value = true;
  try { rows.value = await monitorApi.releases(projects.currentKey); }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : '加载失败'); }
  finally { loading.value = false; }
}
watch(() => projects.currentKey, load, { immediate: true });
</script>

<template>
  <section>
    <h1 class="page-title">Releases</h1>
    <div class="panel">
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="version" label="Version" />
        <el-table-column prop="environment" label="Environment" />
        <el-table-column prop="gitCommit" label="Git Commit" min-width="180" />
        <el-table-column prop="branchName" label="Branch" />
        <el-table-column prop="sourceMapStatus" label="SourceMap" />
        <el-table-column prop="deployTime" label="Deploy Time" width="190" />
      </el-table>
    </div>
  </section>
</template>
