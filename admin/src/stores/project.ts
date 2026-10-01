import { defineStore } from 'pinia';
import { ref } from 'vue';
import { monitorApi, type MonitorProject } from '../api/monitor';

export const useProjectStore = defineStore('project', () => {
  const projects = ref<MonitorProject[]>([]);
  const currentKey = ref(localStorage.getItem('monitor-project-key') || '');

  async function load() {
    projects.value = await monitorApi.projects();
    if (!currentKey.value && projects.value.length) {
      setCurrent(projects.value[0].projectKey);
    }
    if (currentKey.value && !projects.value.some(item => item.projectKey === currentKey.value)) {
      setCurrent(projects.value[0]?.projectKey || '');
    }
  }

  function setCurrent(key: string) {
    currentKey.value = key;
    localStorage.setItem('monitor-project-key', key);
  }

  return { projects, currentKey, load, setCurrent };
});
