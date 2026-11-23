<template>
  <div class="app-shell">
    <!-- 左侧导航 -->
    <aside class="sidebar">
      <div class="sb-logo"><span class="logo"><svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><path d="M12 2.6 21.4 7.4H2.6L12 2.6Z"/><rect x="4.6" y="9.1" width="2.5" height="8.2" rx=".5"/><rect x="10.75" y="9.1" width="2.5" height="8.2" rx=".5"/><rect x="16.9" y="9.1" width="2.5" height="8.2" rx=".5"/><rect x="2.9" y="18.4" width="18.2" height="2.5" rx=".7"/><rect x="9.9" y="20.9" width="4.2" height="1.4" rx=".4"/></svg></span><b>人数行政平台</b></div>

      <nav class="sb-nav">
        <template v-for="group in visibleGroups" :key="group.name">
          <div class="sb-group">{{ group.name }}</div>
          <div
            v-for="item in group.items"
            :key="item.name"
            class="sb-item"
            :class="{ on: route.name === item.name }"
            @click="go(item)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
            <span v-if="item.name === 'pending' && state.pendingReviews" class="badge">
              {{ state.pendingReviews > 99 ? '99+' : state.pendingReviews }}
            </span>
          </div>
        </template>

        <template v-if="state.canOpenLocal && isAdmin">
          <div class="sb-group">系统</div>
          <div class="sb-item" @click="openFolder('archive')">
            <el-icon><FolderOpened /></el-icon><span>发票存档</span>
          </div>
          <div class="sb-item" @click="openFolder('data')">
            <el-icon><Coin /></el-icon><span>数据目录</span>
          </div>
        </template>
      </nav>

      <div class="sb-foot">
        <span class="ver">构建 {{ state.build || '—' }}</span>
        <div class="skin-pick" title="切换配色">
          <button
            v-for="s in SKINS" :key="s.key"
            :class="{ on: skin === s.key }"
            :style="{ '--dot': s.dot }"
            :title="s.name"
            @click="applySkin(s.key)"
          />
        </div>
      </div>
    </aside>

    <!-- 右侧主区 -->
    <div class="main-area">
      <header class="topbar">
        <div class="crumb">{{ crumb }}</div>

        <!-- 全局信息：最新公告 + 时间 -->
        <div class="top-global">
          <div v-if="latestNotice" class="g-notice" @click="goNotice">
            <el-icon><Bell /></el-icon>
            <span class="g-label">公告</span>
            <span class="g-text ellipsis">{{ latestNotice.title }}</span>
          </div>
          <div class="g-clock">
            <span class="g-time">{{ clockText }}</span>
          </div>
        </div>

        <div class="top-actions">
          <el-badge :value="state.unread" :hidden="!state.unread" :max="99">
            <el-button :icon="Bell" circle @click="openNotify" />
          </el-badge>

          <el-dropdown trigger="click" @command="onUserCommand">
            <span class="user-chip">
              <el-avatar :size="24" class="avatar-brand" style="font-size:12px">{{ avatarText }}</el-avatar>
              <span class="ellipsis" style="max-width:90px">{{ state.me?.display_name || state.me?.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ state.me?.username }} · {{ isAdmin ? '管理员' : '普通用户' }}</el-dropdown-item>
                <el-dropdown-item command="account" divided :icon="Setting">账号设置</el-dropdown-item>
                <el-dropdown-item command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>

    <AccountDialog v-model="accountVisible" @saved="onAccountSaved" />
    <NotifyDrawer v-model="notifyVisible" @read="onNotifyRead" />
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  EditPen, Tickets, Clock, Files, DataAnalysis, User, Document,
  FolderOpened, Coin, Bell, ArrowDown, Setting, SwitchButton, Box
} from '@element-plus/icons-vue'

import { api } from '../api'
import { state, isAdmin, can, refreshBadges, applyStats } from '../store'
import { SKINS, skin, applySkin } from '../theme'
import AccountDialog from '../components/AccountDialog.vue'
import NotifyDrawer from '../components/NotifyDrawer.vue'

const route = useRoute()
const router = useRouter()
const accountVisible = ref(false)
const notifyVisible = ref(false)

const allGroups = [
  {
    name: '业务',
    items: [
      { name: 'entry', title: '新建报销单', icon: EditPen },
      { name: 'ledger', title: '发票登记', icon: Tickets },
      { name: 'pending', title: '待审核', icon: Clock, perm: 'review' },
      { name: 'contracts', title: '合同台账', icon: Files, perm: 'contracts' },
  { name: 'notice', title: '公告', icon: Bell },
  { name: 'tools', title: '工具箱', icon: Box }
    ]
  },
  {
    name: '管理',
    items: [
      { name: 'attendance', title: '考勤看板', icon: Clock, perm: 'attendance' },
      { name: 'stats', title: '数据总览', icon: DataAnalysis, perm: 'stats' },
      { name: 'users', title: '用户管理', icon: User, perm: 'users' },
      { name: 'dict', title: '数据字典', icon: Document },
      { name: 'logs', title: '操作日志', icon: Document, perm: 'logs' }
    ]
  }
]

/** 只留下有权限的菜单项；整组都空就不显示这一组 */
const visibleGroups = computed(() =>
  allGroups
    .map(g => ({
      ...g,
      items: g.items.filter(it => !it.perm || can(it.perm))
    }))
    .filter(g => g.items.length > 0)
)

const s = computed(() => state.stats || {})
/** 顶栏最新公告 */
const latestNotice = ref(null)

/** 顶栏时钟 */
const clockText = ref('')
let clockTimer = null

function tickClock() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  const wd = '日一二三四五六'[d.getDay()]
  clockText.value = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} 周${wd} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function loadLatestNotice() {
  try {
    const r = await api.announcements()
    const list = r.announcements || []
    latestNotice.value = list.length ? list[0] : null
  } catch (e) {
    latestNotice.value = null
  }
}

function goNotice() {
  router.push({ name: 'notice' })
}

const crumb = computed(() => {
  const m = route.meta || {}
  return m.title ? `${m.group} · ${m.title}` : ''
})
const avatarText = computed(() => {
  const n = state.me?.display_name || state.me?.username || '?'
  return n.slice(0, 1)
})

function money(v) {
  const n = Number(v || 0)
  return '¥' + n.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function go(item) {
  if (route.name === item.name) return
  router.push({ name: item.name })
}

async function openFolder(kind) {
  try {
    await api.ping()
    await fetch('/api/openfolder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ target: kind })
    })
  } catch (e) {
    ElMessage.error('打不开文件夹')
  }
}

function openNotify() {
  notifyVisible.value = true
}

onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
})

function onNotifyRead(unread) {
  state.unread = unread
}

async function onUserCommand(cmd) {
  if (cmd === 'account') {
    accountVisible.value = true
  } else if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm('确定退出登录吗？', '退出', { type: 'warning' })
    } catch (e) { return }
    await api.logout().catch(() => {})
    state.me = null
    state.ready = false
    router.push({ name: 'login' })
  }
}

function onAccountSaved() {
  ElMessage.success('已保存')
}

onMounted(async () => {
  tickClock()
  clockTimer = setInterval(tickClock, 30000)
  loadLatestNotice()
  try {
    const r = await api.list({ limit: 1 })
    applyStats(r.stats)
  } catch (e) { /* 忽略 */ }
  refreshBadges()
})
</script>

<style scoped>
.user-chip {
  display: flex; align-items: center; gap: 7px;
  height: 32px; padding: 0 10px 0 4px;
  border: 1px solid #E5E7EB; border-radius: 4px;
  cursor: pointer; font-size: 13px;
}
.user-chip:hover { border-color: var(--el-color-primary); }
</style>
