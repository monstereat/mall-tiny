<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { monitorApi, type MonitorIssue, type MonitorRelease } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const canWrite = computed(() => Boolean(projects.projects.find(item => item.projectKey === projects.currentKey)?.canWrite));
const router = useRouter();
const rows = ref<MonitorIssue[]>([]);
const selected = ref<MonitorIssue[]>([]);
const releases = ref<MonitorRelease[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const status = ref('');
const hours = ref(720);
const release = ref('');
const searchText = ref('');
const environment = ref('');
const sort = ref<'lastSeen' | 'eventCount' | 'affectedUsers'>('lastSeen');
const loading = ref(false);
const bulkUpdating = ref(false);
const updatingIssueIds = ref<number[]>([]);

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
      release.value,
      sort.value,
      searchText.value.trim(),
      environment.value.trim()
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

function searchIssues() {
  page.value = 1;
  void load();
}

function trend(row: MonitorIssue) {
  return row.eventsLast24h - row.eventsPrevious24h;
}

async function updateSelected(status: 'resolved' | 'ignored') {
  if (!canWrite.value || !projects.currentKey || !selected.value.length) return;
  bulkUpdating.value = true;
  try {
    const changed = await monitorApi.updateIssuesStatus(
      projects.currentKey,
      selected.value.map(issue => issue.id),
      status
    );
    ElMessage.success(`已更新 ${changed} 个 Issue`);
    selected.value = [];
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量更新失败');
  } finally {
    bulkUpdating.value = false;
  }
}

async function updateIssue(issue: MonitorIssue, status: 'unresolved' | 'resolved' | 'ignored') {
  if (!canWrite.value || !projects.currentKey || updatingIssueIds.value.includes(issue.id)) return;
  updatingIssueIds.value = [...updatingIssueIds.value, issue.id];
  try {
    await monitorApi.updateIssueStatus(projects.currentKey, issue.id, status);
    ElMessage.success(status === 'resolved' ? 'Issue 已解决' : status === 'ignored' ? 'Issue 已忽略' : 'Issue 已重新打开');
    selected.value = selected.value.filter(item => item.id !== issue.id);
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Issue 状态更新失败');
  } finally {
    updatingIssueIds.value = updatingIssueIds.value.filter(id => id !== issue.id);
  }
}

watch(() => projects.currentKey, async () => {
  page.value = 1;
  await loadReleases();
  await load();
}, { immediate: true });
watch([status, hours, release, sort], () => {
  page.value = 1;
  void load();
});
</script>

<template>
  <section>
    <h1 class="page-title">Issues</h1>
    <div class="toolbar">
      <div style="display:flex;gap:10px;flex-wrap:wrap">
        <el-input
          v-model="searchText"
          clearable
          maxlength="128"
          placeholder="搜索标题、错误消息或 Fingerprint"
          style="width:260px"
          @keyup.enter="searchIssues"
          @clear="searchIssues"
        />
        <el-input
          v-model="environment"
          clearable
          maxlength="128"
          placeholder="Environment"
          style="width:170px"
          @keyup.enter="searchIssues"
          @clear="searchIssues"
        />
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
        <el-select v-model="sort" placeholder="排序方式" style="width:150px">
          <el-option label="最近发生" value="lastSeen" />
          <el-option label="发生次数" value="eventCount" />
          <el-option label="影响用户数" value="affectedUsers" />
        </el-select>
      </div>
      <el-button type="primary" @click="searchIssues">搜索</el-button>
      <el-button @click="load">刷新</el-button>
      <el-button v-if="canWrite && selected.length" type="success" :loading="bulkUpdating" @click="updateSelected('resolved')">解决 {{ selected.length }} 项</el-button>
      <el-button v-if="canWrite && selected.length" type="warning" :loading="bulkUpdating" @click="updateSelected('ignored')">忽略 {{ selected.length }} 项</el-button>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="rows" @selection-change="selected = $event" @row-click="openIssue">
        <el-table-column v-if="canWrite" type="selection" width="48" :selectable="() => !bulkUpdating" />
        <el-table-column prop="title" label="Issue" min-width="320">
          <template #default="{ row }">
            <span>{{ row.title }}</span>
            <el-tag v-if="row.newIssue" size="small" type="success" style="margin-left:8px">新发</el-tag>
            <el-tag v-if="row.status === 'unresolved' && row.regressedAt" size="small" type="warning" style="margin-left:6px">回归</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="eventCount" label="次数" width="90" />
        <el-table-column prop="affectedUsers" label="影响用户" width="110" />
        <el-table-column label="近24h / 前24h" width="155">
          <template #default="{ row }">
            <span>{{ row.eventsLast24h }} / {{ row.eventsPrevious24h }}</span>
            <el-tag v-if="trend(row) > 0" size="small" type="danger" style="margin-left:6px">+{{ trend(row) }}</el-tag>
            <el-tag v-else-if="trend(row) < 0" size="small" type="success" style="margin-left:6px">{{ trend(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latestRelease" label="Release" width="140" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="lastSeen" label="最近发生" width="190" />
        <el-table-column v-if="canWrite" label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'unresolved'"
              link
              type="success"
              :disabled="bulkUpdating"
              :loading="updatingIssueIds.includes(row.id)"
              @click.stop="updateIssue(row, 'resolved')"
            >解决</el-button>
            <el-button
              v-if="row.status === 'unresolved'"
              link
              type="warning"
              :disabled="bulkUpdating"
              :loading="updatingIssueIds.includes(row.id)"
              @click.stop="updateIssue(row, 'ignored')"
            >忽略</el-button>
            <el-button
              v-else
              link
              type="primary"
              :disabled="bulkUpdating"
              :loading="updatingIssueIds.includes(row.id)"
              @click.stop="updateIssue(row, 'unresolved')"
            >重新打开</el-button>
          </template>
        </el-table-column>
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
