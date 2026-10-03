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
        { path: 'organization', component: () => import('../views/Organization.vue') },
        { path: 'data-governance', component: () => import('../views/DataGovernance.vue') },
        { path: 'performance', component: () => import('../views/Performance.vue') },
        { path: 'metrics', component: () => import('../views/Metrics.vue') },
        { path: 'profiles', component: () => import('../views/Profiles.vue') },
        { path: 'explore', component: () => import('../views/Explore.vue') },
        { path: 'apis', component: () => import('../views/Apis.vue') },
        { path: 'issues', component: () => import('../views/Issues.vue') },
        { path: 'issues/:id', component: () => import('../views/IssueDetail.vue') },
        { path: 'logs', component: () => import('../views/Logs.vue') },
        { path: 'traces', component: () => import('../views/Traces.vue') },
        { path: 'releases', component: () => import('../views/Releases.vue') },
        { path: 'replays', component: () => import('../views/Replays.vue') },
        { path: 'alerts', component: () => import('../views/Alerts.vue') },
        { path: 'crons', component: () => import('../views/Crons.vue') },
        { path: 'uptime', component: () => import('../views/Uptime.vue') }
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
