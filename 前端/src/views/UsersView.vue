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
        <el-table-column label="最近登录" width="160">
          <template #default="{ row }">{{ row.last_login_at || '从未登录' }}</template>
        </el-table-column>
        <el-table-column label="功能权限" min-width="220">
          <template #default="{ row }">
            <el-tag v-if="row.role === 'admin'" size="small" type="success">全部</el-tag>
            <template v-else>
              <el-tag v-for="k in row.perms || []" :key="k" size="small"
                      style="margin:2px 4px 2px 0">{{ permName(k) }}</el-tag>
              <span v-if="!(row.perms || []).length" class="text-muted">无</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="钉钉" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.dingtalk_userid" size="small" type="success" effect="plain">
              已绑定
            </el-tag>
            <span v-else class="text-muted" style="font-size:12px">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="300" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDing(row)">钉钉</el-button>
            <el-button v-if="row.role !== 'admin'" link type="primary" size="small"
                       @click="openPerm(row)">权限</el-button>
            <el-button link type="primary" size="small" @click="rename(row)">改姓名</el-button>
            <el-button v-if="row.id !== state.me?.id" link type="primary" size="small" @click="toggleRole(row)">
              {{ row.role === 'admin' ? '设为用户' : '设为管理员' }}
            </el-button>
            <el-button v-if="row.id !== state.me?.id" link size="small" @click="toggleStatus(row)">
              {{ row.status ? '停用' : '启用' }}
            </el-button>
              <el-button v-else link type="primary" size="small" @click="openAccount">改密码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 钉钉绑定 -->
    <el-dialog v-model="dingDlg" title="钉钉账号" width="520px" append-to-body>
      <div style="margin-bottom:10px">
        给「<b>{{ dingUser?.display_name || dingUser?.username }}</b>」绑定钉钉账号，
        绑定后审核、公告等消息会推到他的钉钉。
      </div>

      <el-alert v-if="dingLoadError" type="warning" :closable="false" show-icon
                :title="dingLoadError" style="margin-bottom:12px" />

      <el-select v-model="dingPick" filterable clearable placeholder="搜索姓名或手机号"
                 style="width:100%" :loading="dingLoading">
        <el-option v-for="m in dingMembers" :key="m.userid"
                   :label="m.name + (m.mobile ? ' · ' + m.mobile : '')"
                   :value="m.userid" />
      </el-select>

      <div v-if="pickedMember" class="sync-row">
        <el-checkbox v-model="dingSyncName">
          把系统姓名改成「{{ pickedMember.name }}」
        </el-checkbox>
        <span class="text-muted" style="font-size:12px">
          （现在是「{{ dingUser?.display_name || dingUser?.username }}」）
        </span>
      </div>

      <div class="text-muted" style="font-size:12px;margin-top:10px">
        当前：{{ dingUser?.dingtalk_userid ? '已绑定 ' + dingUser.dingtalk_userid : '未绑定' }}
      </div>

      <template #footer>
        <el-button v-if="dingUser?.dingtalk_userid" @click="dingUnbind">解绑</el-button>
        <el-button @click="dingTest" :loading="dingTesting">发测试消息</el-button>
        <el-button @click="dingDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="dingSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 功能权限 -->
    <el-dialog v-model="permDlg" title="功能权限" width="520px" append-to-body>
      <div style="margin-bottom:12px">
        给「<b>{{ permUser?.display_name || permUser?.username }}</b>」开放这些功能：
      </div>
      <el-checkbox-group v-model="permChecked">
        <div v-for="(name, key) in state.permAll" :key="key" class="perm-row">
          <el-checkbox :value="key">{{ name }}</el-checkbox>
        </div>
      </el-checkbox-group>
      <div class="text-muted" style="font-size:12px;margin-top:10px">
        管理员不受这里限制，永远全部可用。没勾的功能，菜单里看不到，接口也会拒绝。
      </div>
      <template #footer>
        <el-button @click="permDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePerm">保存</el-button>
      </template>
    </el-dialog>

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
        <el-form-item label="功能权限（管理员不需要选）">
          <el-checkbox-group v-model="createForm.perms">
            <el-checkbox v-for="(name, key) in state.permAll" :key="key" :value="key"
                         style="margin-right:14px">{{ name }}</el-checkbox>
          </el-checkbox-group>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { state } from '../store'

const users = ref([])
const loading = ref(false)
const saving = ref(false)
const createVisible = ref(false)
const dingDlg = ref(false)
const dingUser = ref(null)
const dingPick = ref('')
const dingMembers = ref([])
const dingLoading = ref(false)
const dingLoadError = ref('')
const dingTesting = ref(false)
const dingSyncName = ref(true)

/** 下拉里选中的那个钉钉成员 */
const pickedMember = computed(() =>
  dingMembers.value.find(m => m.userid === dingPick.value) || null)

/** 打开钉钉绑定弹窗，顺便把通讯录拉一次 */
async function openDing(row) {
  dingUser.value = row
  dingPick.value = row.dingtalk_userid || ''
  dingSyncName.value = true
  dingDlg.value = true
  dingLoadError.value = ''
  dingMembers.value = []
  dingLoading.value = true
  try {
    const r = await api.dingtalkStatus()
    dingMembers.value = r.members || []
    if (r.directory_error) dingLoadError.value = r.directory_error
    else if (!r.configured) dingLoadError.value = '服务端还没配置钉钉密钥'
  } catch (e) {
    dingLoadError.value = e.message || '读取钉钉通讯录失败'
  } finally {
    dingLoading.value = false
  }
}

async function dingSave() {
  saving.value = true
  try {
    const m = pickedMember.value
    await api.dingtalkBind({
      user_id: dingUser.value.id,
      dingtalk_userid: dingPick.value,
      sync_name: !!m && dingSyncName.value,
      dingtalk_name: m ? m.name : ''
    })
    dingUser.value.dingtalk_userid = dingPick.value || null
    if (m && dingSyncName.value) dingUser.value.display_name = m.name
    ElMessage.success(dingPick.value ? '已绑定' : '已解绑')
    dingDlg.value = false
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function dingUnbind() {
  dingPick.value = ''
  await dingSave()
}

async function dingTest() {
  dingTesting.value = true
  try {
    const r = await api.dingtalkTest({ user_id: dingUser.value.id })
    if (r.ok) ElMessage.success('已发送，去钉钉看看')
    else ElMessage.error(r.error || '发送失败')
  } catch (e) {
    ElMessage.error(e.message || '发送失败')
  } finally {
    dingTesting.value = false
  }
}

const permDlg = ref(false)
const permUser = ref(null)
const permChecked = ref([])

function permName(key) {
  return state.permAll?.[key] || key
}

function openPerm(row) {
  permUser.value = row
  permChecked.value = [...(row.perms || [])]
  permDlg.value = true
}

async function savePerm() {
  saving.value = true
  try {
    await api.userUpdate({ id: permUser.value.id, perms: permChecked.value })
    ElMessage.success('已保存')
    permDlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const createForm = reactive({ username: '', display_name: '', password: '', role: 'user', perms: [] })

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

function openAccount() {
  ElMessage.info('请点右上角头像 → 账号设置')
}

onMounted(load)
</script>

<style scoped>
.sync-row {
  margin-top: 12px;
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
}
</style>
