<script setup lang="ts">
import { inject } from 'vue';
import type { BrowserMonitor } from '@observe/browser';

const replayErrorBufferTest = inject<boolean>('monitorReplayErrorBufferTest', false);
const issueRegressionRunId = new URLSearchParams(location.search).get('issueRegression');
const monitor = inject<BrowserMonitor['client']>('monitorClient');
const businessAnalyticsEnabled = inject<boolean>('monitorBusinessAnalyticsEnabled', false);
const monitorApiOrigin = inject<string>('monitorApiOrigin', 'http://localhost:8080');
const businessDemoUrl = new URL(location.href);
businessDemoUrl.searchParams.set('businessAnalytics', '1');

function businessClick() {
  monitor?.track('register_click', { source: 'sdk_demo', button_id: 'register_entry' });
}

function identifyBusinessUser() {
  monitor?.identify('demo-business-user');
  monitor?.page({ name: 'sdk_demo', properties: { source: 'identified_demo' } });
}

function jsError() {
  if (issueRegressionRunId) {
    throw new Error(`issue regression probe ${issueRegressionRunId}`);
  }
  const value: any = undefined;
  value.profile.name = 'boom';
}

function promiseError() {
  Promise.reject(new Error('demo unhandled promise rejection'));
}

async function apiError() {
  await fetch('https://httpstat.us/500');
}

async function traceApiProbe() {
  await fetch(`${monitorApiOrigin}/admin/info`);
}

function vueError() {
  throw new Error('demo vue click handler error');
}

function replayErrorProbe() {
  window.dispatchEvent(new ErrorEvent('error', {
    error: new Error('demo replay error-buffer probe'),
    message: 'demo replay error-buffer probe'
  }));
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
      <button @click="traceApiProbe">API Trace Probe (unauthenticated)</button>
      <button @click="vueError">Vue Error</button>
      <button v-if="replayErrorBufferTest" @click="replayErrorProbe">Error Replay Probe</button>
    </div>
    <label>
      Replay 脱敏输入
      <input placeholder="这里的输入会被 mask" />
    </label>
    <section>
      <h2>业务数据上报</h2>
      <template v-if="businessAnalyticsEnabled">
        <p>页面访问和可见停留时长自动上报；点击下面按钮后，可在管理后台“业务分析”查看。</p>
        <div class="actions">
          <button @click="businessClick">上报注册入口点击</button>
          <button @click="identifyBusinessUser">识别演示用户并记录页面</button>
        </div>
      </template>
      <a v-else :href="businessDemoUrl.href">开启业务分析演示</a>
    </section>
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
