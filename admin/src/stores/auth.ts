import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { request } from '../api/http';

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
  }

  function logout() {
    token.value = '';
    localStorage.removeItem('monitor-token');
    localStorage.removeItem('monitor-token-head');
  }

  return { token, loggedIn, login, logout };
});
