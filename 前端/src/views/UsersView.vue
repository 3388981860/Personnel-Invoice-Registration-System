<template>
  <div>
    <div class="org-wrap">
      <div class="panel org-tree">
        <div class="panel-hd"><span>组织架构</span></div>
        <div class="tree-box">
          <el-tree
            :data="treeData"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            highlight-current
            default-expand-all
            :expand-on-click-node="false"
            @node-click="onNodeClick"
          >
            <template #default="{ data }">
              <span class="tree-node">
                <span class="tree-name">{{ data.name }}</span>
                <span v-if="data.count" class="tree-count">{{ data.count }}</span>
              </span>
            </template>
          </el-tree>
        </div>
      </div>

      <div class="panel org-main">
      <div class="panel-hd">
        <span>{{ currentDeptName }}人员</span>
        <span class="sub">共 {{ shownUsers.length }} 个账号</span>
        <el-input
          v-model="kw" size="small" clearable
          placeholder="搜姓名 / 用户名 / 部门"
          style="width:220px;margin-left:auto"
          :prefix-icon="Search"
        />
        <el-button size="small" :loading="syncing" @click="syncDept">
          同步部门
        </el-button>
        <el-button type="primary" size="small" @click="openCreate">+ 新建用户</el-button>
      </div>

      <div class="table-scroll">
        <el-table :key="colKey" v-loading="loading" :data="shownUsers"
                  size="small" style="width:100%">
        <el-table-column label="用户名" :width="cw.user">
          <template #default="{ row }">
            <span class="mono">{{ row.username }}</span>
            <el-tag v-if="row.id === state.me?.id" size="small" style="margin-left:6px">我</el-tag>
            <span v-if="row.dingtalk_userid" class="binding-dot" title="已绑定钉钉">钉</span>
          </template>
        </el-table-column>
        <el-table-column prop="display_name" label="姓名" :width="cw.name" />
        <el-table-column label="角色" :width="cw.role">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 'admin' ? 'primary' : 'info'">
              {{ row.role === 'admin' ? '管理员' : '用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" :width="cw.status">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status ? 'success' : 'danger'">{{ row.status ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        
        <el-table-column v-if="showPermCol" label="功能权限" :width="cw.perm">
          <template #default="{ row }">
            <el-tag v-if="row.role === 'admin'" size="small" type="success">全部</el-tag>
            <el-tooltip v-else-if="(row.perms || []).length" placement="top">
              <template #content>
                <div style="max-width:240px;line-height:1.7">
                  {{ (row.perms || []).map(permName).join('、') }}
                </div>
              </template>
              <el-tag size="small" effect="plain">{{ (row.perms || []).length }} 项</el-tag>
            </el-tooltip>
            <span v-else class="text-muted" style="font-size:12px">无</span>
          </template>
        </el-table-column>
        <el-table-column v-if="showLoginCol" label="最近登录" :width="cw.login">
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.last_login_at }">
              {{ row.last_login_at || '从未登录' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="showDeptCol" prop="dept_name" label="部门" :min-width="cw.dept" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.dept_name }">{{ row.dept_name || '—' }}</span>
          </template>
        </el-table-column>
        
        <el-table-column label="操作" :width="cw.op" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDing(row)">钉钉</el-button>
              <el-button v-if="row.role !== 'admin'" link type="primary" size="small"
                         @click="openPerm(row)">权限</el-button>
              <el-button link type="primary" size="small" @click="rename(row)">改姓名</el-button>
              <template v-if="row.id !== state.me?.id">
                <el-button link type="primary" size="small" @click="toggleRole(row)">
                  {{ row.role === 'admin' ? '设为用户' : '设为管理员' }}
                </el-button>
                <el-button link size="small" @click="toggleStatus(row)">
                  {{ row.status ? '停用' : '启用' }}
                </el-button>
              </template>
              <el-button v-else link type="primary" size="small" @click="openAccount">改密码</el-button>
            </template>
          </el-table-column>
      </el-table>
        </div>
      </div>
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
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { Search, ArrowDown } from '@element-plus/icons-vue'
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

const syncing = ref(false)
const treeData = ref([])
const currentDeptId = ref('all')
const currentDeptName = ref('全部')

async function loadOrg() {
  try {
    const r = await api.userOrg()
    const all = { id: 'all', name: '全部人员', count: r.total || 0, children: [] }
    const noDept = { id: 'none', name: '未分配部门', count: r.no_dept || 0, children: [] }
    treeData.value = [all, ...(r.tree || []), noDept]
  } catch (e) {
    treeData.value = [{ id: 'all', name: '全部人员', count: 0, children: [] }]
  }
}

function onNodeClick(data) {
  currentDeptId.value = data.id
  currentDeptName.value = data.name
}

function collectIds(node) {
  const ids = []
  const walk = n => {
    if (typeof n.id === 'number') ids.push(n.id)
    ;(n.children || []).forEach(walk)
  }
  walk(node)
  return ids
}

function findNode(nodes, id) {
  for (const n of nodes) {
    if (n.id === id) return n
    const hit = findNode(n.children || [], id)
    if (hit) return hit
  }
  return null
}

function inCurrentDept(u) {
  if (currentDeptId.value === 'all') return true
  const ids = String(u.dept_ids || '')
  if (currentDeptId.value === 'none') return !ids.trim()
  const node = findNode(treeData.value, currentDeptId.value)
  if (!node) return true
  const want = collectIds(node)
  const mine = ids.split(',').map(s => Number(s.trim())).filter(Boolean)
  return mine.some(x => want.includes(x))
}

const kw = ref('')

/** 窗口宽度：窄了就把次要列收起来 */
const vw = ref(typeof window === 'undefined' ? 1600 : window.innerWidth)
/** 按窗口分四档：窄了自动收列、缩列宽 */
const tier = computed(() => {
  const w = vw.value
  if (w >= 1750) return 'xl'
  if (w >= 1500) return 'lg'
  if (w >= 1300) return 'md'
  return 'sm'
})

const COL_W = {
  xl: { user: 150, name: 110, role: 86, status: 76, perm: 120, login: 158, dept: 230, op: 300 },
  lg: { user: 130, name: 95, role: 76, status: 66, perm: 110, login: 0, dept: 200, op: 280 },
  md: { user: 120, name: 80, role: 66, status: 60, perm: 0, login: 0, dept: 170, op: 260 },
  sm: { user: 110, name: 70, role: 58, status: 56, perm: 0, login: 0, dept: 0, op: 250 }
}
const cw = computed(() => COL_W[tier.value])

const showLoginCol = computed(() => tier.value === 'xl')
const showPermCol = computed(() => ['xl', 'lg'].includes(tier.value))
const showDeptCol = computed(() => tier.value !== 'sm')
const colKey = computed(() => tier.value)

function onResize() {
  vw.value = window.innerWidth
}

/** 当前部门 + 关键词一起过滤 */
const shownUsers = computed(() => {
  const k = kw.value.trim().toLowerCase()
  return users.value.filter(u => {
    if (!inCurrentDept(u)) return false
    if (!k) return true
    return String(u.display_name || '').toLowerCase().includes(k)
      || String(u.username || '').toLowerCase().includes(k)
      || String(u.dept_name || '').toLowerCase().includes(k)
  })
})

/** 从钉钉通讯录把每个人的部门拉过来 */
/** 「更多」菜单的命令分发 */
function onUserCmd(cmd, row) {
  if (cmd === 'rename') return rename(row)
  if (cmd === 'role') return toggleRole(row)
  if (cmd === 'status') return toggleStatus(row)
  if (cmd === 'password') return openAccount()
}

async function syncDept() {
  syncing.value = true
  try {
    const r = await api.syncUserDept()
    ElMessage.success(`已更新 ${r.updated} 个账号的部门`)
    await load()
    await loadOrg()
  } catch (e) {
    ElMessage.error(e.message || '同步失败')
  } finally {
    syncing.value = false
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

onMounted(async () => {
  window.addEventListener('resize', onResize)
  await loadOrg()
  await load()
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.table-scroll { overflow-x: auto; }
.table-scroll :deep(.el-table__body-wrapper) { overflow-x: auto; }
.binding-dot {
  display: inline-block; margin-left: 5px;
  font-size: 10px; color: #fff; background: #1B9AAA;
  border-radius: 3px; padding: 0 3px; line-height: 14px; vertical-align: middle;
}
.org-wrap { display: flex; gap: 14px; align-items: flex-start; }
.org-tree { width: 260px; flex: none; }
.org-main { flex: 1; min-width: 0; }
.tree-box { padding: 8px 6px 14px; max-height: calc(100vh - 220px); overflow: auto; }
.tree-node { display: flex; align-items: center; gap: 6px; width: 100%; }
.tree-name { font-size: 13px; }
.tree-count {
  margin-left: auto; font-size: 11px; color: #6B7280;
  background: #F1F3F5; border-radius: 9px; padding: 0 6px; line-height: 16px;
}
:deep(.el-tree-node__content) { height: 30px; }
.sync-row {
  margin-top: 12px;
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
}
</style>
