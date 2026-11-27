import { createRouter, createWebHistory } from 'vue-router'
import { loadMe, state, isAdmin, can } from './store'
import { setUnauthorizedHandler } from './api'

const routes = [
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
  { path: '/batch-print/:id', name: 'batch-print',
    component: () => import('./views/BatchPrintView.vue'),
    meta: { title: '报销单打印' } },
  { path: '/invoice-print/:id', name: 'invoice-print',
    component: () => import('./views/InvoicePrintView.vue'),
    meta: { title: '开票申请单打印' } },
  {
    path: '/',
    component: () => import('./layouts/AppLayout.vue'),
    children: [
      { path: '', redirect: '/entry' },
      { path: 'entry', name: 'entry', component: () => import('./views/EntryView.vue'), meta: { title: '新建报销单', group: '业务' } },
      { path: 'ledger', name: 'ledger', component: () => import('./views/LedgerView.vue'), meta: { title: '发票登记', group: '业务' } },
      { path: 'pending', name: 'pending', component: () => import('./views/LedgerView.vue'), meta: { title: '待审核', group: '业务', perm: 'review', review: 'submitted' } },
        { path: 'notice', name: 'notice', component: () => import('./views/NoticeView.vue'), meta: { title: '公告', group: '业务' } },
  { path: 'tools', name: 'tools', component: () => import('./views/ToolsView.vue'), meta: { title: '工具箱', group: '业务' } },
   { path: 'tools/watermark', name: 'watermark', component: () => import('./views/WatermarkView.vue'), meta: { title: '图片水印', group: '业务', perm: 'tools' } },
  { path: 'invoice-requests', name: 'invoice-requests', component: () => import('./views/InvoiceRequestsView.vue'), meta: { title: '开票申请', group: '业务', perm: 'contracts' } },
  { path: 'seal', name: 'seal', component: () => import('./views/SealView.vue'), meta: { title: '用印申请', group: '业务' } },
  { path: 'contracts', name: 'contracts', component: () => import('./views/ContractsView.vue'), meta: { title: '合同台账', group: '业务', perm: 'contracts' } },
      { path: 'attendance', name: 'attendance', component: () => import('./views/AttendanceView.vue'), meta: { title: '考勤看板', group: '管理', perm: 'attendance' } },
  { path: 'assets', name: 'assets', component: () => import('./views/AssetsView.vue'), meta: { title: '固定资产', group: '管理', perm: 'assets' } },
  { path: 'stats', name: 'stats', component: () => import('./views/StatsView.vue'), meta: { title: '数据总览', group: '管理', perm: 'stats' } },
      { path: 'users', name: 'users', component: () => import('./views/UsersView.vue'), meta: { title: '用户管理', group: '管理', perm: 'users' } },
      { path: 'dict', name: 'dict', component: () => import('./views/DictView.vue'), meta: { title: '数据字典', group: '管理', perm: 'dict' } },
  { path: 'logs', name: 'logs', component: () => import('./views/LogsView.vue'), meta: { title: '操作日志', group: '管理', perm: 'logs' } }
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

  if (to.meta.perm && !can(to.meta.perm)) {
    return { name: 'entry' }
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
