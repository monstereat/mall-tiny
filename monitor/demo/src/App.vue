<script setup lang="ts">
function jsError() {
  const value: any = undefined;
  value.profile.name = 'boom';
}

function promiseError() {
  Promise.reject(new Error('demo unhandled promise rejection'));
}

async function apiError() {
  await fetch('https://httpstat.us/500');
}

function vueError() {
  throw new Error('demo vue click handler error');
}
</script>

<template>
  <main class="page">
    <h1>Observability SDK Demo</h1>
    <p>用于验证 SDK → Ingest → Kafka → Storage → Admin 全链路。</p>
    <div class="actions">
      <button @click="jsError">JS Error</button>
      <button @click="promiseError">Promise Error</button>
      <button @click="apiError">API 500</button>
      <button @click="vueError">Vue Error</button>
    </div>
    <label>
      Replay 脱敏输入
      <input placeholder="这里的输入会被 mask" />
    </label>
    <section data-monitor-block class="secret">
      该区域会被 Replay 完全屏蔽
    </section>
  </main>
</template>

<style scoped>
.page { font-family: system-ui, sans-serif; max-width: 760px; margin: 48px auto; padding: 24px; }
.actions { display: flex; gap: 12px; flex-wrap: wrap; margin: 24px 0; }
button { padding: 10px 16px; cursor: pointer; }
label { display: grid; gap: 8px; margin: 24px 0; }
input { padding: 10px; }
.secret { padding: 20px; border: 1px dashed #999; }
</style>
