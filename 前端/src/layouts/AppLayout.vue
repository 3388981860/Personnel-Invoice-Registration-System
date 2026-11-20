<template>
  <div class="app-shell">
    <!-- 左侧导航 -->
    <aside class="sidebar">
      <div class="sb-logo"><span class="logo">票</span><b>发票台账</b></div>

      <nav class="sb-nav">
        <template v-for="group in groups" :key="group.name">
          <div v-if="group.name === '管理' && !isAdmin" style="display:none" />
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

        <div class="top-stats">
          <div class="top-stat"><b>{{ s.unpaid_batches || 0 }}</b><span class="u">单</span>待报销</div>
          <div class="top-stat"><b>{{ s.unpaid?.count || 0 }}</b><span class="u">张</span>{{ money(s.unpaid?.amount) }}</div>
          <div class="top-stat"><b>{{ s.month?.count || 0 }}</b><span class="u">张</span>本月</div>
          <div class="top-stat"><b>{{ s.year?.count || 0 }}</b><span class="u">张</span>本年</div>
          <div class="top-stat"><b>{{ s.total?.count || 0 }}</b><span class="u">张</span>累计</div>
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
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  EditPen, Tickets, Clock, DataAnalysis, User, Document,
  FolderOpened, Coin, Bell, ArrowDown, Setting, SwitchButton
} from '@element-plus/icons-vue'

import { api } from '../api'
import { state, isAdmin, refreshBadges, applyStats } from '../store'
import { SKINS, skin, applySkin } from '../theme'
import AccountDialog from '../components/AccountDialog.vue'
import NotifyDrawer from '../components/NotifyDrawer.vue'

const route = useRoute()
const router = useRouter()
const accountVisible = ref(false)
const notifyVisible = ref(false)

const groups = [
  {
    name: '业务',
    items: [
      { name: 'entry', title: '新建报销单', icon: EditPen },
      { name: 'ledger', title: '登记台账', icon: Tickets },
      { name: 'pending', title: '待审核', icon: Clock }
    ]
  },
  {
    name: '管理',
    items: [
      { name: 'stats', title: '数据总览', icon: DataAnalysis },
      { name: 'users', title: '用户管理', icon: User },
      { name: 'logs', title: '操作日志', icon: Document }
    ]
  }
]

const s = computed(() => state.stats || {})
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
