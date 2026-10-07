import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { request } from '../api/http';
import { monitorApi } from '../api/monitor';

interface LoginResult {
  token: string;
  tokenHead: string;
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('monitor-token') || '');
  const loggedIn = computed(() => Boolean(token.value));

  async function login(username: string, password: string) {
    const data = await request<LoginResult>('/admin/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    }, false);
    token.value = data.token;
    localStorage.setItem('monitor-token', data.token);
    localStorage.setItem('monitor-token-head', data.tokenHead);
    localStorage.removeItem('monitor-sso-session');
  }

  async function exchangeSamlCode(code: string) {
    const data = await monitorApi.exchangeSamlLoginCode(code);
    token.value = data.token;
    localStorage.setItem('monitor-token', data.token);
    localStorage.setItem('monitor-token-head', data.tokenHead);
    localStorage.setItem('monitor-sso-session', 'true');
  }

  function logout() {
    const shouldUseSamlLogout = localStorage.getItem('monitor-sso-session') === 'true';
    token.value = '';
    localStorage.removeItem('monitor-token');
    localStorage.removeItem('monitor-token-head');
    localStorage.removeItem('monitor-sso-session');
    if (shouldUseSamlLogout) {
      const form = document.createElement('form');
      form.method = 'post';
      form.action = '/logout';
      document.body.appendChild(form);
      form.submit();
    }
  }

  return { token, loggedIn, login, exchangeSamlCode, logout };
});
