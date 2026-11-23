<template>
  <div>
    <el-row :gutter="14">
      <el-col :span="12">
        <div class="panel">
          <div class="panel-hd">
            <span>报销部门</span>
            <span class="sub">共 {{ depts.length }} 个</span>
            <div style="margin-left:auto;display:flex;gap:8px">
              <el-button size="small" :loading="importing" @click="importFrom('dingtalk')">
                从钉钉导入
              </el-button>
              <el-button type="primary" size="small" @click="openAdd('dept')">+ 添加</el-button>
            </div>
          </div>
          <div class="dict-list">
            <div v-for="d in depts" :key="d.id" class="dict-item">
              <span class="dict-name">{{ d.name }}</span>
              <span class="dict-ops">
                <el-button link size="small" @click="rename(d)">改名</el-button>
                <el-button link type="danger" size="small" @click="remove(d)">删除</el-button>
              </span>
            </div>
            <div v-if="!depts.length" class="dict-empty">还没有部门，点「从钉钉导入」</div>
          </div>
        </div>
      </el-col>

      <el-col :span="12">
        <div class="panel">
          <div class="panel-hd">
            <span>报销项目</span>
            <span class="sub">共 {{ projects.length }} 个</span>
            <div style="margin-left:auto;display:flex;gap:8px">
              <el-button size="small" :loading="importing" @click="importFrom('project')">
                从合同项目导入
              </el-button>
              <el-button type="primary" size="small" @click="openAdd('project')">+ 添加</el-button>
            </div>
          </div>
          <div class="dict-list">
            <div v-for="p in projects" :key="p.id" class="dict-item">
              <span class="dict-name">{{ p.name }}</span>
              <span class="dict-ops">
                <el-button link size="small" @click="rename(p)">改名</el-button>
                <el-button link type="danger" size="small" @click="remove(p)">删除</el-button>
              </span>
            </div>
            <div v-if="!projects.length" class="dict-empty">还没有项目，点「从合同项目导入」</div>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const depts = ref([])
const projects = ref([])
const importing = ref(false)

async function load() {
  try {
    const r = await api.dict()
    const items = r.items || []
    depts.value = items.filter(x => x.type === 'dept')
    projects.value = items.filter(x => x.type === 'project')
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  }
}

async function importFrom(source) {
  importing.value = true
  try {
    const r = await api.dictImport({ source })
    ElMessage.success(`扫描 ${r.scanned} 条，新增 ${r.added} 条`)
    await load()
  } catch (e) {
    ElMessage.error(e.message || '导入失败')
  } finally {
    importing.value = false
  }
}

async function openAdd(type) {
  try {
    const { value } = await ElMessageBox.prompt('名称', '添加', {
      inputPattern: /\S+/,
      inputErrorMessage: '不能为空'
    })
    await api.dictSave({ type, name: value.trim() })
    await load()
  } catch (e) {
    // 取消
  }
}

async function rename(item) {
  try {
    const { value } = await ElMessageBox.prompt('新名称', '改名', {
      inputValue: item.name,
      inputPattern: /\S+/,
      inputErrorMessage: '不能为空'
    })
    await api.dictSave({ id: item.id, type: item.type, name: value.trim() })
    await load()
  } catch (e) {
    // 取消
  }
}

async function remove(item) {
  try {
    await ElMessageBox.confirm(`删除「${item.name}」？不影响已提交的单据。`, '删除', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await api.dictDelete({ id: item.id })
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.dict-list { max-height: calc(100vh - 260px); overflow-y: auto; padding: 6px 16px 14px; }
.dict-item {
  display: flex; align-items: center;
  padding: 8px 2px; border-bottom: 1px solid #F2F4F7;
}
.dict-item:last-child { border-bottom: none; }
.dict-name { font-size: 13px; color: var(--brand-text); }
.dict-ops { margin-left: auto; }
.dict-empty { text-align: center; color: #9AA6B6; padding: 30px 0; font-size: 12.5px; }
</style>
