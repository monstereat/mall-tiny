<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { monitorApi, type MonitorIssue, type MonitorRelease } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const router = useRouter();
const rows = ref<MonitorIssue[]>([]);
const releases = ref<MonitorRelease[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const status = ref('');
const hours = ref(720);
const release = ref('');
const loading = ref(false);

async function load() {
  if (!projects.currentKey) return;
  loading.value = true;
  try {
    const data = await monitorApi.issues(
      projects.currentKey,
      page.value,
      pageSize.value,
      status.value,
      hours.value,
      release.value
    );
    rows.value = data.records;
    total.value = data.total;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadReleases() {
  release.value = '';
  if (!projects.currentKey) {
    releases.value = [];
    return;
  }
  try {
    releases.value = await monitorApi.releases(projects.currentKey);
  } catch {
    releases.value = [];
  }
}

function openIssue(row: MonitorIssue) {
  void router.push('/issues/' + row.id);
}

function changePage(value: number) {
  page.value = value;
  void load();
}

watch(() => projects.currentKey, async () => {
  page.value = 1;
  await loadReleases();
  await load();
}, { immediate: true });
watch([status, hours, release], () => {
  page.value = 1;
  void load();
});
</script>

<template>
  <section>
    <h1 class="page-title">Issues</h1>
    <div class="toolbar">
      <div style="display:flex;gap:10px">
        <el-select v-model="status" placeholder="状态" clearable style="width:140px">
          <el-option label="未解决" value="unresolved" />
          <el-option label="已解决" value="resolved" />
          <el-option label="已忽略" value="ignored" />
        </el-select>
        <el-select v-model="hours" style="width:140px">
          <el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" />
          <el-option label="最近 30 天" :value="720" />
          <el-option label="最近 90 天" :value="2160" />
        </el-select>
        <el-select v-model="release" clearable filterable placeholder="全部 Release" style="width:190px">
          <el-option v-for="item in releases" :key="item.id" :label="item.version" :value="item.version" />
        </el-select>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="rows" @row-click="openIssue">
        <el-table-column prop="title" label="Issue" min-width="320" show-overflow-tooltip />
        <el-table-column prop="eventCount" label="次数" width="90" />
        <el-table-column prop="affectedUsers" label="影响用户" width="110" />
        <el-table-column prop="latestRelease" label="Release" width="140" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="lastSeen" label="最近发生" width="190" />
      </el-table>
      <el-pagination
        style="margin-top:16px"
        layout="prev, pager, next, total"
        :current-page="page"
        :page-size="pageSize"
        :total="total"
        @current-change="changePage"
      />
    </div>
  </section>
</template>
