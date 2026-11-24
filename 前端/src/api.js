/**
 * 后端接口封装。
 * 目前指向既有的 Python 服务；以后换成 Spring Boot 只要接口契约不变即可。
 */

export class ApiError extends Error {
  constructor(message, { status = 0, field = '', payload = null } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.field = field
    this.payload = payload
  }
}

let onUnauthorized = null
/** 由 router 注册：会话失效时跳登录页 */
export function setUnauthorizedHandler(fn) { onUnauthorized = fn }

function buildUrl(path, query) {
  if (!query) return path
  const qs = new URLSearchParams()
  Object.entries(query).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    qs.append(k, v)
  })
  const s = qs.toString()
  return s ? `${path}?${s}` : path
}

async function request(path, { method = 'GET', body, query, raw = false, headers = {} } = {}) {
  const opts = {
    method,
    credentials: 'same-origin',
    headers: { ...headers }
  }
  if (body !== undefined && !raw) {
    opts.headers['Content-Type'] = 'application/json'
    opts.body = JSON.stringify(body)
  } else if (raw) {
    opts.body = body
  }

  let resp
  try {
    resp = await fetch(buildUrl(path, query), opts)
  } catch (e) {
    throw new ApiError('连不上服务器，请检查网络或服务是否在运行', { status: 0 })
  }

  if (resp.status === 401) {
    if (onUnauthorized) onUnauthorized()
    throw new ApiError('登录已过期，请重新登录', { status: 401 })
  }

  const ctype = resp.headers.get('content-type') || ''
  if (!ctype.includes('application/json')) {
    if (!resp.ok) throw new ApiError(`服务返回 ${resp.status}`, { status: resp.status })
    return resp
  }

  const data = await resp.json()
  if (!resp.ok || data.ok === false) {
    throw new ApiError(data.error || `请求失败（${resp.status}）`, {
      status: resp.status,
      field: data.field || '',
      payload: data
    })
  }
  return data
}

const get = (p, query) => request(p, { query })
const post = (p, body) => request(p, { method: 'POST', body })

export const api = {
  // ---- 会话 ----
  me: () => get('/api/me'),
  ping: () => get('/api/ping'),

  announcements: () => get('/api/announcements'),
  announcementSave: (body) => post('/api/announcements/save', body),
  announcementDelete: (body) => post('/api/announcements/delete', body),
  announcementAck: (body) => post('/api/announcements/ack', body),

  dingtalkLoginUrl: () => get('/api/dingtalk/login-url'),
  attendanceToday: (force) => get('/api/attendance/today' + (force ? '?force=true' : '')),
  attendanceRefresh: () => post('/api/attendance/refresh', {}),
  attendanceMonth: (m) => get('/api/attendance/month?month=' + m),
  attendanceSyncMonth: (body) => post('/api/attendance/sync-month', body),
  attendanceProgress: (m) => get('/api/attendance/progress?month=' + m),
  attendanceExportUrl: (m) => '/api/attendance/export?month=' + m,
  attendanceReportUrl: (m) => '/api/attendance/export-report?month=' + m,
  attendanceCalendar: (m) => get('/api/attendance/calendar?month=' + m),
  attendanceCalendarSet: (body) => post('/api/attendance/calendar/set', body),
  attendanceCalendarSync: (body) => post('/api/attendance/calendar/sync', body),
  attendanceRemind: () => post('/api/attendance/remind', {}),
  dict: (type) => get('/api/dict' + (type ? '?type=' + type : '')),
  dictSave: (body) => post('/api/dict/save', body),
  dictImport: (body) => post('/api/dict/import', body),
  dictDelete: (body) => post('/api/dict/delete', body),
  syncUserDept: () => post('/api/users/sync-dept', {}),
  userOrg: () => get('/api/users/org'),
  importDingUsers: () => post('/api/users/import-dingtalk', {}),

  attendanceExempt: () => get('/api/attendance/exempt'),
  attendanceExemptSet: (body) => post('/api/attendance/exempt/set', body),

  dingtalkStatus: () => get('/api/dingtalk/status'),
  dingtalkBind: (body) => post('/api/dingtalk/bind', body),
  dingtalkTest: (body) => post('/api/dingtalk/test', body),

  tools: () => get('/api/tools'),
  toolSave: (body) => post('/api/tools/save', body),
  toolDelete: (body) => post('/api/tools/delete', body),
  login: (username, password) => post('/api/login', { username, password }),
  register: (payload) => post('/api/register', payload),
  logout: () => post('/api/logout', {}),
  changePassword: (oldPwd, newPwd) => post('/api/password', { old: oldPwd, new: newPwd }),

  // ---- 台账 ----
  list: (query) => get('/api/list', query),
  parse: (file) => {
    // 文件名用 base64 放在头里，后端按扩展名判断 PDF/OFD
    const name = btoa(unescape(encodeURIComponent(file.name)))
    return request('/api/parse', {
      method: 'POST',
      raw: true,
      body: file,
      headers: {
        'Content-Type': 'application/octet-stream',
        'X-File-Name': name
      }
    })
  },
  add: (record) => post('/api/add', record),
  update: (record) => post('/api/update', record),
  setStatus: (id, status) => post('/api/status', { id, status }),
  remove: (id) => post('/api/delete', { id }),
  attach: (ids, batchId) => post('/api/attach', { ids, batch_id: batchId }),
  detach: (ids) => post('/api/detach', { ids }),

  // ---- 报销单 ----
  batches: (query) => get('/api/batches', query),
  batch: (id) => get('/api/batch', { id }),
  batchAdd: (payload) => post('/api/batch_add', payload),
  batchUpdate: (payload) => post('/api/batch_update', payload),
  batchStatus: (id, status) => post('/api/batch_status', { id, status }),
  batchReview: (payload) => post('/api/batch/review', payload),
  batchResubmit: (id) => post('/api/batch_resubmit', { id }),
  batchDelete: (id) => post('/api/batch_delete', { id }),

  // ---- 查验 ----
  auditStatus: () => get('/api/audit/status'),
  auditStart: (ids) => post('/api/audit/start', { ids }),
  auditStop: () => post('/api/audit/stop', {}),
  auditShotUrl: (id, t) => `/api/audit/shot?id=${id}&t=${encodeURIComponent(t || '')}`,

  // ---- 通知 ----
  notifications: () => get('/api/notifications'),
  readNotifications: (ids) => post('/api/notifications/read', { ids }),

  // ---- 统计 ----
  stats: () => get('/api/stats/overview'),

  // ---- 用户 / 日志 ----
  users: () => get('/api/users'),
  userCreate: (payload) => post('/api/users/create', payload),
  userUpdate: (payload) => post('/api/users/update', payload),
  logs: (query) => get('/api/logs', query),

  // ---- 导出 ----
  exportUrl: (query) => buildUrl('/api/export', query),

  // ---- 合同管理 ----
  projects: (query) => get('/api/projects', query),
  projectSets: () => get('/api/project_sets'),
  projectSetSave: (body) => post('/api/project_set/save', body),
  project: (id) => get('/api/project', { id }),
  saveProject: (payload) => post('/api/project/save', payload),
  deleteProject: (id) => post('/api/project/delete', { id }),
  saveContract: (payload) => post('/api/contract/save', payload),
  deleteContract: (id) => post('/api/contract/delete', { id }),
  contractPay: (payload) => post('/api/contract/pay', payload),
  contractsOverview: () => get('/api/contracts/overview'),
  contractsExportUrl: () => '/api/contracts/export',
  saveBilling: (payload) => post('/api/billing/save', payload),
  deleteBilling: (id) => post('/api/billing/delete', { id }),
  contractFileUrl: (id) => `/api/contract/file?id=${id}`,
  contractUpload: (id, formData) => {
    const url = buildUrl('/api/contract/upload', { id })
    return fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      body: formData
    }).then(async (resp) => {
      const data = await resp.json().catch(() => ({}))
      if (!resp.ok || data.ok === false) {
        throw new ApiError(data.error || '上传失败', resp.status)
      }
      return data
    })
  }
}
