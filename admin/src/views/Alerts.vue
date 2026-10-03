<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { monitorApi, type AlertDelivery, type AlertNotificationRoute, type AlertNotificationRouteOption, type AlertRecord, type AlertRule, type AlertSilence } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const rules = ref<AlertRule[]>([]);
const records = ref<AlertRecord[]>([]);
const dialog = ref(false);
const silenceDialog = ref(false);
const silences = ref<AlertSilence[]>([]);
const deliveries = ref<AlertDelivery[]>([]);
const routeOptions = ref<AlertNotificationRouteOption[]>([]);
const tenantRoutes = ref<AlertNotificationRoute[]>([]);
const canManageRoutes = ref(false);
const routesDialog = ref(false);
const routeFormDialog = ref(false);
const routeForm = reactive<{ id?: number; name: string; webhookUrl: string }>({ name: '', webhookUrl: '' });
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
    const project = projects.projects.find(item => item.projectKey === projects.currentKey);
    [rules.value, records.value, silences.value, deliveries.value, routeOptions.value] = await Promise.all([
      monitorApi.alertRules(projects.currentKey),
      monitorApi.alertRecords(projects.currentKey),
      monitorApi.alertSilences(projects.currentKey),
      monitorApi.alertDeliveries(projects.currentKey),
      monitorApi.alertNotificationRoutes(projects.currentKey)
    ]);
    if (project?.tenantId) {
      try {
        tenantRoutes.value = await monitorApi.tenantAlertNotificationRoutes(project.tenantId);
        canManageRoutes.value = true;
      } catch {
        tenantRoutes.value = [];
        canManageRoutes.value = false;
      }
    } else {
      tenantRoutes.value = [];
      canManageRoutes.value = false;
    }
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
    level: 'warning', webhookUrl: '', notificationRouteId: undefined, enabled: 1
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

function createRoute() {
  Object.assign(routeForm, { id: undefined, name: '', webhookUrl: '' });
  routeFormDialog.value = true;
}

function editRoute(route: AlertNotificationRoute) {
  Object.assign(routeForm, route);
  routeFormDialog.value = true;
}

async function saveRoute() {
  const project = projects.projects.find(item => item.projectKey === projects.currentKey);
  if (!project?.tenantId) return;
  try {
    const payload = { name: routeForm.name.trim(), webhookUrl: routeForm.webhookUrl.trim() };
    if (routeForm.id) await monitorApi.updateTenantAlertNotificationRoute(project.tenantId, routeForm.id, payload);
    else await monitorApi.createTenantAlertNotificationRoute(project.tenantId, payload);
    routeFormDialog.value = false;
    await load();
    ElMessage.success('通知路由已保存');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '通知路由保存失败');
  }
}

async function deleteRoute(route: AlertNotificationRoute) {
  const project = projects.projects.find(item => item.projectKey === projects.currentKey);
  if (!project?.tenantId) return;
  try {
    await ElMessageBox.confirm(`删除租户通知路由“${route.name}”？`, '删除通知路由', { type: 'warning' });
    await monitorApi.deleteTenantAlertNotificationRoute(project.tenantId, route.id);
    await load();
    ElMessage.success('通知路由已删除');
  } catch (e) {
    if (e instanceof Error && e.message !== 'cancel' && e.message !== 'close') {
      ElMessage.error(e.message || '通知路由删除失败');
    }
  }
}

watch(() => projects.currentKey, load, { immediate: true });
</script>

<template>
  <section>
    <div class="toolbar">
      <h1 class="page-title" style="margin:0">Alerts</h1>
      <el-button @click="createSilence">创建静默</el-button>
      <el-button v-if="canManageRoutes" @click="routesDialog = true">租户通知路由</el-button>
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
      <el-alert
        title="Cron Monitor 告警"
        description="将 Metric 设为 cron_unhealthy_count，即可统计项目内处于 warning 或 error 的启用 Cron 监控，并复用当前告警规则的静默、恢复和 Webhook 投递。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-alert
        title="Uptime Monitor 告警"
        description="uptime_unhealthy_count 统计 warning/down 的启用站点；uptime_max_latency_ms 使用启用站点最近一次检查的最大延迟。两者都复用当前规则的持续时间、静默、恢复与 Webhook 投递。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-alert
        title="API 请求失败告警"
        description="将 Metric 设为 api_failure_count，可按窗口统计 Browser SDK 上报的 API Behavior 事件：HTTP 4xx/5xx 与网络失败（status=0）。规则继续使用当前项目的持续时间、静默、恢复和 Webhook 投递。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-alert
        title="项目日志错误告警"
        description="log_error_count 按窗口统计 Loki 中带当前项目标识的 ERROR 日志，每 30 秒评估一次；触发后继续使用当前规则的持续时间、静默、恢复和 Webhook 投递。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
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
          <el-select v-model="form.metric" filterable allow-create default-first-option style="width:100%">
            <el-option label="Error Count" value="error_count" />
            <el-option label="API Failure Count" value="api_failure_count" />
            <el-option label="Log Error Count" value="log_error_count" />
            <el-option label="Cron Unhealthy Count" value="cron_unhealthy_count" />
            <el-option label="Uptime Unhealthy Count" value="uptime_unhealthy_count" />
            <el-option label="Uptime Max Latency (ms)" value="uptime_max_latency_ms" />
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
        <el-form-item label="通知路由"><el-select v-model="form.notificationRouteId" clearable placeholder="租户共享路由（可选）" style="width:100%"><el-option v-for="route in routeOptions" :key="route.id" :label="route.name" :value="route.id" /></el-select></el-form-item>
        <el-form-item label="Webhook 兼容"><el-input v-model="form.webhookUrl" placeholder="未选择共享路由时使用" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="routesDialog" title="租户共享通知路由" width="760px">
      <div class="toolbar"><span>路由可被当前租户的多个项目告警规则共用。</span><el-button type="primary" @click="createRoute">新增路由</el-button></div>
      <el-table :data="tenantRoutes">
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column prop="webhookUrl" label="Webhook URL" min-width="360" show-overflow-tooltip />
        <el-table-column label="操作" width="150">
          <template #default="{ row }"><el-button link type="primary" @click="editRoute(row)">编辑</el-button><el-button link type="danger" @click="deleteRoute(row)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="routeFormDialog" :title="routeForm.id ? '编辑通知路由' : '新增通知路由'" width="520px">
      <el-form label-width="110px">
        <el-form-item label="名称"><el-input v-model="routeForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="Webhook URL"><el-input v-model="routeForm.webhookUrl" maxlength="1024" placeholder="https://hooks.example.com/..." /></el-form-item>
      </el-form>
      <template #footer><el-button @click="routeFormDialog = false">取消</el-button><el-button type="primary" @click="saveRoute">保存</el-button></template>
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
