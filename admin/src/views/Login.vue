<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '../stores/auth';

const form = reactive({ username: 'admin', password: 'macro123' });
const loading = ref(false);
const auth = useAuthStore();
const router = useRouter();

async function submit() {
  loading.value = true;
  try {
    await auth.login(form.username, form.password);
    await router.replace('/dashboard');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败');
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1>Observability Admin</h1>
      <p>Spring Boot + Kafka + ClickHouse 企业级监控平台</p>
      <el-form @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" show-password placeholder="密码" @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="submit">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page{min-height:100vh;display:grid;place-items:center;background:linear-gradient(135deg,#0f172a,#1d4ed8)}.login-card{width:400px}.login-card h1{margin:0 0 8px}.login-card p{color:#64748b;margin:0 0 24px}
</style>
