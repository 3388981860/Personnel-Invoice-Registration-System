import { reactive, computed } from 'vue'
import { api } from './api'

/** 全局会话 + 台账统计（跨页面共享） */
export const state = reactive({
  perms: [],
  permAll: {},
  ready: false,
  me: null,
  build: '',
  canOpenLocal: false,
  unread: 0,
  pendingReviews: 0,
  stats: null
})

export const perms = computed(() => state.perms || [])
export const permAll = computed(() => state.permAll || {})

/** 有没有某个功能权限（管理员永远有） */
export function can(key) {
  if (isAdmin.value) return true
  return (state.perms || []).includes(key)
}

export const isAdmin = computed(() => state.me?.role === 'admin')
export const isLoggedIn = computed(() => !!state.me)

export async function loadMe() {
  const r = await api.me()
  state.me = r.user
  state.build = r.build || ''
  state.perms = r.perms || []
  state.permAll = r.perm_all || {}
  state.canOpenLocal = !!r.can_open_local
  state.unread = r.unread || 0
  state.pendingReviews = r.pending_reviews || 0
  state.ready = true
  if (r.stats) state.stats = r.stats
  return r
}

export function applyStats(stats) {
  if (stats) state.stats = stats
}

export async function refreshBadges() {
  try {
    const r = await api.me()
    state.unread = r.unread || 0
    state.pendingReviews = r.pending_reviews || 0
  } catch (e) { /* 静默 */ }
}

export function clearSession() {
  state.me = null
  state.ready = false
}
