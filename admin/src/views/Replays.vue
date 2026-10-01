<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Replayer } from 'rrweb';
import { monitorApi, type MonitorReplay } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const route = useRoute();
const rows = ref<MonitorReplay[]>([]);
const sessionId = ref(String(route.query.sessionId || ''));
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
    const events = await monitorApi.replay(projects.currentKey, row.id);
    await nextTick();
    playerEl.value.innerHTML = '';
    replayer = new Replayer(events as any, {
      root: playerEl.value,
      speed: 1
    });
    replayer.play(0);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : 'Replay 加载失败');
  }
}

watch(() => projects.currentKey, load, { immediate: true });
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
      <div ref="playerEl" class="player" />
    </div>
  </section>
</template>

<style scoped>
.player{min-height:420px;overflow:auto;background:#f8fafc;border:1px solid #e5e7eb}
</style>
