<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { monitorApi, type ProjectCredentials } from '../api/monitor';
import { useProjectStore } from '../stores/project';

const projects = useProjectStore();
const dialog = ref(false);
const credentials = ref<ProjectCredentials | null>(null);
const form = reactive({ name: '', projectKey: '', platform: 'web' });

async function createProject() {
  try {
    credentials.value = await monitorApi.createProject({ ...form });
    ElMessage.success('项目创建成功，请立即保存密钥');
    dialog.value = false;
    await projects.load();
    projects.setCurrent(credentials.value.project.projectKey);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  }
}

async function rotate(projectKey: string) {
  try {
    await ElMessageBox.confirm('轮换后旧 Ingest/Release Key 将立即失效，确认继续？', '轮换密钥');
    credentials.value = await monitorApi.rotateProjectKeys(projectKey);
    ElMessage.success('密钥已轮换，请立即保存新密钥');
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  }
}
</script>

<template>
  <section>
    <div class="toolbar">
      <h1 class="page-title" style="margin:0">Projects</h1>
      <el-button type="primary" @click="dialog=true">创建项目</el-button>
    </div>

    <div v-if="credentials" class="panel">
      <el-alert type="warning" :closable="false" title="密钥只在创建/轮换时展示，请立即保存。" />
      <el-descriptions :column="1" border style="margin-top:14px">
        <el-descriptions-item label="Project Key">{{ credentials.project.projectKey }}</el-descriptions-item>
        <el-descriptions-item label="Ingest Key"><code>{{ credentials.ingestKey }}</code></el-descriptions-item>
        <el-descriptions-item label="Release Key"><code>{{ credentials.releaseKey }}</code></el-descriptions-item>
      </el-descriptions>
      <el-button style="margin-top:12px" @click="credentials=null">我已保存</el-button>
    </div>

    <div class="panel">
      <el-table :data="projects.projects">
        <el-table-column prop="name" label="项目" />
        <el-table-column prop="projectKey" label="Project Key" min-width="180" />
        <el-table-column prop="platform" label="Platform" width="120" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button link type="primary" @click="projects.setCurrent(row.projectKey)">进入</el-button>
            <el-button link type="danger" @click="rotate(row.projectKey)">轮换密钥</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="创建监控项目" width="520px">
      <el-form label-width="100px">
        <el-form-item label="项目名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="Project Key"><el-input v-model="form.projectKey" placeholder="例如 dcrm-web" /></el-form-item>
        <el-form-item label="Platform">
          <el-select v-model="form.platform" style="width:100%">
            <el-option label="Web" value="web" />
            <el-option label="H5" value="h5" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="createProject">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>
