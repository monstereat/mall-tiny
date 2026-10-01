<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { monitorApi, type AlertRecord, type AlertRule } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const rules = ref<AlertRule[]>([]);
const records = ref<AlertRecord[]>([]);
const dialog = ref(false);
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
    [rules.value, records.value] = await Promise.all([
      monitorApi.alertRules(projects.currentKey),
      monitorApi.alertRecords(projects.currentKey)
    ]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
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
      <el-button type="primary" @click="create">新增规则</el-button>
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
        <el-table-column prop="cooldownSeconds" label="Cooldown(s)" />
        <el-table-column prop="level" label="级别" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </div>
    <div class="panel">
      <h3>告警记录</h3>
      <el-table :data="records">
        <el-table-column prop="triggeredAt" label="时间" width="190" />
        <el-table-column prop="level" label="级别" width="90" />
        <el-table-column prop="metric" label="Metric" width="130" />
        <el-table-column prop="metricValue" label="当前值" />
        <el-table-column prop="thresholdValue" label="阈值" />
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
        <el-form-item label="Cooldown(s)"><el-input-number v-model="form.cooldownSeconds" :min="60" style="width:100%" /></el-form-item>
        <el-form-item label="级别"><el-select v-model="form.level" style="width:100%"><el-option label="warning" value="warning"/><el-option label="critical" value="critical"/></el-select></el-form-item>
        <el-form-item label="Webhook"><el-input v-model="form.webhookUrl" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>
