<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '../stores/auth';

const form = reactive({ username: '', password: '' });
const tenantKey = ref('');
const loading = ref(false);
const auth = useAuthStore();
const router = useRouter();

onMounted(async () => {
  const params = new URLSearchParams(location.hash.slice(1));
  const code = params.get('ssoCode');
  if (params.has('ssoCode') || params.has('ssoError')) history.replaceState(null, '', location.pathname + location.search);
  if (code) {
    loading.value = true;
    try {
      await auth.exchangeSamlCode(code);
      await router.replace('/dashboard');
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : 'SAML 登录失败，请重试');
    } finally { loading.value = false; }
  } else if (params.has('ssoError')) {
    ElMessage.error('SAML 登录未能匹配到此组织的已启用成员账号');
  }
});

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

function samlLogin() {
  const key = tenantKey.value.trim();
  if (!key) { ElMessage.warning('请输入组织 Key'); return; }
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(key)) { ElMessage.warning('组织 Key 格式不正确'); return; }
  location.assign(`/saml2/authenticate/${encodeURIComponent(key)}`);
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
      <el-divider>企业单点登录</el-divider>
      <el-input v-model="tenantKey" placeholder="组织 Key" @keyup.enter="samlLogin" />
      <el-button :loading="loading" style="width:100%;margin-top:12px" @click="samlLogin">使用 SAML SSO 登录</el-button>
    </el-card>
  </div>
</template>

<style scoped>
.login-page{min-height:100vh;display:grid;place-items:center;background:linear-gradient(135deg,#0f172a,#1d4ed8)}.login-card{width:400px}.login-card h1{margin:0 0 8px}.login-card p{color:#64748b;margin:0 0 24px}
</style>
