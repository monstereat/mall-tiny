<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type ProfileDetail, type ProfileFlameNode, type ProfileSummary } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const profiles = ref<ProfileSummary[]>([]);
const selected = ref<ProfileDetail | null>(null);
const releases = ref<{ version: string }[]>([]);
const hours = ref(24);
const environment = ref('');
const release = ref('');
const loading = ref(false);
const detailLoading = ref(false);

interface FlameRow { name: string; value: number; left: number; width: number; depth: number }
const flameRows = computed(() => {
  const rows: FlameRow[] = [];
  const profile = selected.value;
  const roots = profile?.flamegraph ?? [];
  const total = roots.reduce((sum, node) => sum + node.value, 0);
  function visit(nodes: ProfileFlameNode[], parentValue: number, base: number, parentWidth: number, depth: number) {
    let cursor = base;
    for (const node of nodes) {
      const width = parentValue > 0 ? parentWidth * node.value / parentValue : 0;
      rows.push({ name: node.name, value: node.value, left: cursor, width, depth });
      visit(node.children, node.value, cursor, width, depth + 1);
      cursor += width;
    }
  }
  visit(roots, total, 0, 100, 0);
  return rows;
});
const flameLevels = computed(() => Array.from(
  { length: Math.max(0, ...flameRows.value.map(row => row.depth + 1)) },
  (_, depth) => flameRows.value.filter(row => row.depth === depth)
));

async function loadReleases() {
  if (!projects.currentKey) { releases.value = []; return; }
  try { releases.value = await monitorApi.releases(projects.currentKey); }
  catch { releases.value = []; }
}

async function load() {
  if (!projects.currentKey) { profiles.value = []; selected.value = null; return; }
  loading.value = true;
  try {
    profiles.value = await monitorApi.profiles(projects.currentKey, hours.value, environment.value, release.value);
    if (selected.value && !profiles.value.some(item => item.eventId === selected.value?.profile.eventId)) selected.value = null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Profile 查询失败');
  } finally { loading.value = false; }
}

async function openProfile(row: ProfileSummary) {
  if (!projects.currentKey) return;
  detailLoading.value = true;
  try { selected.value = await monitorApi.profile(projects.currentKey, row.eventId); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : 'Profile 详情加载失败'); }
  finally { detailLoading.value = false; }
}

watch(() => projects.currentKey, async () => { selected.value = null; await loadReleases(); await load(); }, { immediate: true });
watch([hours, environment, release], () => void load());
</script>

<template>
  <section>
    <div class="toolbar">
      <h1 class="page-title" style="margin:0">Continuous Profiling</h1>
      <div class="filters">
        <el-select v-model="hours" style="width:140px">
          <el-option label="最近 1 小时" :value="1" /><el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" /><el-option label="最近 30 天" :value="720" />
        </el-select>
        <el-select v-model="environment" clearable placeholder="全部环境" style="width:150px">
          <el-option label="production" value="production" /><el-option label="staging" value="staging" /><el-option label="development" value="development" />
        </el-select>
        <el-select v-model="release" clearable filterable placeholder="全部 Release" style="width:190px">
          <el-option v-for="item in releases" :key="item.version" :label="item.version" :value="item.version" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
      </div>
    </div>

    <el-alert title="内存 Profile 是浏览器估算值，需要安全上下文、crossOriginIsolated 和 measureUserAgentSpecificMemory() 支持；不同浏览器或版本的数值不可直接比较。" type="info" :closable="false" show-icon />

    <div class="panel">
      <el-table v-loading="loading" :data="profiles" @row-click="openProfile">
        <el-table-column prop="eventTime" label="时间" min-width="205" />
        <el-table-column prop="name" label="Profile" width="140" />
        <el-table-column prop="unit" label="单位" width="120" />
        <el-table-column prop="sampleCount" label="堆栈样本" width="115" />
        <el-table-column prop="totalValue" label="总权重" width="120" />
        <el-table-column prop="release" label="Release" min-width="180" show-overflow-tooltip />
        <el-table-column prop="environment" label="环境" width="140" />
      </el-table>
      <el-empty v-if="!loading && !profiles.length" description="暂无 Profile；可使用 SDK recordProfile 上报 collapsed stack samples，或按需启用 CPU / 内存采样" />
    </div>

    <div v-if="selected" v-loading="detailLoading" class="panel">
      <div class="detail-header">
        <div><h2>{{ selected.profile.name }}{{ selected.profile.name === 'JavaScript Memory' ? ' 采样' : ' 火焰图' }}</h2><span>{{ selected.profile.eventTime }} · {{ selected.profile.release || '无 Release' }} · {{ selected.profile.environment || '无环境' }}</span></div>
        <div>{{ selected.profile.totalValue }} {{ selected.profile.unit }}</div>
      </div>
      <div class="flamegraph">
        <div v-for="(level, depth) in flameLevels" :key="depth" class="flame-row">
          <div v-for="row in level" :key="`${row.left}-${row.name}`" class="flame-bar"
               :style="{ left: `${row.left}%`, width: `${Math.max(row.width, 0.3)}%` }"
               :title="`${row.name}: ${row.value} ${selected.profile.unit}`">
            <span>{{ row.name }}</span><small>{{ row.value }}</small>
          </div>
        </div>
      </div>
      <el-empty v-if="!flameRows.length" description="Profile 没有可展示的样本" />
    </div>
  </section>
</template>

<style scoped>
.filters{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.detail-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.detail-header h2{margin:0 0 6px}.detail-header span{color:#64748b}.flamegraph{display:flex;flex-direction:column;gap:4px;overflow:hidden}.flame-row{height:30px;position:relative}.flame-bar{position:absolute;height:28px;box-sizing:border-box;padding:4px 8px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;border:1px solid #ffffffa8;border-radius:4px;background:#dbeafe;color:#1e3a8a;display:flex;justify-content:space-between;gap:8px;font-size:12px}.flame-row:nth-child(4n+2) .flame-bar{background:#dcfce7;color:#14532d}.flame-row:nth-child(4n+3) .flame-bar{background:#fef3c7;color:#78350f}.flame-row:nth-child(4n) .flame-bar{background:#fce7f3;color:#831843}
</style>
