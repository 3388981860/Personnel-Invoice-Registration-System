<template>
  <div>
    <div class="panel">
      <div class="panel-hd">
        <span>开票申请</span>
        <span class="sub">
          共 {{ list.length }} 张
          <template v-if="pending">· <b style="color:#C62828">{{ pending }} 张待审核</b></template>
        </span>
        <div style="margin-left:auto;display:flex;gap:8px">
          <div class="filter-pair">
            <el-input v-model="keyword" size="small" placeholder="单号 / 单位 / 内容" clearable
                      @keyup.enter="load" @clear="load" />
            <el-select v-model="statusFilter" size="small" @change="load">
            <el-option value="all" label="全部状态" />
            <el-option value="draft" label="草稿" />
            <el-option value="submitted" label="待审核" />
            <el-option value="approved" label="已通过" />
            <el-option value="returned" label="已退回" />
              <el-option value="invoiced" label="已开票" />
            </el-select>
          </div>
          <el-button @click="load">刷新</el-button>
          <el-button type="primary" @click="openNew">+ 新建申请</el-button>
        </div>
      </div>

      <el-table :data="list" v-loading="loading" size="small" border
                max-height="calc(100vh - 240px)">
        <el-table-column prop="no" label="单号" width="120">
          <template #default="{ row }"><span class="mono">{{ row.no }}</span></template>
        </el-table-column>
        <el-table-column prop="buyer_name" label="单位全称" min-width="200" show-overflow-tooltip />
        <el-table-column label="开票金额" width="120" align="right">
          <template #default="{ row }"><span class="money">{{ money(row.amount) }}</span></template>
        </el-table-column>
        <el-table-column prop="content" label="开票内容" min-width="180" show-overflow-tooltip />
        <el-table-column prop="inv_type" label="类型" width="96" />
        <el-table-column prop="applicant_name" label="申请人" width="96" />
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="创建时间" width="150" />
        <el-table-column label="操作" width="230" align="right">
          <template #default="{ row }">
            <el-button v-if="canEdit(row)" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canSign(row)" link type="primary" size="small" @click="openSign(row)">签名</el-button>
            <el-button v-if="canSubmit(row)" link type="success" size="small" @click="submit(row)">提交</el-button>
            <el-button v-if="canReview && row.status === 'submitted'" link type="success" size="small"
                       @click="review(row, true)">通过</el-button>
            <el-button v-if="canReview && row.status === 'submitted'" link type="warning" size="small"
                       @click="review(row, false)">退回</el-button>
            <el-button v-if="row.status === 'approved' && row.contract_id" link type="success"
                       size="small" @click="openInvoiced(row)">已开票</el-button>
            <el-button v-if="row.status === 'approved' || row.status === 'invoiced'" link
                       type="primary" size="small" @click="print(row)">打印</el-button>
            <el-button v-if="canEdit(row)" link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 申请单表单 -->
    <el-dialog v-model="dlg" :title="form.id ? '修改开票申请' : '新建开票申请'" width="640px">
      <el-form :model="form" label-width="96px" label-position="right">
        <el-form-item label="关联合同">
          <el-select v-model="form.contract_id" filterable clearable placeholder="可选，选了自动带出单位"
                     style="width:100%" @change="onContractPick">
            <el-option v-for="c in contracts" :key="c.id"
                       :label="`${c.party_a} → ${c.party_b}  ${c.contract_no || ''}`"
                       :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户档案">
          <el-select v-model="form.customer_id" filterable clearable placeholder="选客户，自动带出税号和开户行"
                     style="width:100%" @change="onCustomerPick">
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="单位全称" required>
          <el-input v-model="form.buyer_name" />
        </el-form-item>
        <el-form-item label="企业税号">
          <el-input v-model="form.buyer_tax_no" placeholder="客户档案里有就自动带出" />
        </el-form-item>
        <el-form-item label="开票金额" required>
          <el-input v-model="form.amount" placeholder="只要数字" style="width:200px" />
        </el-form-item>
        <el-form-item label="开票内容" required>
          <el-input v-model="form.content" placeholder="比如：国有资产监管信息系统服务费" />
        </el-form-item>
        <el-form-item label="开票类型">
          <el-radio-group v-model="form.inv_type">
            <el-radio value="专用发票">专用发票</el-radio>
            <el-radio value="普通发票">普通发票</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="开票部门">
          <el-select v-model="form.dept" filterable allow-create default-first-option
                     placeholder="选钉钉部门，也能自己填" style="width:100%">
            <el-option v-for="d in departments" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="收款银行">
          <el-input v-model="form.bank" placeholder="默认温州银行" />
        </el-form-item>
        <el-form-item label="申请人">
          <el-select v-model="form.applicant_name" filterable allow-create
                     default-first-option placeholder="默认当前账号，也能选钉钉同事"
                     style="width:100%">
            <el-option v-for="m in members" :key="'a' + m.userid"
                       :label="m.name + (m.mobile ? ' · ' + m.mobile : '')" :value="m.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="代办人">
          <el-select v-model="form.agent_name" filterable allow-create clearable
                     default-first-option placeholder="谁去办，可留空" style="width:100%">
            <el-option v-for="m in members" :key="'g' + m.userid"
                       :label="m.name + (m.mobile ? ' · ' + m.mobile : '')" :value="m.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <div class="text-muted" style="font-size:12px;margin-top:-6px">
        保存后点「签名」手写签上名字，再点「提交」走审核。
      </div>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 标记已开票 -->
    <el-dialog v-model="invDlg" title="标记已开票" width="440px">
      <div style="margin-bottom:12px;font-size:13px;color:#4B5563">
        填了发票号码后保存，系统会自动往合同里记一笔开票记录，合同的已开票金额跟着涨。
      </div>
      <el-form label-width="90px">
        <el-form-item label="发票号码">
          <el-input v-model="invForm.invoice_no" placeholder="可留空，开完票再补" />
        </el-form-item>
        <el-form-item label="开票日期">
          <el-date-picker v-model="invForm.happened_at" type="date" value-format="YYYY-MM-DD"
                          style="width:100%" />
        </el-form-item>
        <el-form-item label="阶段">
          <el-input v-model="invForm.stage" placeholder="比如：首付款 / 尾款" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="invDlg = false">取消</el-button>
        <el-button type="primary" :loading="invSaving" @click="saveInvoiced">确认已开票</el-button>
      </template>
    </el-dialog>

    <SignPad v-model="signDlg" @done="onSignDone" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { state, can, isAdmin, refreshBadges } from '../store'
import SignPad from '../components/SignPad.vue'

const list = ref([])
const pending = ref(0)
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref('all')
const dlg = ref(false)
const saving = ref(false)
const signDlg = ref(false)
const signTarget = ref(null)
const invDlg = ref(false)
const invSaving = ref(false)
const invForm = reactive({ id: null, invoice_no: '', happened_at: '', stage: '按申请单开票' })
const contracts = ref([])
const customers = ref([])
const departments = ref([])
const members = ref([])

const canReview = computed(() => isAdmin.value || can('review'))

const form = reactive({
  id: null, contract_id: null, project_id: null, customer_id: null,
  buyer_name: '', buyer_tax_no: '', amount: '', content: '', note: '',
  inv_type: '普通发票', dept: '市场部', bank: '温州银行',
  applicant_name: '', agent_name: ''
})

function money(v) {
  return '¥' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const STATUS = { draft: '草稿', submitted: '待审核', approved: '已通过', returned: '已退回', invoiced: '已开票' }
function statusText(s) { return STATUS[s] || s }
function statusType(s) {
  return { draft: 'info', submitted: 'warning', approved: 'success',
    returned: 'danger', invoiced: 'primary' }[s] || 'info'
}

function mine(row) {
  return String(row.applicant_id) === String(state.me?.id)
}
function canEdit(row) {
  return (mine(row) || isAdmin.value) && ['draft', 'returned'].includes(row.status)
}
function canSign(row) {
  return canEdit(row)
}
function canSubmit(row) {
  return canEdit(row) && !!row.sign_rel
}

async function load() {
  loading.value = true
  try {
    const status = statusFilter.value === 'all' ? '' : statusFilter.value
    const r = await api.invoiceRequests({ status, q: keyword.value })
    list.value = r.requests || []
    pending.value = r.pending || 0
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  } finally {
    loading.value = false
  }
}

/** 钉钉部门 + 成员，失败就给个兜底部门 */
async function loadDingPicker() {
  try {
    const r = await api.dingtalkPicker()
    departments.value = (r.departments || []).filter(Boolean)
    members.value = (r.members || []).filter(m => m.name)
  } catch (e) {
    departments.value = []
    members.value = []
  }
  if (!departments.value.length) {
    departments.value = ['市场部', '技术部']
  }
}

async function loadRefs() {
  try {
    const [c1, c2] = await Promise.all([api.projects({}), api.customers({})])
    contracts.value = []
    for (const p of c1.projects || []) {
      const d = await api.project(p.id)
      for (const c of d.project?.contracts || []) {
        if (c.kind === 'income') {
          contracts.value.push({ ...c, project_name: p.name })
      }
      }
    }
    customers.value = c2.customers || []
  } catch (e) {
    // 带不出来也不影响手填
  }
}

function openNew() {
  Object.assign(form, {
    id: null, contract_id: null, project_id: null, customer_id: null,
    buyer_name: '', buyer_tax_no: '', amount: '', content: '', note: '',
    inv_type: '普通发票', dept: '市场部', bank: '温州银行',
    applicant_name: state.me?.display_name || state.me?.username || '',
    agent_name: ''
  })
  dlg.value = true
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id, contract_id: row.contract_id, project_id: row.project_id,
    customer_id: row.customer_id, buyer_name: row.buyer_name || '',
    buyer_tax_no: row.buyer_tax_no || '', amount: row.amount ?? '',
    content: row.content || '', note: row.note || '',
    inv_type: row.inv_type || '普通发票', dept: row.dept || '市场部',
    bank: row.bank || '温州银行', applicant_name: row.applicant_name || '',
    agent_name: row.agent_name || ''
  })
  dlg.value = true
}

function onContractPick(id) {
  const c = contracts.value.find(x => x.id === id)
  if (!c) return
  form.project_id = c.project_id
  if (!form.buyer_name) form.buyer_name = c.party_a
  if (!form.amount && c.amount) form.amount = c.amount
}

function onCustomerPick(id) {
  const c = customers.value.find(x => x.id === id)
  if (!c) return
  form.buyer_name = c.name
  form.buyer_tax_no = c.tax_no || ''
  if (c.bank) form.bank = c.bank
}

async function save() {
  if (!form.buyer_name.trim()) return ElMessage.warning('填单位全称')
  if (!form.content.trim()) return ElMessage.warning('填开票内容')
  if (!String(form.amount).trim()) return ElMessage.warning('填开票金额')
  saving.value = true
  try {
    await api.invoiceRequestSave({ ...form })
    ElMessage.success('已保存')
    dlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function openSign(row) {
  signTarget.value = row
  signDlg.value = true
}

async function onSignDone(dataUrl) {
  try {
    await api.invoiceRequestSign({ id: signTarget.value.id, image: dataUrl })
    ElMessage.success('已签名，可以提交了')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '签名保存失败')
  }
}

async function submit(row) {
  try {
    await api.invoiceRequestSubmit({ id: row.id })
    ElMessage.success('已提交，等管理员审核')
    await load()
    refreshBadges()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  }
}

async function review(row, pass) {
  let note = ''
  if (!pass) {
    try {
      const r = await ElMessageBox.prompt('退回原因（会通知申请人）', '退回', {
        inputPlaceholder: '比如：金额和合同对不上'
      })
      note = r.value || ''
    } catch (e) {
      return
    }
  }
  try {
    await api.invoiceRequestReview({ id: row.id, pass, note })
    ElMessage.success(pass ? '已通过' : '已退回')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`删除申请单 ${row.no}？`, '删除', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await api.invoiceRequestDelete({ id: row.id })
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function openInvoiced(row) {
  invForm.id = row.id
  invForm.invoice_no = ''
  invForm.happened_at = new Date().toISOString().slice(0, 10)
  invForm.stage = '按申请单开票'
  invDlg.value = true
}

async function saveInvoiced() {
  invSaving.value = true
  try {
    await api.invoiceRequestMarkInvoiced({ ...invForm })
    ElMessage.success('已开票，合同里已记上这笔')
    invDlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    invSaving.value = false
  }
}

function print(row) {
  window.open(`/invoice-print/${row.id}`, '_blank')
}

onMounted(async () => {
  await load()
  loadDingPicker()
  loadRefs()
})
</script>

<style scoped>

/* 下拉框默认比输入框矮 8px（24 vs 32），用 :deep 穿透组件样式拉齐 */
.filter-pair :deep(.el-select__wrapper) {
  min-height: 32px;
}
.filter-pair :deep(.el-input__wrapper) {
  min-height: 32px;
}
/* 两个筛选控件严格等宽：外层固定宽度，内部各占一半 */
.filter-pair {
  display: flex; gap: 8px; width: 368px; flex: none;
}
.filter-pair > .el-input,
.filter-pair > .el-select {
  flex: 1 1 0; width: 0; min-width: 0; box-sizing: border-box;
}
</style>
