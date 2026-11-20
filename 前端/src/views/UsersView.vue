<template>
  <div>
    <div class="panel">
      <div class="panel-hd">
        <span>用户管理</span>
        <span class="sub">共 {{ users.length }} 个账号</span>
        <el-button type="primary" size="small" style="margin-left:auto" @click="openCreate">+ 新建用户</el-button>
      </div>

      <el-table v-loading="loading" :data="users" size="small" style="width:100%">
        <el-table-column label="用户名" width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.username }}</span>
            <el-tag v-if="row.id === state.me?.id" size="small" style="margin-left:6px">我</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="display_name" label="姓名" width="150" />
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 'admin' ? 'primary' : 'info'">
              {{ row.role === 'admin' ? '管理员' : '用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status ? 'success' : 'danger'">{{ row.status ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" width="170">
          <template #default="{ row }">{{ row.last_login_at || '从未登录' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="260" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="rename(row)">改姓名</el-button>
            <el-button v-if="row.id !== state.me?.id" link type="primary" size="small" @click="toggleRole(row)">
              {{ row.role === 'admin' ? '设为用户' : '设为管理员' }}
            </el-button>
            <el-button v-if="row.id !== state.me?.id" link size="small" @click="toggleStatus(row)">
              {{ row.status ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="row.id !== state.me?.id" link type="warning" size="small" @click="resetPwd(row)">重置密码</el-button>
            <el-button v-else link type="primary" size="small" @click="openAccount">改密码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建 -->
    <el-dialog v-model="createVisible" title="新建用户" width="440px">
      <el-form :model="createForm" label-position="top">
        <el-form-item label="用户名" required>
          <el-input v-model="createForm.username" placeholder="字母开头，3-20 位" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="createForm.display_name" placeholder="留空则同用户名" />
        </el-form-item>
        <el-form-item label="初始密码">
          <el-input v-model="createForm.password" placeholder="留空自动生成" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="createForm.role">
            <el-radio value="user">普通用户</el-radio>
            <el-radio value="admin">管理员</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { state } from '../store'

const users = ref([])
const loading = ref(false)
const saving = ref(false)
const createVisible = ref(false)
const createForm = reactive({ username: '', display_name: '', password: '', role: 'user' })

async function load() {
  loading.value = true
  try {
    const r = await api.users()
    users.value = r.users || []
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(createForm, { username: '', display_name: '', password: '', role: 'user' })
  createVisible.value = true
}

async function doCreate() {
  if (!createForm.username.trim()) return ElMessage.warning('请填写用户名')
  saving.value = true
  try {
    const r = await api.userCreate({ ...createForm })
    ElMessage.success(r.password ? `已创建，初始密码：${r.password}` : '已创建')
    createVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    saving.value = false
  }
}

async function update(row, fields) {
  try {
    await api.userUpdate({ id: row.id, ...fields })
    Object.assign(row, fields)
    ElMessage.success('已更新')
  } catch (e) {
    ElMessage.error(e.message || '更新失败')
  }
}

async function rename(row) {
  let v
  try {
    const r = await ElMessageBox.prompt('新的姓名', '改姓名', {
      inputValue: row.display_name || '', confirmButtonText: '保存', cancelButtonText: '取消'
    })
    v = r.value
  } catch (e) { return }
  if (!v || !v.trim()) return
  update(row, { display_name: v.trim() })
}

function toggleRole(row) {
  update(row, { role: row.role === 'admin' ? 'user' : 'admin' })
}

function toggleStatus(row) {
  update(row, { status: row.status ? 0 : 1 })
}

async function resetPwd(row) {
  try {
    await ElMessageBox.confirm(
      `给「${row.display_name || row.username}」重置密码？系统会生成随机密码，旧密码立即失效。`,
      '重置密码', { type: 'warning' }
    )
  } catch (e) { return }
  try {
    const r = await api.userResetPassword(row.id)
    await ElMessageBox.alert(
      `新密码：${r.password}\n\n请立刻告诉对方，让对方登录后自行修改。`,
      '重置成功', { confirmButtonText: '我已记下' }
    )
  } catch (e) {
    ElMessage.error(e.message || '重置失败')
  }
}

function openAccount() {
  ElMessage.info('请点右上角头像 → 账号设置')
}

onMounted(load)
</script>
