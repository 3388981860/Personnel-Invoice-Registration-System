<template>
  <div class="fill-page">
    <div class="panel">
      <div class="panel-hd">
        <span>{{ isPendingView ? '待审核' : '发票登记' }}</span>
        <span class="sub">{{ auditSummary }}</span>

        <!-- 发票统计（原来在顶栏，系统不止发票了，挪到这里） -->
        <div class="ledger-stats">
          <div class="ls-item">
            <b>{{ stats?.unpaid_batches || 0 }}</b><span class="u">单</span><span class="l">待报销</span>
          </div>
          <div class="ls-item">
            <b>{{ stats?.unpaid?.count || 0 }}</b><span class="u">张</span>
            <span class="l">{{ money(stats?.unpaid?.amount) }} 未报销</span>
          </div>
          <div class="ls-item"><b>{{ stats?.month?.count || 0 }}</b><span class="u">张</span><span class="l">本月</span></div>
          <div class="ls-item"><b>{{ stats?.year?.count || 0 }}</b><span class="u">张</span><span class="l">本年</span></div>
          <div class="ls-item"><b>{{ stats?.total?.count || 0 }}</b><span class="u">张</span><span class="l">累计</span></div>
        </div>
      </div>

      <!-- 筛选 -->
      <div style="display:flex;flex-wrap:wrap;gap:10px;align-items:center;padding:14px 16px;border-bottom:1px solid #E5E7EB">
        <el-input
          v-model="filters.q" placeholder="搜索 发票号码 / 单号 / 销售方 / 报销人 / 收款人"
          clearable style="width:240px" @keyup.enter="reload" @clear="reload"
        />
        <el-select v-model="filters.person" placeholder="全部报销人" clearable style="width:118px" @change="reload">
          <el-option v-for="p in persons" :key="p" :label="p" :value="p" />
        </el-select>
        <el-select v-model="filters.month" placeholder="全部月份" clearable style="width:118px" @change="reload">
          <el-option v-for="m in months" :key="m" :label="m" :value="m" />
        </el-select>
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width:120px" @change="reload">
          <el-option label="未报销" value="0" />
          <el-option label="已报销" value="1" />
        </el-select>
        <el-select v-model="filters.audit" placeholder="全部查验" clearable style="width:118px" @change="reload">
          <el-option label="未查验" value="0" />
          <el-option label="查验通过" value="1" />
          <el-option label="查验不通过" value="2" />
          <el-option label="无需查验" value="3" />
        </el-select>
        <el-select v-model="filters.batch" placeholder="全部报销单" clearable style="width:140px" @change="reload">
          <el-option label="未归单" value="none" />
          <el-option v-for="b in batchOptions" :key="b.id" :label="b.batch_no" :value="String(b.id)" />
        </el-select>
        <el-select v-if="can('review')" v-model="filters.review" placeholder="全部审核" clearable style="width:120px" @change="reload">
          <el-option label="待审核" value="submitted" />
          <el-option label="已退回" value="returned" />
          <el-option label="已通过" value="approved" />
        </el-select>
        <el-button type="primary" @click="reload">查询</el-button>
        <el-button v-if="can('audit')" type="primary" :loading="auditChecking" @click="auditClick">
          查验
        </el-button>
        <el-button type="primary" plain @click="exportExcel">导出</el-button>
        <span class="text-muted" style="margin-left:auto;font-size:12.5px">
          共 {{ rows.length }} 条　合计 {{ money(stats?.total?.amount) }}　
          其中未报销 {{ money(stats?.unpaid?.amount) }}
        </span>
      </div>

      <!-- 批量操作 -->
      <div v-if="selection.length" class="selbar">
        <span>
          已选 <b>{{ selection.length }}</b> 张
          <span v-if="selectedOnOtherPages > 0" class="other-page">
            （其中 {{ selectedOnOtherPages }} 张在其它页）
          </span>
        </span>
        <el-button v-if="can('audit')" size="small" @click="startAudit">查验选中</el-button>
        <el-button size="small" @click="attachDialog = true">加入报销单</el-button>
        <el-button size="small" @click="detachSelected">从单里拆出</el-button>
        <el-button size="small" link @click="clearSelection">取消选择</el-button>
      </div>

      <!-- 表格 -->
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="pageRows"
        row-key="id"
        border
        size="small"
        style="width:100%"
        @selection-change="onSelect"
        @row-click="onRowClick"
        max-height="calc(100vh - 258px)"
      >
        <el-table-column type="selection" width="52" align="center" reserve-selection />
        <el-table-column label="报销单号" width="176">
          <template #default="{ row }">
            <el-link v-if="row.batch_id" type="primary" @click.stop="openBatch(row.batch_id)">
              <span class="mono" style="font-size:12.5px">{{ row.batch_no }}</span>
            </el-link>
            <span v-else class="text-muted">—</span>
            <el-tag v-if="row.batch_review === 'submitted'" size="small" type="warning" style="margin-left:4px">待审</el-tag>
            <el-tag v-if="row.batch_review === 'returned'" size="small" type="danger" style="margin-left:4px">退回</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="invoice_no" label="发票号码" width="174">
          <template #default="{ row }"><span class="mono">{{ row.invoice_no }}</span></template>
        </el-table-column>
        <el-table-column prop="invoice_date" label="开票日期" width="104" />
        <el-table-column label="金额（含税）" width="116" align="right">
          <template #default="{ row }"><span class="money">{{ money(row.amount) }}</span></template>
        </el-table-column>
        <el-table-column prop="person" label="报销人" width="92" />
        <el-table-column label="收款人" width="128">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.payee_names"
              :content="payeeTip(row)"
              placement="top"
              :show-after="200"
            >
              <span class="payee-cell">{{ payeeLabel(row) }}</span>
            </el-tooltip>
            <el-tooltip v-else-if="row.payee" :content="row.payee" placement="top" :show-after="200">
              <span class="payee-cell" :class="{ other: row.person && row.payee !== row.person }">
                {{ row.payee }}
              </span>
            </el-tooltip>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="报销状态" width="110" align="center">
          <template #default="{ row }">
            <el-switch
              v-if="can('review')"
              :model-value="Number(row.status) === 1"
              @update:model-value="(v) => setStatus(row, v)"
            />
            <el-tag v-else size="small" :type="Number(row.status) === 1 ? 'success' : 'danger'">
              {{ Number(row.status) === 1 ? '已报销' : '未报销' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="查验" width="104">
          <template #default="{ row }">
            <el-tag size="small" :type="auditType(row.audit_status)">{{ auditText(row.audit_status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="80" show-overflow-tooltip />
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="openInvoice(row.id)">详情</el-button>
            <el-button v-if="canDeleteRow(row)" link type="danger" size="small" @click.stop="removeRow(row)">删除</el-button>
            <el-tooltip v-else-if="!canEditRow(row)" :content="lockReason(row)" placement="top">
              <el-icon class="lock-ico"><Lock /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <span class="text-muted" style="font-size:13px">第 {{ page }} / {{ pageCount }} 页</span>
        <div style="flex:1" />
        <el-select v-model="pageSize" style="width:96px" @change="page = 1">
          <el-option :value="20" label="每页 20" />
          <el-option :value="50" label="每页 50" />
          <el-option :value="100" label="每页 100" />
        </el-select>
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="rows.length"
          layout="prev, pager, next"
          background
        />
      </div>
    </div>

    <DetailDrawer
      v-model="drawerVisible"
      :type="drawerType"
      :id="drawerId"
      @changed="reload"
      @open-batch="openBatch"
    />

    <!-- 加入报销单 -->
    <el-dialog v-model="attachDialog" title="加入已有报销单" width="480px">
      <el-select v-model="attachBatch" placeholder="选择报销单" filterable style="width:100%">
        <el-option v-for="b in batchOptions" :key="b.id" :label="`${b.batch_no}（${b.count || 0} 张）`" :value="b.id" />
      </el-select>
      <template #footer>
        <el-button @click="attachDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!attachBatch" @click="doAttach">加入</el-button>
      </template>
    </el-dialog>

    <!-- 查验进度 -->
    <el-dialog v-model="auditVisible" title="发票查验" width="420px" :close-on-click-modal="false">
      <div style="margin-bottom:10px;font-size:13px">
        进度 {{ audit.done }}/{{ audit.total }}
        <span class="text-muted" style="margin-left:8px">
          成功 {{ audit.ok }}　不通过 {{ audit.fail }}　跳过 {{ audit.skip }}
        </span>
      </div>
      <el-progress :percentage="audit.total ? Math.round(audit.done / audit.total * 100) : 0" />
      <div class="audit-log">
        <div v-for="(l, i) in audit.log.slice(-8)" :key="i">[{{ l.t }}] {{ l.text }}</div>
      </div>
      <template #footer>
        <el-button v-if="audit.running" type="danger" plain @click="stopAudit">停止查验</el-button>
        <el-button @click="auditVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Lock } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { state, isAdmin, can, applyStats } from '../store'
import DetailDrawer from '../components/DetailDrawer.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const rows = ref([])
const persons = ref([])
const months = ref([])
const batchOptions = ref([])
const stats = ref(null)

const filters = reactive({ q: '', person: '', month: '', status: '', audit: '', batch: '', review: '' })
const selection = ref([])
const page = ref(1)
const tableRef = ref(null)
const pageSize = ref(20)

const drawerVisible = ref(false)
const drawerType = ref('invoice')
const drawerId = ref(null)

const attachDialog = ref(false)
const attachBatch = ref(null)

const auditVisible = ref(false)
const auditChecking = ref(false)
const audit = reactive({ running: false, total: 0, done: 0, ok: 0, fail: 0, skip: 0, log: [] })
let auditTimer = null

const isPendingView = computed(() => route.name === 'pending')
// 勾选跨页保留（表格用了 reserve-selection），翻页不清空；
// 但改了筛选条件就得清，否则会操作到已经筛掉、看不见的发票
watch(() => [filters.q, filters.person, filters.month, filters.status,
             filters.audit, filters.batch, filters.review],
      () => clearSelection())

const pageCount = computed(() => Math.max(1, Math.ceil(rows.value.length / pageSize.value)))
const pageRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return rows.value.slice(start, start + pageSize.value)
})
const auditSummary = computed(() => {
  const a = stats.value?.audit || {}
  const parts = []
  if (a[1]) parts.push(`通过 ${a[1]}`)
  if (a[2]) parts.push(`不通过 ${a[2]}`)
  if (a[0]) parts.push(`未查 ${a[0]}`)
  return parts.length ? '查验 ' + parts.join(' · ') : ''
})

function money(v) {
  return '¥' + Number(v || 0).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}
/** 收款人显示：一个人就写名字，多人写"张三 等 2 人" */
function payeeLabel(row) {
  const names = String(row.payee_names || '').split('、').filter(Boolean)
  if (!names.length) return row.payee || '—'
  return names.length === 1 ? names[0] : `${names[0]} 等 ${names.length} 人`
}

/** 悬浮提示：把所有人 + 金额列出来 */
function payeeTip(row) {
  const names = String(row.payee_names || '').split('、').filter(Boolean)
  if (names.length <= 1) return names[0] || ''
  return names.join('、')
}

/** 为什么这张票锁着：说清楚，别让人猜 */
function lockReason(row) {
  if (row.batch_review === 'approved') return '单据已通过审核，不能再改'
  if (Number(row.status) === 1) return '这张已经报销，已锁定'
  return '没有修改权限'
}

function auditText(s) {
  return { 0: '未查验', 1: '查验通过', 2: '查验不通过', 3: '无需查验' }[Number(s)] || '未查验'
}
function auditType(s) {
  return { 0: 'info', 1: 'success', 2: 'danger', 3: 'info' }[Number(s)] || 'info'
}
function canEditRow(r) {
  if (isAdmin.value) return r.batch_review !== 'approved'
  if (!r.batch_id) return true
  return r.batch_review === 'returned'
}
function canDeleteRow(r) {
  if (!state.me) return false
  const owner = Number(r.owner_id || r.batch_owner || 0)
  if (owner !== Number(state.me.id)) return false
  if (!r.batch_id) return true
  return r.batch_review !== 'approved'
}

async function reload() {
  loading.value = true
  try {
    const q = { ...filters }
    if (!isAdmin.value) delete q.review
    if (isPendingView.value) q.review = 'submitted'
    const r = await api.list(q)
    rows.value = r.records || []
    persons.value = r.persons || []
    months.value = r.months || []
    batchOptions.value = r.batches || []
    stats.value = r.stats
    applyStats(r.stats)
    page.value = 1
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  } finally {
    loading.value = false
  }
}

function onSelect(list) { selection.value = list }

/** 选中的里面有多少张不在当前页（跨页保留） */
const selectedOnOtherPages = computed(() => {
  const ids = new Set(pageRows.value.map(r => r.id))
  return selection.value.filter(r => !ids.has(r.id)).length
})

/**
 * 取消选择。
 * 只把 selection 数组清空是不够的——表格内部的勾选框还亮着，
 * 会变成「全都没勾上但方框还是勾的」。必须调表格自己的 clearSelection。
 */
function clearSelection() {
  tableRef.value?.clearSelection()
  selection.value = []
}

function onRowClick(row, col, ev) {
  // 点开关、勾选框、按钮、链接时不要顺带打开详情
  if (col?.type === 'selection' || col?.label === '操作') return
  if (ev?.target?.closest?.('button, a, input, .el-switch, .el-checkbox')) return
  openInvoice(row.id)
}

function openInvoice(id) {
  drawerType.value = 'invoice'
  drawerId.value = id
  drawerVisible.value = true
}
function openBatch(id) {
  drawerType.value = 'batch'
  drawerId.value = id
  drawerVisible.value = true
}

async function setStatus(row, v) {
  try {
    await api.setStatus(row.id, v ? 1 : 0)
    row.status = v ? 1 : 0
    ElMessage.success('已更新')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function removeRow(row) {
  try {
    await ElMessageBox.confirm('删除后发票会移入「已删除」，确定吗？', '删除发票', { type: 'warning' })
  } catch (e) { return }
  try {
    await api.remove(row.id)
    ElMessage.success('已删除')
    reload()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function doAttach() {
  try {
    await api.attach(selection.value.map(r => r.id), attachBatch.value)
    ElMessage.success('已归单')
    attachDialog.value = false
    reload()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function detachSelected() {
  try {
    await api.detach(selection.value.map(r => r.id))
    ElMessage.success('已拆出')
    reload()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function exportExcel() {
  window.open(api.exportUrl({ ...filters }), '_blank')
}

  async function startAudit(ids) {
    const list = ids && ids.length ? ids : selection.value.map(r => r.id)
    if (!list.length) {
      ElMessage.info('请先勾选要查验的发票')
      return
    }
    try {
      await api.auditStart(list)
      auditVisible.value = true
      pollAudit()
    } catch (e) {
      ElMessage.error(e.message || '启动查验失败')
    }
  }

  /**
   * 工具栏上的「查验」：
   * 勾了就查勾中的；没勾就问一句要不要查全部「未查验 / 上次没通过」的。
   * 旧版就是这个行为，按钮一直摆在外面。
   */
  async function auditClick() {
    if (selection.value.length) {
      return startAudit()
    }
    auditChecking.value = true
    try {
      const r = await api.list({})
      const ids = (r.records || [])
        .filter(x => [0, 2].includes(Number(x.audit_status || 0)))
        .map(x => x.id)
      if (!ids.length) {
        ElMessage.success('没有需要查验的发票，都是查过的')
        return
      }
      await ElMessageBox.confirm(
        `没有勾选发票。要查验全部「未查验 / 上次没通过」的 ${ids.length} 张吗？`,
        '查验哪些发票',
        { confirmButtonText: '全部查验', cancelButtonText: '取消', type: 'info' }
      )
      await startAudit(ids)
    } catch (e) {
      if (e !== 'cancel' && e !== 'close') {
        ElMessage.error(e.message || '启动查验失败')
      }
    } finally {
      auditChecking.value = false
    }
  }

async function pollAudit() {
  clearTimeout(auditTimer)
  try {
    const r = await api.auditStatus()
    // 后端把进度放在 state 里（旧接口叫 status），两个都兼容
    Object.assign(audit, r.state || r.status || r)
    if (audit.running) {
      auditTimer = setTimeout(pollAudit, 1200)
    } else {
      reload()
    }
  } catch (e) {
    audit.running = false
  }
}

async function stopAudit() {
  await api.auditStop().catch(() => {})
  pollAudit()
}

watch(() => route.name, () => {
  filters.review = ''
  reload()
})

onMounted(reload)
</script>

<style scoped>
.ledger-stats {
  display: flex; align-items: center; gap: 22px;
  margin-left: auto; padding-right: 4px;
}
.ls-item { display: flex; align-items: baseline; gap: 3px; font-size: 12.5px; color: #6B7280; }
.ls-item b { font-size: 17px; font-weight: 700; color: var(--brand-text); }
.ls-item .u { font-size: 11px; color: #9AA6B6; }
.ls-item .l { color: #6B7280; }
.lock-ico {
  color: #8A94A6; font-size: 16px; cursor: help;
  vertical-align: middle; margin-top: 2px;
}
.lock-ico:hover { color: var(--el-color-primary); }
.payee-cell {
  display: inline-block; max-width: 100%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  vertical-align: bottom;
}
.payee-cell.other { color: #E6A23C; }

.other-page { color: var(--brand-text-3); font-size: 12px; }
.selbar {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 16px;
  background: var(--selbar-bg); border-bottom: 1px solid var(--selbar-line);
  font-size: 13px;
}
.selbar b { color: var(--el-color-primary); }
.audit-log {
  margin-top: 10px; padding: 8px 10px; max-height: 120px; overflow: auto;
  background: #F9FAFB; border: 1px solid #E5E7EB; border-radius: 4px;
  font-size: 12px; color: #6B7280; line-height: 1.7;
}
:deep(.el-table__row) { cursor: pointer; }
</style>
