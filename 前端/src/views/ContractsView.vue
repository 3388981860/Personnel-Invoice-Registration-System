<template>
  <div class="page">
    <div class="panel">
      <div class="panel-head">
        <h2>合同台账</h2>
        <div style="display:flex;gap:10px;align-items:center">
          <el-input v-model="q" placeholder="搜索 项目 / 单位 / 编号" clearable
                    style="width:240px" @keyup.enter="reload" @clear="reload" />
          <el-button @click="reload">刷新</el-button>
          <el-button @click="exportExcel">导出</el-button>
          <el-button type="primary" @click="openProject()">+ 新建项目</el-button>
        </div>
      </div>

      <div v-if="!isAdmin" class="scope-tip">
        你只能看到自己创建的项目；管理员可以看到全部。
      </div>

      <el-table :data="pagedProjects" v-loading="loading" size="small" border
                max-height="calc(100vh - 300px)"
                empty-text="还没有项目，点右上角「新建项目」">
        <el-table-column prop="code" label="编号" width="90" />
        <el-table-column label="项目名称" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" :underline="false" class="proj-link"
                     @click="openProject(row.id)">{{ row.name }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="甲方单位" width="130" />
        <el-table-column label="创建人" width="100">
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.owner_name }">{{ row.owner_name || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="服务期" width="180">
          <template #default="{ row }">
            <span class="text-muted">{{ period(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="contract_count" label="合同" width="64" align="center" />
        <el-table-column label="收入合同" width="110" align="right">
          <template #default="{ row }"><span class="money">{{ money(row.income_amount) }}</span></template>
        </el-table-column>
        <el-table-column label="已开票" width="130" align="right">
          <template #default="{ row }">
            <span class="money">{{ money(row.invoiced) }}</span>
            <span class="ratio">{{ pct(row.invoiced, row.income_amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="已回款" width="130" align="right">
          <template #default="{ row }">
            <span class="money">{{ money(row.received) }}</span>
            <span class="ratio">{{ pct(row.received, row.income_amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openProject(row.id)">详情</el-button>
            <el-button link type="danger" size="small" v-if="isAdmin"
                       @click="removeProject(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="projects.length"
          layout="total, sizes, prev, pager, next, jumper"
          background
          size="small"
        />
      </div>
    </div>

    <!-- 项目详情 -->
    <el-drawer v-model="drawer" direction="rtl" size="680px" class="detail-drawer">
      <template #header>
        <div class="drawer-head">
          <span>{{ project?.name || '项目详情' }}</span>
          <span v-if="project?.code" class="head-sub">· {{ project.code }}</span>
        </div>
      </template>

      <div v-if="project" v-loading="detailLoading">
        <div class="amount-hero">
          <div class="hero-money">{{ money(project.income_amount) }}</div>
          <div class="hero-cap">收入合同合计 · {{ project.contracts?.length || 0 }} 份合同</div>
        </div>

        <div class="kpi">
          <div class="kpi-cell">
            <span>已开票</span>
            <b>{{ money(project.invoiced) }}</b>
            <i>{{ pct(project.invoiced, project.income_amount) }}</i>
          </div>
          <div class="kpi-cell">
            <span>已回款</span>
            <b>{{ money(project.received) }}</b>
            <i>{{ pct(project.received, project.income_amount) }}</i>
          </div>
          <div class="kpi-cell">
            <span>外协成本</span>
            <b>{{ money(project.cost_amount) }}</b>
            <i>已付 {{ money(project.paid_cost) }}</i>
          </div>
        </div>

        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="甲方单位">{{ project.unit || '—' }}</el-descriptions-item>
          <el-descriptions-item label="服务期">{{ period(project) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ project.note || '—' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 合同分组 -->
        <template v-for="grp in contractGroups" :key="grp.kind">
          <div class="sec sec-with-btn">
            {{ grp.title }}（{{ grp.list.length }} 份）
            <el-button size="small" type="primary" plain @click="openContract(null, grp.kind)">
              + 添加
            </el-button>
          </div>
          <div v-if="!grp.list.length" class="group-empty">还没有，点右上「+ 添加」</div>
          <div v-if="grp.list.length" class="contract-list">
            <div v-for="c in grp.list" :key="c.id" class="contract-card">
              <div class="cc-head">
                <b>{{ c.party_a || '—' }} → {{ c.party_b || '—' }}</b>
                <el-tag size="small" :type="signedType(c.signed)">{{ c.signed || '未填' }}</el-tag>
                <span class="cc-no">{{ c.contract_no || '' }}</span>
              </div>
              <div class="cc-body">
                <span>合同金额 <b class="money">{{ c.amount == null ? '—' : money(c.amount) }}</b></span>
                <span>已开票 <b>{{ money(c.invoiced) }}</b> {{ pct(c.invoiced, c.amount) }}</span>
                <span>{{ grp.kind === 'cost' ? '已付' : '已回款' }} <b>{{ money(c.paid) }}</b> {{ pct(c.paid, c.amount) }}</span>
              </div>
              <div v-if="c.billings?.length" class="cc-bills">
                <div v-for="bl in c.billings" :key="bl.id" class="cc-bill">
                  <el-tag size="small" :type="bl.kind === 'invoice' ? 'warning' : 'success'"
                          effect="plain">
                    {{ bl.kind === 'invoice' ? '开票' : (grp.kind === 'cost' ? '付款' : '回款') }}
                  </el-tag>
                  <b class="money">{{ money(bl.amount) }}</b>
                  <span v-if="bl.stage" class="text-muted">{{ bl.stage }}</span>
                  <span v-if="bl.no" class="text-muted mono">{{ bl.no }}</span>
                  <span v-if="bl.happened_at" class="text-muted">{{ bl.happened_at }}</span>
                  <span v-if="bl.note" class="text-muted">{{ bl.note }}</span>
                  <el-button link type="danger" size="small" @click="removeBilling(bl)">删</el-button>
                </div>
              </div>
              <div class="cc-foot">
                <el-button size="small" @click="openBilling(c, 'invoice')">+ 开票</el-button>
                <el-button size="small" @click="openBilling(c, 'payment')">
                  + {{ grp.kind === 'cost' ? '付款' : '回款' }}
                </el-button>
                <el-button v-if="grp.kind === 'cost'" type="primary" plain size="small"
                           @click="openPay(c)">生成付款单</el-button>
                <span style="width:1px;height:14px;background:#E5E7EB;margin:0 4px" />
                <el-button v-if="c.file_rel" link type="primary" size="small"
                           @click="openFile(c.id)">查看扫描件</el-button>
                <el-upload :show-file-list="false" :http-request="(o) => upload(c.id, o)"
                           accept=".pdf,.ofd,.jpg,.jpeg,.png" style="display:inline-block">
                  <el-button link type="primary" size="small">
                    {{ c.file_rel ? '重新上传' : '上传扫描件' }}
                  </el-button>
                </el-upload>
                <el-button link size="small" @click="openContract(c)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeContract(c)">删除</el-button>
                <span v-if="c.note" class="text-muted" style="margin-left:8px">{{ c.note }}</span>
              </div>
            </div>
          </div>
        </template>


      </div>
    </el-drawer>

    <!-- 项目弹窗 -->
    <el-dialog v-model="projectDlg" :title="pform.id ? '编辑项目' : '新建项目'"
               width="520px" append-to-body>
      <el-form label-position="top">
        <div class="form-row">
          <el-form-item label="编号">
            <el-input v-model="pform.code" placeholder="留空自动生成，如 HT-2026-001" />
          </el-form-item>
          <el-form-item label="甲方单位">
            <el-input v-model="pform.unit" placeholder="如 区财政局" />
          </el-form-item>
        </div>
        <el-form-item label="项目名称" required>
          <el-input v-model="pform.name" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="开始">
            <el-date-picker v-model="pform.start_date" type="date" value-format="YYYY-MM-DD"
                            style="width:100%" />
          </el-form-item>
          <el-form-item label="结束">
            <el-date-picker v-model="pform.end_date" type="date" value-format="YYYY-MM-DD"
                            style="width:100%" />
          </el-form-item>
        </div>
        <el-form-item label="备注">
          <el-input v-model="pform.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>

    <!-- 外协付款单 -->
    <el-dialog v-model="payDlg" title="生成外协付款单" width="500px" append-to-body>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:14px"
                :title="`${payForm.payee} · 合同 ${payForm.contract_no || '—'}`"
                description="生成后是一张普通报销单，走审核 → 打款；打款完成会自动回填这份合同的已付金额。" />
      <el-form label-position="top">
        <div class="form-row">
          <el-form-item label="外协开给我们的发票号" required>
            <el-input v-model="payForm.invoice_no" placeholder="发票号码" />
          </el-form-item>
          <el-form-item label="付款金额" required>
            <el-input v-model="payForm.amount" placeholder="0.00" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="报销人（项目负责人）" required>
            <el-input v-model="payForm.person" />
          </el-form-item>
          <el-form-item label="收款人（外协单位）">
            <el-input v-model="payForm.payee" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="发票日期">
            <el-date-picker v-model="payForm.reimburse_date" type="date"
                            value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="payForm.note" placeholder="选填" />
          </el-form-item>
        </div>
        <div class="text-muted" style="font-size:12px">{{ payHint }}</div>
      </el-form>
      <template #footer>
        <el-button @click="payDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doPay">生成</el-button>
      </template>
    </el-dialog>

    <!-- 开票 / 回款 -->
    <el-dialog v-model="billDlg" :title="billTitle" width="440px" append-to-body>
      <el-form label-position="top">
        <div class="form-row">
          <el-form-item label="阶段">
            <el-select v-model="bform2.stage" clearable placeholder="选择或手填"
                       filterable allow-create style="width:100%">
              <el-option v-for="s in ['首付款', '进度款', '尾款', '全额']" :key="s"
                         :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item label="金额" required>
            <el-input v-model="bform2.amount" placeholder="0.00" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item :label="bform2.kind === 'invoice' ? '发票号' : '凭证号'">
            <el-input v-model="bform2.no" :placeholder="bform2.kind === 'invoice' ? '发票号码' : '选填'" />
          </el-form-item>
          <el-form-item label="日期">
            <el-date-picker v-model="bform2.happened_at" type="date" value-format="YYYY-MM-DD"
                            style="width:100%" />
          </el-form-item>
        </div>
        <el-form-item label="备注">
          <el-input v-model="bform2.note" placeholder="选填" />
        </el-form-item>
        <div v-if="billHint" class="text-muted" style="font-size:12px">{{ billHint }}</div>
      </el-form>
      <template #footer>
        <el-button @click="billDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBilling">保存</el-button>
      </template>
    </el-dialog>

    <!-- 合同弹窗 -->
    <el-dialog v-model="contractDlg" :title="cform.id ? '编辑合同' : '添加合同'"
               width="520px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="合同类型">
          <el-radio-group v-model="cform.kind">
            <el-radio value="income">收入（别人付给我们）</el-radio>
            <el-radio value="cost">外协（我们付出去）</el-radio>
            <el-radio value="reference">关联（只跟踪）</el-radio>
          </el-radio-group>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="付款方">
            <el-input v-model="cform.party_a" placeholder="谁付钱" />
          </el-form-item>
          <el-form-item label="收款方">
            <el-input v-model="cform.party_b" placeholder="谁收钱" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="合同编号">
            <el-input v-model="cform.contract_no" />
          </el-form-item>
          <el-form-item label="合同金额">
            <el-input v-model="cform.amount" placeholder="不知道可留空" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="签订状态">
            <el-select v-model="cform.signed" clearable placeholder="选择" style="width:100%">
              <el-option v-for="s in ['已签','待上会','未签','谈判中']" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item label="签订日期">
            <el-date-picker v-model="cform.signed_at" type="date" value-format="YYYY-MM-DD"
                            style="width:100%" />
          </el-form-item>
        </div>
        <el-form-item label="备注">
          <el-input v-model="cform.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contractDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveContract">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { isAdmin } from '../store'

const q = ref('')
const projects = ref([])
const page = ref(1)
const pageSize = ref(20)

/** 当前页的项目 */
const pagedProjects = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return projects.value.slice(start, start + pageSize.value)
})
const loading = ref(false)

const drawer = ref(false)
const detailLoading = ref(false)
const project = ref(null)

const projectDlg = ref(false)
const contractDlg = ref(false)
const saving = ref(false)
const pform = ref({})
const billDlg = ref(false)
const bform2 = ref({})
const billTitle = ref('')
const billHint = ref('')
const payDlg = ref(false)
const payForm = ref({})
const payHint = ref('')
const cform = ref({})

const GROUPS = [
  { kind: 'reference', title: '关联合同（上游，我们只看进度）' },
  { kind: 'income', title: '收入合同' },
  { kind: 'cost', title: '外协成本合同' }
]

const contractGroups = computed(() =>
  GROUPS.map(g => ({
    ...g,
    list: (project.value?.contracts || []).filter(c => c.kind === g.kind)
  })))

function money(v) {
  if (v == null || v === '') return '—'
  return '¥' + Number(v || 0).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}
function pct(a, b) {
  const t = Number(b || 0)
  if (!t) return ''
  return '（' + Math.round(Number(a || 0) * 100 / t) + '%）'
}
function period(row) {
  const a = row.start_date || ''
  const b = row.end_date || ''
  if (!a && !b) return '—'
  return `${a} ~ ${b}`
}
function signedType(s) {
  return { 已签: 'success', 待上会: 'warning', 谈判中: 'warning', 未签: 'info' }[s] || 'info'
}

async function reload() {
  loading.value = true
  try {
    const r = await api.projects({ q: q.value })
    projects.value = r.projects || []
    page.value = 1
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  } finally {
    loading.value = false
  }
}

async function openProject(id) {
  if (id == null) {
    pform.value = { code: '', name: '', unit: '', start_date: '', end_date: '', note: '' }
    projectDlg.value = true
    return
  }
  drawer.value = true
  detailLoading.value = true
  try {
    const r = await api.project(id)
    project.value = r.project
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  } finally {
    detailLoading.value = false
  }
}

async function saveProject() {
  if (!String(pform.value.name || '').trim()) {
    return ElMessage.warning('请填写项目名称')
  }
  saving.value = true
  try {
    const r = await api.saveProject(pform.value)
    ElMessage.success('已保存')
    projectDlg.value = false
    await reload()
    if (drawer.value && r.id) await openProject(r.id)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeProject(row) {
  try {
    await ElMessageBox.confirm(
      `删除项目「${row.name}」？它下面的合同和扫描件会一起删掉。`,
      '删除项目', { type: 'warning' })
  } catch (e) { return }
  try {
    await api.deleteProject(row.id)
    ElMessage.success('已删除')
    reload()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function openContract(c, kind) {
  if (c) {
    cform.value = { ...c, amount: c.amount == null ? '' : c.amount }
  } else {
    cform.value = {
      project_id: project.value.id,
      kind: kind || 'income',
      party_a: kind === 'cost' ? '人数（我司）' : '',
      party_b: kind === 'cost' ? '' : '人数（我司）',
      contract_no: '', amount: '', signed: '已签', signed_at: '', note: ''
    }
  }
  contractDlg.value = true
}

async function saveContract() {
  saving.value = true
  try {
    await api.saveContract({ ...cform.value, project_id: project.value.id })
    ElMessage.success('已保存')
    contractDlg.value = false
    await openProject(project.value.id)
    await reload()
    drawer.value = true
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeContract(c) {
  try {
    await ElMessageBox.confirm('删除这份合同？扫描件也会一起删掉。', '删除合同',
      { type: 'warning' })
  } catch (e) { return }
  try {
    await api.deleteContract(c.id)
    ElMessage.success('已删除')
    await openProject(project.value.id)
    await reload()
    drawer.value = true
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function upload(id, option) {
  const fd = new FormData()
  fd.append('file', option.file)
  try {
    await api.contractUpload(id, fd)
    ElMessage.success('已上传')
    await openProject(project.value.id)
    drawer.value = true
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  }
}

function openBilling(c, kind) {
  bform2.value = {
    contract_id: c.id,
    kind,
    stage: kind === 'invoice' ? '首付款' : '首付款',
    amount: '', no: '', happened_at: new Date().toISOString().slice(0, 10), note: ''
  }
  billTitle.value = `${kind === 'invoice' ? '记一笔开票' : '记一笔' +
    (c.kind === 'cost' ? '付款' : '回款')} · ${c.party_a} → ${c.party_b}`
  const rest = Number(c.amount || 0) - Number(kind === 'invoice' ? c.invoiced : c.paid)
  billHint.value = c.amount
    ? `合同金额 ${money(c.amount)}，已${kind === 'invoice' ? '开票' : '记'} ${money(kind === 'invoice' ? c.invoiced : c.paid)}，还剩 ${money(rest)}`
    : '这份合同还没填金额，比例暂时算不出来'
  billDlg.value = true
}

async function saveBilling() {
  if (!(Number(bform2.value.amount) > 0)) {
    return ElMessage.warning('请填写金额')
  }
  saving.value = true
  try {
    await api.saveBilling({ ...bform2.value, amount: Number(bform2.value.amount) })
    ElMessage.success('已记上')
    billDlg.value = false
    await openProject(project.value.id)
    await reload()
    drawer.value = true
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeBilling(bl) {
  try {
    await ElMessageBox.confirm('删掉这条记录？', '删除', { type: 'warning' })
  } catch (e) { return }
  try {
    await api.deleteBilling(bl.id)
    ElMessage.success('已删除')
    await openProject(project.value.id)
    await reload()
    drawer.value = true
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function openPay(c) {
  const rest = Math.max(0, Number(c.amount || 0) - Number(c.paid || 0))
  payForm.value = {
    contract_id: c.id,
    contract_no: c.contract_no || '',
    invoice_no: '',
    amount: rest ? rest.toFixed(2) : '',
    person: meName(),
    payee: c.party_b || '',
    reimburse_date: new Date().toISOString().slice(0, 10),
    note: ''
  }
  payHint.value = c.amount
    ? `合同金额 ${money(c.amount)}，已付 ${money(c.paid)}，还剩 ${money(rest)}`
    : '这份合同还没填金额'
  payDlg.value = true
}

function meName() {
  return (project.value && project.value.owner_name) || '管理员'
}

async function doPay() {
  const f = payForm.value
  if (!String(f.invoice_no || '').trim()) {
    return ElMessage.warning('请填外协开给我们的发票号')
  }
  if (!(Number(f.amount) > 0)) {
    return ElMessage.warning('请填付款金额')
  }
  if (!String(f.person || '').trim()) {
    return ElMessage.warning('请填报销人')
  }
  saving.value = true
  try {
    const r = await api.contractPay({ ...f, amount: Number(f.amount) })
    ElMessage.success(`已生成报销单 ${r.batch_no || ''}，去「待审核」走流程`)
    payDlg.value = false
    await openProject(project.value.id)
    await reload()
    drawer.value = true
  } catch (e) {
    ElMessage.error(e.message || '生成失败')
  } finally {
    saving.value = false
  }
}

function exportExcel() {
  window.open('/api/contracts/export', '_blank')
}

function openFile(id) {
  window.open(`/api/contract/file?id=${id}`, '_blank')
}

onMounted(reload)
</script>

<style scoped>
.panel { background: #fff; border-radius: 6px; }
.pager {
  display: flex; justify-content: flex-end;
  padding: 12px 4px 4px;
}
.proj-link { font-size: 13px; }
.proj-link:hover { text-decoration: underline; }
.scope-tip {
  padding: 8px 16px; font-size: 12.5px; color: #6B7280;
  background: #F8FAFC; border-bottom: 1px solid #E5E7EB;
}
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px; border-bottom: 1px solid #E5E7EB;
}
.panel-head h2 { margin: 0; font-size: 15px; }
.ratio { color: #9CA3AF; font-size: 12px; margin-left: 4px; }

.amount-hero {
  margin: 0 0 14px; padding: 18px 20px; border-radius: 6px; text-align: center; color: #fff;
  background: linear-gradient(135deg, var(--el-color-primary), var(--el-color-primary-light-3));
}
.hero-money { font-size: 28px; font-weight: 700; }
.hero-cap { margin-top: 4px; font-size: 12px; opacity: .85; }

.kpi { display: flex; gap: 10px; margin-bottom: 14px; }
.kpi-cell {
  flex: 1; padding: 10px 12px; border-radius: 6px; background: #F8FAFC;
  border: 1px solid #E5E7EB; display: flex; flex-direction: column; gap: 2px;
}
.kpi-cell span { font-size: 12px; color: #6B7280; }
.kpi-cell b { font-size: 16px; }
.kpi-cell i { font-size: 12px; color: #9CA3AF; font-style: normal; }

.sec {
  margin: 18px 0 10px; padding-bottom: 6px; font-size: 12px; font-weight: 600;
  color: #6B7280; letter-spacing: .5px; border-bottom: 1px solid #E5E7EB;
}
.sec-with-btn { display: flex; align-items: center; justify-content: space-between; }

.cc-bills { margin: 6px 0 4px; display: flex; flex-direction: column; gap: 4px; }
.cc-bill {
  display: flex; align-items: center; gap: 8px; font-size: 12.5px;
  padding: 3px 8px; background: #F8FAFC; border-radius: 4px;
}
.cc-bill .mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; }
.contract-card {
  border: 1px solid #E5E7EB; border-radius: 6px; padding: 10px 12px; margin-bottom: 10px;
  background: #FCFDFE;
}
.cc-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.cc-head b { font-size: 13px; }
.cc-no { color: #9CA3AF; font-size: 12px; }
.cc-body { display: flex; gap: 18px; font-size: 12.5px; color: #4B5563; flex-wrap: wrap; }
.cc-foot { margin-top: 6px; display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.group-empty { padding: 8px 12px; color: #9CA3AF; font-size: 12.5px; }
.empty-tip { padding: 20px; text-align: center; color: #9CA3AF; font-size: 13px; }
.form-row { display: flex; gap: 12px; }
.form-row > .el-form-item { flex: 1; }
.drawer-head { font-size: 15px; font-weight: 600; }
.head-sub { margin-left: 6px; font-weight: 400; opacity: .8; font-size: 13px; }
</style>
