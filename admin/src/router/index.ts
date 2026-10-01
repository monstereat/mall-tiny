import { createRouter, createWebHistory } from 'vue-router';
import Login from '../views/Login.vue';
import MainLayout from '../layouts/MainLayout.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('../views/Dashboard.vue') },
        { path: 'projects', component: () => import('../views/Projects.vue') },
        { path: 'performance', component: () => import('../views/Performance.vue') },
        { path: 'apis', component: () => import('../views/Apis.vue') },
        { path: 'issues', component: () => import('../views/Issues.vue') },
        { path: 'issues/:id', component: () => import('../views/IssueDetail.vue') },
        { path: 'releases', component: () => import('../views/Releases.vue') },
        { path: 'replays', component: () => import('../views/Replays.vue') },
        { path: 'alerts', component: () => import('../views/Alerts.vue') }
      ]
    }
  ]
});

router.beforeEach(to => {
  const token = localStorage.getItem('monitor-token');
  if (to.path !== '/login' && !token) return '/login';
  if (to.path === '/login' && token) return '/dashboard';
});

export default router;