import { createRouter, createWebHistory } from 'vue-router'
import { loadMe, state, isAdmin } from './store'
import { setUnauthorizedHandler } from './api'

const routes = [
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('./layouts/AppLayout.vue'),
    children: [
      { path: '', redirect: '/entry' },
      { path: 'entry', name: 'entry', component: () => import('./views/EntryView.vue'), meta: { title: '新建报销单', group: '业务' } },
      { path: 'ledger', name: 'ledger', component: () => import('./views/LedgerView.vue'), meta: { title: '登记台账', group: '业务' } },
      { path: 'pending', name: 'pending', component: () => import('./views/LedgerView.vue'), meta: { title: '待审核', group: '业务', admin: true, review: 'submitted' } },
      { path: 'stats', name: 'stats', component: () => import('./views/StatsView.vue'), meta: { title: '数据总览', group: '管理', admin: true } },
      { path: 'users', name: 'users', component: () => import('./views/UsersView.vue'), meta: { title: '用户管理', group: '管理', admin: true } },
      { path: 'logs', name: 'logs', component: () => import('./views/LogsView.vue'), meta: { title: '操作日志', group: '管理', admin: true } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true

  if (!state.ready) {
    try {
      await loadMe()
    } catch (e) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  if (to.meta.admin && !isAdmin.value) {
    return { name: 'entry' }
  }
  return true
})

setUnauthorizedHandler(() => {
  state.ready = false
  state.me = null
  if (router.currentRoute.value.name !== 'login') {
    router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})

export default router
