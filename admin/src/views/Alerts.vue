<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type AlertDelivery, type AlertRecord, type AlertRule, type AlertSilence } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const rules = ref<AlertRule[]>([]);
const records = ref<AlertRecord[]>([]);
const dialog = ref(false);
const silenceDialog = ref(false);
const silences = ref<AlertSilence[]>([]);
const deliveries = ref<AlertDelivery[]>([]);
const silenceForm = reactive<{
  scope: AlertSilence['scope']; ruleId?: number; fingerprint: string;
  reason: string; durationSeconds: number;
}>({ scope: 'project', fingerprint: '', reason: '', durationSeconds: 1800 });
const form = reactive<AlertRule>({
  name: '',
  metric: 'error_count',
  operator: '>',
  thresholdValue: 100,
  windowSeconds: 300,
  durationSeconds: 0,
  cooldownSeconds: 900,
  level: 'warning',
  webhookUrl: '',
  enabled: 1
});

async function load() {
  if (!projects.currentKey) return;
  try {
    [rules.value, records.value, silences.value, deliveries.value] = await Promise.all([
      monitorApi.alertRules(projects.currentKey),
      monitorApi.alertRecords(projects.currentKey),
      monitorApi.alertSilences(projects.currentKey),
      monitorApi.alertDeliveries(projects.currentKey)
    ]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  }
}

function createSilence() {
  Object.assign(silenceForm, {
    scope: 'project', ruleId: undefined, fingerprint: '', reason: '', durationSeconds: 1800
  });
  silenceDialog.value = true;
}

async function saveSilence() {
  if (!projects.currentKey) return;
  try {
    await monitorApi.createAlertSilence(projects.currentKey, {
      ...silenceForm,
      ruleId: silenceForm.scope === 'rule' ? silenceForm.ruleId : undefined,
      fingerprint: silenceForm.scope === 'issue' ? silenceForm.fingerprint : undefined
    });
    silenceDialog.value = false;
    ElMessage.success('静默已创建');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建静默失败');
  }
}

async function removeSilence(silenceId: string) {
  if (!projects.currentKey) return;
  try {
    await monitorApi.deleteAlertSilence(projects.currentKey, silenceId);
    ElMessage.success('静默已取消');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消静默失败');
  }
}

function create() {
  Object.assign(form, {
    id: undefined, name: '', metric: 'error_count', operator: '>', thresholdValue: 100,
    windowSeconds: 300, durationSeconds: 0, cooldownSeconds: 900,
    level: 'warning', webhookUrl: '', enabled: 1
  });
  dialog.value = true;
}

function edit(row: AlertRule) {
  Object.assign(form, row);
  dialog.value = true;
}

async function save() {
  if (!projects.currentKey) return;
  try {
    if (form.id) await monitorApi.updateAlertRule(projects.currentKey, form.id, { ...form });
    else await monitorApi.createAlertRule(projects.currentKey, { ...form });
    dialog.value = false;
    ElMessage.success('保存成功');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  }
}

watch(() => projects.currentKey, load, { immediate: true });
</script>

<template>
  <section>
    <div class="toolbar">
      <h1 class="page-title" style="margin:0">Alerts</h1>
      <el-button @click="createSilence">创建静默</el-button>
      <el-button type="primary" @click="create">新增规则</el-button>
    </div>
    <div class="panel">
      <h3>静默与维护窗口</h3>
      <el-table :data="silences">
        <el-table-column prop="scope" label="范围" width="120" />
        <el-table-column prop="ruleId" label="规则 ID" width="100" />
        <el-table-column prop="fingerprint" label="Fingerprint" min-width="220" />
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column label="到期时间" width="190">
          <template #default="{ row }">{{ new Date(row.expiresAt).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button link type="danger" @click="removeSilence(row.id)">取消</el-button></template>
        </el-table-column>
      </el-table>
    </div>
    <div class="panel">
      <h3>告警规则</h3>
      <el-table :data="rules">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="metric" label="Metric" />
        <el-table-column label="条件" min-width="160">
          <template #default="{ row }">{{ row.operator }} {{ row.thresholdValue }}</template>
        </el-table-column>
        <el-table-column prop="windowSeconds" label="窗口(s)" />
        <el-table-column prop="durationSeconds" label="持续(s)" />
        <el-table-column prop="cooldownSeconds" label="Cooldown(s)" />
        <el-table-column prop="level" label="级别" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </div>
    <div class="panel">
      <h3>通知投递记录</h3>
      <el-table :data="deliveries">
        <el-table-column prop="createdAt" label="创建时间" width="190">
          <template #default="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column prop="alertStatus" label="告警状态" width="120" />
        <el-table-column prop="status" label="投递状态" width="120" />
        <el-table-column prop="attempts" label="尝试次数" width="100" />
        <el-table-column prop="lastError" label="最近错误" min-width="220" />
      </el-table>
    </div>
    <div class="panel">
      <h3>告警记录</h3>
      <el-table :data="records">
        <el-table-column prop="triggeredAt" label="时间" width="190" />
        <el-table-column prop="level" label="级别" width="90" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="metric" label="Metric" width="130" />
        <el-table-column prop="metricValue" label="当前值" />
        <el-table-column prop="thresholdValue" label="阈值" />
        <el-table-column prop="recoveredAt" label="恢复时间" width="190" />
        <el-table-column prop="message" label="消息" min-width="320" />
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="告警规则" width="560px">
      <el-form label-width="110px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="Metric">
          <el-select v-model="form.metric" style="width:100%">
            <el-option label="Error Count" value="error_count" />
            <el-option label="LCP" value="LCP" />
            <el-option label="FCP" value="FCP" />
            <el-option label="CLS" value="CLS" />
            <el-option label="TTFB" value="TTFB" />
            <el-option label="INP" value="INP" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作符"><el-select v-model="form.operator" style="width:100%"><el-option v-for="op in ['>','>=','<','<=','==']" :key="op" :value="op" :label="op" /></el-select></el-form-item>
        <el-form-item label="阈值"><el-input-number v-model="form.thresholdValue" style="width:100%" /></el-form-item>
        <el-form-item label="窗口(s)"><el-input-number v-model="form.windowSeconds" :min="60" style="width:100%" /></el-form-item>
        <el-form-item label="持续(s)"><el-input-number v-model="form.durationSeconds" :min="0" style="width:100%" /></el-form-item>
        <el-form-item label="Cooldown(s)"><el-input-number v-model="form.cooldownSeconds" :min="60" style="width:100%" /></el-form-item>
        <el-form-item label="级别"><el-select v-model="form.level" style="width:100%"><el-option label="warning" value="warning"/><el-option label="critical" value="critical"/></el-select></el-form-item>
        <el-form-item label="Webhook"><el-input v-model="form.webhookUrl" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="silenceDialog" title="创建告警静默" width="520px">
      <el-form label-width="110px">
        <el-form-item label="范围">
          <el-select v-model="silenceForm.scope" style="width:100%">
            <el-option label="项目" value="project" />
            <el-option label="告警规则" value="rule" />
            <el-option label="Issue" value="issue" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="silenceForm.scope === 'rule'" label="告警规则">
          <el-select v-model="silenceForm.ruleId" style="width:100%">
            <el-option v-for="rule in rules" :key="rule.id" :label="rule.name" :value="rule.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="silenceForm.scope === 'issue'" label="Fingerprint">
          <el-input v-model="silenceForm.fingerprint" />
        </el-form-item>
        <el-form-item label="时长(s)"><el-input-number v-model="silenceForm.durationSeconds" :min="60" :max="2592000" style="width:100%" /></el-form-item>
        <el-form-item label="原因"><el-input v-model="silenceForm.reason" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="silenceDialog=false">取消</el-button><el-button type="primary" @click="saveSilence">创建</el-button></template>
    </el-dialog>
  </section>
</template>
