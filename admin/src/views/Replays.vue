<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Replayer } from 'rrweb';
import { monitorApi, type MonitorReplay } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const route = useRoute();
const rows = ref<MonitorReplay[]>([]);
const sessionId = ref(String(route.query.sessionId || ''));
const errorTimestamp = computed(() => {
  const raw = route.query.errorAt;
  if (!raw) return undefined;
  const value = Number(raw);
  if (Number.isFinite(value)) return value;
  const parsed = Date.parse(String(raw));
  return Number.isFinite(parsed) ? parsed : undefined;
});
const playerEl = ref<HTMLDivElement>();
let replayer: Replayer | undefined;

async function load() {
  if (!projects.currentKey) return;
  try { rows.value = await monitorApi.replays(projects.currentKey, sessionId.value); }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : '加载失败'); }
}

async function play(row: MonitorReplay) {
  if (!projects.currentKey || !playerEl.value) return;
  try {
    const fragments = rows.value.filter((item) => item.sessionId === row.sessionId);
    const chunks = await Promise.all(fragments.map((item) => monitorApi.replay(projects.currentKey!, item.id)));
    const events = chunks.flat() as Array<{ timestamp?: number; type?: number }>;
    events.sort((left, right) => (left.timestamp ?? 0) - (right.timestamp ?? 0));
    let playbackEvents = events;
    if (errorTimestamp.value !== undefined) {
      const start = errorTimestamp.value - 60_000;
      const end = errorTimestamp.value + 30_000;
      let snapshotIndex = -1;
      let metadataIndex = -1;
      events.forEach((event, index) => {
        if ((event.timestamp ?? Number.POSITIVE_INFINITY) <= start) {
          if (event.type === 2) snapshotIndex = index;
          if (event.type === 4) metadataIndex = index;
        }
      });
      playbackEvents = events.filter((event, index) =>
        index === snapshotIndex || index === metadataIndex ||
        ((event.timestamp ?? Number.NEGATIVE_INFINITY) >= start &&
          (event.timestamp ?? Number.POSITIVE_INFINITY) <= end)
      );
    }
    await nextTick();
    playerEl.value.innerHTML = '';
    replayer = new Replayer(playbackEvents as any, {
      root: playerEl.value,
      speed: 1
    });
    replayer.play(0);
    playerEl.value.scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : 'Replay 加载失败');
  }
}

watch(() => projects.currentKey, load, { immediate: true });
watch(() => [route.query.sessionId, route.query.errorAt], () => {
  sessionId.value = String(route.query.sessionId || '');
  load();
});
onBeforeUnmount(() => { replayer?.pause(); });
</script>

<template>
  <section>
    <h1 class="page-title">Session Replay</h1>
    <div class="toolbar">
      <el-input v-model="sessionId" placeholder="Session ID" clearable style="width:320px" @keyup.enter="load" />
      <el-button @click="load">查询</el-button>
    </div>
    <div class="panel">
      <el-table :data="rows">
        <el-table-column prop="sessionId" label="Session" min-width="200" />
        <el-table-column prop="releaseVersion" label="Release" width="130" />
        <el-table-column prop="eventCount" label="rrweb Events" width="120" />
        <el-table-column prop="createTime" label="创建时间" width="190" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button link type="primary" @click="play(row)">播放</el-button></template>
        </el-table-column>
      </el-table>
    </div>
    <div class="panel">
      <h3>Replay Player</h3>
      <p v-if="errorTimestamp">错误前 60 秒至后 30 秒</p>
      <div ref="playerEl" class="player" />
    </div>
  </section>
</template>

<style scoped>
.player{min-height:420px;overflow:auto;background:#f8fafc;border:1px solid #e5e7eb}
</style>
