<script setup lang="ts">
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { useProjectStore } from '../stores/project';

const auth = useAuthStore();
const projects = useProjectStore();
const router = useRouter();

onMounted(() => {
  void projects.load();
});

function logout() {
  auth.logout();
  router.push('/login');
}
</script>

<template>
  <el-container class="shell">
    <el-aside width="220px" class="aside">
      <div class="brand">Observe</div>
      <el-menu router :default-active="$route.path" class="menu">
        <el-menu-item index="/dashboard">Dashboard</el-menu-item>
        <el-menu-item index="/projects">Projects</el-menu-item>
        <el-menu-item index="/organization">Organization</el-menu-item>
        <el-menu-item index="/data-governance">Data Governance</el-menu-item>
        <el-menu-item index="/performance">Performance</el-menu-item>
        <el-menu-item index="/metrics">Metrics</el-menu-item>
        <el-menu-item index="/profiles">Profiling</el-menu-item>
        <el-menu-item index="/explore">Explore</el-menu-item>
        <el-menu-item index="/apis">API</el-menu-item>
        <el-menu-item index="/issues">Issues</el-menu-item>
        <el-menu-item index="/logs">Logs</el-menu-item>
        <el-menu-item index="/releases">Releases</el-menu-item>
        <el-menu-item index="/replays">Session Replay</el-menu-item>
        <el-menu-item index="/alerts">Alerts</el-menu-item>
        <el-menu-item index="/crons">Crons</el-menu-item>
        <el-menu-item index="/uptime">Uptime</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="header-title">企业级前端可观测与异常监控平台</div>
        <div class="header-actions">
          <el-select
            :model-value="projects.currentKey"
            style="width: 220px"
            placeholder="选择项目"
            @update:model-value="projects.setCurrent"
          >
            <el-option
              v-for="item in projects.projects"
              :key="item.projectKey"
              :label="item.name"
              :value="item.projectKey"
            />
          </el-select>
          <el-button @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.shell{min-height:100vh}.aside{background:#111827;color:#fff}.brand{font-size:22px;font-weight:800;padding:24px}.menu{border:0;background:transparent}.menu :deep(.el-menu-item){color:#cbd5e1}.menu :deep(.el-menu-item:hover),.menu :deep(.el-menu-item.is-active){background:#1f2937;color:#fff}.header{display:flex;align-items:center;justify-content:space-between;background:#fff;border-bottom:1px solid #e5e7eb}.header-title{font-weight:700}.header-actions{display:flex;gap:12px;align-items:center}.main{padding:22px}
</style>
