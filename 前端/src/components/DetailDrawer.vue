<template>
  <el-drawer
    :model-value="modelValue"
    class="detail-drawer"
    direction="rtl"
    size="620px"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="drawer-head">
        <span>{{ type === 'batch' ? '报销单详情' : '发票详情' }}</span>
        <span v-if="type === 'batch' && batch" class="head-sub">· {{ batch.batch_no }}</span>
        <span v-else-if="type !== 'batch' && invoice" class="head-sub">· {{ invoice.invoice_no }}</span>
      </div>
    </template>
    <div v-loading="loading">
      <!-- ============ 报销单 ============ -->
      <template v-if="type === 'batch' && batch">
        <div class="drawer-actions">
          <el-button type="primary" plain size="small" @click="printBatch">
            打印报销单（照着抄）
          </el-button>
        </div>
        <div class="amount-hero">
          <div class="hero-money">{{ money(batch.amount) }}</div>
          <div class="hero-cap">本单合计 · {{ batch.count }} 张</div>
        </div>

        <el-alert
          v-if="batch.locked"
          type="success"
          :closable="false"
          show-icon
          style="margin-bottom:12px"
          title="这张单已报销，已锁定"
          description="任何人都不能再修改。要改先点「改回未报销」解锁（仅管理员）。"
        />

        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="报销单号">
            <span class="mono">{{ batch.batch_no }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="审核状态">
            <el-tag size="small" :type="reviewType(batch.review_status)">{{ reviewText(batch.review_status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="报销人">{{ batch.person || '—' }}</el-descriptions-item>
          <el-descriptions-item label="收款人">{{ batch.payee || '—' }}</el-descriptions-item>
          <el-descriptions-item label="报销日期">{{ batch.reimburse_date || '—' }}</el-descriptions-item>
          <el-descriptions-item label="报销部门">{{ batch.dept_name || '—' }}</el-descriptions-item>
          <el-descriptions-item label="报销项目">{{ batch.project_name || '—' }}</el-descriptions-item>
          <el-descriptions-item label="报销状态">
            <el-tag size="small" :type="Number(batch.status) === 1 ? 'success' : 'danger'">
              {{ Number(batch.status) === 1 ? '已报销' : '未报销' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="本单合计">
            <b class="money brand-strong">{{ money(batch.amount) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="发票张数">{{ batch.count }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ batch.note || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="batch.review_note" label="审核意见" :span="2">
            {{ batch.review_note }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="sec">收款信息（{{ batch.payees?.length || 0 }} 人）</div>
        <el-table :data="batch.payees || []" size="small" border empty-text="没有收款记录">
          <el-table-column type="index" width="46" />
          <el-table-column prop="name" label="收款人" width="140" />
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }"><span class="money">{{ money(row.amount) }}</span></template>
          </el-table-column>
          <el-table-column prop="note" label="备注" />
        </el-table>
        <div class="pay-total">
          收款合计 <b>{{ money(batch.pay_total) }}</b>
          <span v-if="Math.abs((batch.pay_total || 0) - (batch.amount || 0)) >= 0.005" class="bad">
            ！与发票合计 {{ money(batch.amount) }} 不符
          </span>
          <span v-else class="ok">✓ 与发票合计一致</span>
        </div>

        <div class="sec sec-with-btn">
          本单明细（{{ batch.invoices?.length || 0 }} 张）
          <span class="audit-sum">
            查验：通过 {{ batchAudit.pass }} / 未查 {{ batchAudit.todo }}
          </span>
        </div>
        <div v-if="auditing" class="audit-tip">
          <div class="audit-line"><span class="dot" />{{ auditTip || '正在启动查验…' }}</div>
          <el-progress
            :percentage="auditPercent"
            :stroke-width="6"
            :show-text="false"
            :status="auditPercent >= 100 ? 'success' : ''"
          />
        </div>
        <el-table :data="batch.invoices || []" size="small" border>
          <el-table-column type="index" width="44" />
          <el-table-column prop="invoice_no" label="发票号码" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.invoice_no }}</span></template>
          </el-table-column>
          <el-table-column prop="invoice_date" label="开票日期" width="104" />
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }"><span class="money">{{ money(row.amount) }}</span></template>
          </el-table-column>
          <el-table-column label="查验" width="82" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="auditType(row.audit_status)">
                {{ auditText(row.audit_status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="86" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="Number(row.status) === 1 ? 'success' : 'danger'">
                {{ Number(row.status) === 1 ? '已报销' : '未报销' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>


      </template>

      <!-- ============ 发票 ============ -->
      <template v-else-if="invoice">
        <div class="amount-hero">
          <div class="hero-money">{{ money(invoice.amount) }}</div>
          <div class="hero-cap">价税合计（含税）</div>
        </div>

        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="发票号码" :span="2">
            <span class="mono">{{ invoice.invoice_no }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="开票日期">{{ invoice.invoice_date || '—' }}</el-descriptions-item>
          <el-descriptions-item label="金额（含税）">
            <b class="money brand-strong">{{ money(invoice.amount) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="不含税">{{ money(invoice.amount_net) }}</el-descriptions-item>
          <el-descriptions-item label="税额">{{ money(invoice.tax) }}</el-descriptions-item>
          <el-descriptions-item label="销售方" :span="2">{{ invoice.seller || '—' }}</el-descriptions-item>
          <el-descriptions-item label="购买方" :span="2">{{ invoice.buyer || '—' }}</el-descriptions-item>
          <el-descriptions-item label="项目" :span="2">{{ invoice.category || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="sec">报销信息</div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="报销单号">
            <el-link v-if="invoice.batch_id" type="primary" @click="switchToBatch(invoice.batch_id)">
              {{ invoice.batch_no }}
            </el-link>
            <span v-else class="text-muted">未归单</span>
          </el-descriptions-item>
          <el-descriptions-item label="报销人">{{ invoice.person || '—' }}</el-descriptions-item>
          <el-descriptions-item label="收款人">
            {{ invoice.payee || '—' }}
            <el-tag v-if="invoice.payee && invoice.person && invoice.payee !== invoice.person"
                    size="small" type="warning" style="margin-left:6px">与报销人不同</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="报销日期">{{ invoice.reimburse_date || '—' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ invoice.note || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="sec sec-with-btn">
          查验
          <el-button
            v-if="can('audit')"
            size="small"
            type="primary"
            plain
            :loading="auditing"
            :disabled="auditing"
            @click="auditOne"
          >{{ auditing ? '查验中…' : '查验这张' }}</el-button>
        </div>
        <div v-if="auditing" class="audit-tip">
          <div class="audit-line"><span class="dot" />{{ auditTip || '正在启动查验…' }}</div>
          <el-progress
            :percentage="auditPercent"
            :stroke-width="6"
            :show-text="false"
            :status="auditPercent >= 100 ? 'success' : ''"
          />
        </div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="查验状态">
            <el-tag size="small" :type="auditType(invoice.audit_status)">{{ auditText(invoice.audit_status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="查验时间">{{ invoice.audit_at || '—' }}</el-descriptions-item>
          <el-descriptions-item label="说明" :span="2">{{ invoice.audit_msg || '—' }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="invoice.audit_shot">
          <el-image
            :src="shotUrl"
            :preview-src-list="[shotUrl]"
            fit="contain"
            style="width:100%;margin-top:10px;border:1px solid #E5E7EB;border-radius:4px;cursor:zoom-in"
          />
          <div class="text-muted" style="font-size:12px;margin-top:6px">点图片看大图</div>
        </template>
      </template>
    </div>

    <!-- 报销单底部操作栏 -->
    <template v-if="type === 'batch' && batch" #footer>
      <div class="drawer-foot">
        <el-button
          v-if="can('audit')"
          :loading="auditing"
          :disabled="auditing"
          @click="auditBatch"
        >查验本单</el-button>
        <el-button
          v-if="can('review') && batch?.review_status !== 'approved'"
          type="danger" plain @click="startReturn"
        >{{ isMyOwnSubmit ? '修改' : '退回修改' }}</el-button>
        <el-button
          v-if="can('review') && batch?.review_status !== 'approved'"
          type="primary" @click="review('approve')"
        >审核通过</el-button>
        <el-button
          v-if="can('review') && batch?.review_status === 'approved'"
          @click="toggleBatchStatus"
        >{{ Number(batch?.status) === 1 ? '改回未报销' : '整单标记已报销' }}</el-button>
        <el-button
          v-if="batch?.review_status === 'returned'"
          type="primary" @click="resubmit"
        >修改完成，重新提交</el-button>
        <el-button
          v-if="canDelete"
          type="danger" plain @click="removeBatch"
        >{{ batch?.review_status === 'submitted' ? '撤回并删除' : '删除整单' }}</el-button>
      </div>
    </template>

    <!-- 发票底部操作栏 -->
    <template v-if="type !== 'batch'" #footer>
      <div class="drawer-foot">
        <el-button :disabled="!invoice?.file_rel" @click="viewOriginal">查看原件</el-button>
        <el-button :disabled="!invoice?.batch_id" @click="switchToBatch(invoice.batch_id)">
          打开报销单
        </el-button>
        <el-button type="primary" :disabled="!canEditInvoice" @click="openEdit">修改</el-button>
      </div>
    </template>

    <!-- 改报销单弹窗 -->
    <el-dialog v-model="batchEdit" title="修改报销单" width="460px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="报销人">
          <el-input v-model="bform.person" />
        </el-form-item>
        <el-form-item label="收款人">
          <el-input v-model="bform.payee" />
        </el-form-item>
        <el-form-item label="报销日期">
          <el-date-picker v-model="bform.reimburse_date" type="date"
                          value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="bform.note" type="textarea" :rows="2" />
        </el-form-item>

        <el-form-item label="收款信息（合计必须等于单合计）">
          <div style="width:100%">
            <div v-for="(p, i) in bform.payees" :key="i" class="payee-row">
              <el-input v-model="p.name" placeholder="收款人" style="width:110px" />
              <el-input v-model="p.amount" placeholder="金额" style="width:100px" />
              <el-input v-model="p.note" placeholder="备注" />
              <el-button link type="danger" @click="bform.payees.splice(i, 1)">删</el-button>
            </div>
            <div class="payee-foot">
              <el-button size="small" @click="bform.payees.push({ name: '', amount: '', note: '' })">
                + 添加
              </el-button>
              <el-button size="small" @click="splitBatchEvenly">平分</el-button>
              <span class="text-muted" style="font-size:12px">
                合计 {{ money(bPayTotal) }} / 单合计 {{ money(batch?.amount) }}
                <b v-if="Math.abs(bPayTotal - (batch?.amount || 0)) >= 0.005" class="bad">
                  （差 {{ money(Math.abs((batch?.amount || 0) - bPayTotal)) }}）
                </b>
              </span>
            </div>
          </div>
        </el-form-item>

        <div class="text-muted" style="font-size:12px">
          报销人 / 收款人 / 报销日期会同时更新到单里所有发票。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="batchEdit = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBatch">保存</el-button>
      </template>
    </el-dialog>

    <!-- 修改弹窗 -->
    <el-dialog v-model="editVisible" title="修改发票" width="560px" append-to-body>
      <el-form label-position="top" class="edit-form">
        <div class="form-row">
          <el-form-item label="开票日期">
            <el-date-picker v-model="form.invoice_date" type="date" value-format="YYYY-MM-DD"
                            style="width:100%" />
          </el-form-item>
          <el-form-item label="金额（含税）">
            <el-input v-model="form.amount" placeholder="0.00" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="不含税金额">
            <el-input v-model="form.amount_net" placeholder="0.00" />
          </el-form-item>
          <el-form-item label="税额">
            <el-input v-model="form.tax" placeholder="0.00" />
          </el-form-item>
        </div>
        <el-form-item label="销售方">
          <el-input v-model="form.seller" />
        </el-form-item>
        <el-form-item label="购买方">
          <el-input v-model="form.buyer" />
        </el-form-item>
        <el-form-item label="项目">
          <el-input v-model="form.category" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="报销人">
            <el-input v-model="form.person" />
          </el-form-item>
          <el-form-item label="收款人">
            <el-input v-model="form.payee" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="报销日期">
            <el-date-picker v-model="form.reimburse_date" type="date" value-format="YYYY-MM-DD"
                            style="width:100%" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.note" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 退回弹窗 -->
    <el-dialog v-model="reviewDialog" title="退回修改" width="480px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="退回原因（提交人会看到）">
          <el-input v-model="reviewNote" type="textarea" :rows="3" placeholder="例如：金额与发票不符" />
        </el-form-item>
        <el-form-item label="勾选有问题的发票">
          <el-checkbox-group v-model="issueIds">
            <div v-for="i in batch?.invoices || []" :key="i.id" style="margin-bottom:6px">
              <el-checkbox :value="i.id">
                <span class="mono">{{ i.invoice_no }}</span> · {{ money(i.amount) }}
              </el-checkbox>
            </div>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialog = false">取消</el-button>
        <el-button type="danger" @click="doReview">确认退回</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { state, isAdmin, can } from '../store'

const props = defineProps({
  modelValue: Boolean,
  type: { type: String, default: 'invoice' },
  id: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue', 'changed', 'open-batch'])

const loading = ref(false)
const batch = ref(null)
const invoice = ref(null)
const reviewDialog = ref(false)
const reviewNote = ref('')
const issueIds = ref([])
const editVisible = ref(false)
const batchEdit = ref(false)
const bform = ref({})
const saving = ref(false)
const form = ref({})
const auditing = ref(false)
const auditTip = ref('')
const auditPercent = ref(0)
let auditTimer = null

const shotUrl = computed(() => invoice.value?.id
  ? api.auditShotUrl(invoice.value.id, invoice.value.audit_at)
  : '')

const canDelete = computed(() => {
  const b = batch.value
  if (!b || !state.me) return false
  if (Number(b.owner_id) !== Number(state.me.id)) return false
  return b.review_status !== 'approved'
})

/** 打印纸质报销单模板（照着抄到正式单子上） */
function printBatch() {
  const b = batch.value
  if (!b || !b.id) return
  window.open('/batch-print/' + b.id, '_blank')
}

function money(v) {
  return '¥' + Number(v || 0).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}
function reviewText(s) {
  return { submitted: '待审核', returned: '已退回', approved: '已通过' }[s] || s
}
function reviewType(s) {
  return { submitted: 'warning', returned: 'danger', approved: 'success' }[s] || 'info'
}
function auditText(s) {
  return { 0: '未查验', 1: '查验通过', 2: '查验不通过', 3: '无需查验' }[Number(s)] || '未查验'
}
function auditType(s) {
  return { 0: 'info', 1: 'success', 2: 'danger', 3: 'info' }[Number(s)] || 'info'
}

async function load() {
  if (!props.id) return
  loading.value = true
  batch.value = null
  invoice.value = null
  try {
    if (props.type === 'batch') {
      const r = await api.batch(props.id)
      batch.value = r.batch
    } else {
      const r = await api.list({ limit: 5000 })
      invoice.value = (r.records || []).find(x => Number(x.id) === Number(props.id)) || null
    }
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  } finally {
    loading.value = false
  }
}

function switchToBatch(id) {
  emit('update:modelValue', false)
  emit('open-batch', id)
}

async function review(action) {
  try {
    await api.batchReview({ id: batch.value?.id, action, note: '', issues: [] })
    ElMessage.success(action === 'approve' ? '已通过' : '已退回')
    emit('update:modelValue', false)
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

/**
 * 单张查验：只查抽屉里这一张，进度就地显示，查完自动刷新。
 * 如果别处正在查（后台任务只允许一个），后端会返回 409，这里如实提示。
 */
async function auditOne() {
  if (auditing.value || !invoice.value) return
  auditing.value = true
  auditPercent.value = 0
  auditTip.value = '正在启动浏览器…'
  try {
    await api.auditStart([invoice.value.id])
    pollOne()
  } catch (e) {
    auditing.value = false
    auditPercent.value = 0
    auditTip.value = ''
    ElMessage.error(e.message || '启动查验失败')
  }
}

/**
 * 单张查验时后端 done 一直是 0（跑完才 +1），进度条会一直不动。
 * 这里按日志里的阶段给个百分比，看起来才是"在往前走"。
 */
function stagePercent(text) {
  const s = String(text || '')
  if (/启动浏览器|正在启动/.test(s)) return 8
  if (/打开查验页面/.test(s)) return 18
  if (/页面已打开/.test(s)) return 30
  if (/填写/.test(s)) return 45
  if (/提交查验/.test(s)) return 88
  if (/查验通过|读取结果/.test(s)) return 95
  if (/任务结束/.test(s)) return 100
  const m = s.match(/识别验证码.*第\s*(\d+)\s*次/)
  if (m) return Math.min(85, 52 + Number(m[1]) * 5)
  if (/识别验证码/.test(s)) return 52
  return 0
}

async function pollOne() {
  clearTimeout(auditTimer)
  try {
    const r = await api.auditStatus()
    const st = r.state || r.status || r
    const logs = st.log || []
    if (logs.length) {
      const last = logs[logs.length - 1].text || '查验中…'
      auditTip.value = last
      const p = stagePercent(last)
      if (p > auditPercent.value) auditPercent.value = p
    }
    // 顺带跑多张时，用整体完成度兜底
    const total = Number(st.total || 0)
    const done = Number(st.done || 0)
    if (total > 1) {
      auditPercent.value = Math.max(auditPercent.value,
        Math.min(99, Math.round(done * 100 / total)))
    }
    if (st.running) {
      auditTimer = setTimeout(pollOne, 1200)
      return
    }
    auditPercent.value = 100
    auditing.value = false
    auditTip.value = ''
    await load()
    emit('changed')
    ElMessage.success('查验结束')
  } catch (e) {
    auditing.value = false
    auditTip.value = ''
  }
}

// 抽屉打开、或换了一条记录，都要重新拉数据
watch(
  () => [props.modelValue, props.type, props.id],
  ([open]) => {
    if (open && props.id) load()
  },
  { immediate: true }
)

onUnmounted(() => clearTimeout(auditTimer))

// 这单是不是自己提交的
const isMyOwnSubmit = computed(() => {
  const b = batch.value
  if (!b || !state.me) return false
  return Number(b.owner_id) === Number(state.me.id)
})

/**
 * 点「退回修改」：
 * 自己提交的单不用退回——管理员本来就能直接改，退回反而多绕一圈。
 */
async function startReturn() {
  if (isMyOwnSubmit.value) {
    try {
      await ElMessageBox.alert(
        '这是你自己提交的报销单，不需要退回。\n\n点下面的「知道了」，直接点「修改」把报销人、收款人、日期或备注改掉就行，改完不用重新提交。',
        '自己提交的单不用退回',
        { confirmButtonText: '知道了', type: 'info' }
      )
    } catch (e) { /* 关掉就算了 */ }
    openBatchEdit()
    return
  }
  reviewNote.value = ''
  issueIds.value = []
  reviewDialog.value = true
}

const bPayTotal = computed(() => (bform.value.payees || [])
  .reduce((s, p) => s + (Number(p.amount) || 0), 0))

function splitBatchEvenly() {
  const list = bform.value.payees || []
  const n = Math.max(1, list.length)
  const cents = Math.round((batch.value?.amount || 0) * 100)
  const base = Math.floor(cents / n)
  const rest = cents - base * n
  list.forEach((p, i) => {
    p.amount = ((base + (i === n - 1 ? rest : 0)) / 100).toFixed(2)
  })
}

function openBatchEdit() {
  const b = batch.value
  bform.value = {
    payees: (b.payees || []).map(p => ({
      name: p.name, amount: Number(p.amount || 0).toFixed(2), note: p.note || ''
    })),
    person: b.person || '',
    payee: b.payee || '',
    reimburse_date: b.reimburse_date || '',
    note: b.note || '',
      dept_name: b.dept_name || '',
      project_name: b.project_name || ''
  }
    loadDeptOptions()
  batchEdit.value = true
}

async function saveBatch() {
  saving.value = true
  try {
    const diff = Math.abs(bPayTotal.value - (batch.value?.amount || 0))
    if (diff >= 0.005) {
      ElMessage.warning(`收款合计 ${money(bPayTotal.value)}，单合计 ${money(batch.value?.amount)}，差了 ${money(diff)}`)
      return
    }
    await api.batchUpdate({ id: batch.value?.id, ...bform.value })
    ElMessage.success('已保存')
    batchEdit.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 整单查验：把这一单里「未查验 / 上次没通过」的发票一起送去查 */
async function auditBatch() {
  if (auditing.value || !batch.value) return
  const ids = (batch.value.invoices || [])
    .filter(i => [0, 2].includes(Number(i.audit_status || 0)))
    .map(i => i.id)
  if (!ids.length) {
    ElMessage.success('这一单的发票都查验过了')
    return
  }
  auditing.value = true
  auditPercent.value = 0
  auditTip.value = '正在启动浏览器…'
  try {
    await api.auditStart(ids)
    pollOne()
  } catch (e) {
    auditing.value = false
    auditPercent.value = 0
    auditTip.value = ''
    ElMessage.error(e.message || '启动查验失败')
  }
}

// 本单的查验概况：通过几张、还没查几张
const batchAudit = computed(() => {
  const list = batch.value?.invoices || []
  let pass = 0
  let todo = 0
  for (const i of list) {
    const s = Number(i.audit_status || 0)
    if (s === 1) pass++
    else if (s === 0 || s === 2) todo++
  }
  return { pass, todo }
})

// 能不能改：管理员只要不是已通过就能改；普通用户只有自己「已退回」的单能改
const canEditInvoice = computed(() => {
  const r = invoice.value
  if (!r || !state.me) return false
  // 散票（没归单）不受单据状态限制，谁的上谁能改
  if (!r.batch_id) {
    return isAdmin.value || Number(r.owner_id) === Number(state.me.id)
  }
  const st = r.batch_review
  if (isAdmin.value) return st !== 'approved'
  return Number(r.owner_id) === Number(state.me.id) && st === 'returned'
})

function viewOriginal() {
  if (!invoice.value?.file_rel) {
    ElMessage.info('这张发票没有原件')
    return
  }
  window.open(`/api/file?id=${invoice.value.id}`, '_blank')
}

function openEdit() {
  const r = invoice.value
  form.value = {
    invoice_date: r.invoice_date || '',
    amount: r.amount ?? '',
    amount_net: r.amount_net ?? '',
    tax: r.tax ?? '',
    seller: r.seller || '',
    buyer: r.buyer || '',
    category: r.category || '',
    person: r.person || '',
    payee: r.payee || '',
    reimburse_date: r.reimburse_date || '',
    note: r.note || ''
  }
  editVisible.value = true
}

async function saveEdit() {
  saving.value = true
  try {
    await api.update({ id: invoice.value.id, ...form.value })
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function doReview() {
  try {
    await api.batchReview({
      id: batch.value?.id,
      action: 'return',
      note: reviewNote.value,
      issues: issueIds.value
    })
    ElMessage.success('已退回')
    reviewDialog.value = false
    emit('update:modelValue', false)
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function resubmit() {
  try {
    await api.batchResubmit(batch.value?.id)
    ElMessage.success('已重新提交')
    emit('update:modelValue', false)
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function toggleBatchStatus() {
  const next = Number(batch.value.status) === 1 ? 0 : 1
  try {
    await api.batchStatus(batch.value?.id, next)
    batch.value.status = next
    ElMessage.success('已更新')
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function removeBatch() {
  const label = batch.value.review_status === 'submitted' ? '撤回并删除' : '删除整单'
  try {
    await ElMessageBox.confirm(
      `确定${label}「${batch.value.batch_no}」吗？单里的发票会一起删掉。`,
      label, { type: 'warning', confirmButtonText: label }
    )
  } catch (e) { return }
  try {
    const r = await api.batchDelete(batch.value?.id)
    ElMessage.success(`已删除（${r.deleted || 0} 张）`)
    emit('update:modelValue', false)
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}
</script>

<style scoped>
.drawer-actions {
  display: flex; justify-content: flex-end; margin-bottom: 10px;
}
/* 标题栏配色在 main.css 里（teleport 出去的节点 scoped 管不到） */
.drawer-head { font-size: 15px; font-weight: 600; letter-spacing: .5px; }

/* 顶部金额横幅 */
.amount-hero {
  margin: 0 0 16px; padding: 18px 20px;
  border-radius: 6px; text-align: center; color: #fff;
  background: linear-gradient(135deg, var(--el-color-primary), var(--el-color-primary-light-3));
  box-shadow: 0 4px 12px rgba(27, 79, 140, .18);
}
.hero-money { font-size: 30px; font-weight: 700; line-height: 1.15; }
.hero-cap { margin-top: 4px; font-size: 12px; opacity: .85; letter-spacing: 1px; }

/* 底部按钮栏 */
.drawer-foot { display: flex; gap: 10px; justify-content: flex-end; }
.drawer-foot .el-button { margin-left: 0; }

/* 修改弹窗两列 */
.edit-form .form-row { display: flex; gap: 12px; }
.edit-form .form-row > .el-form-item { flex: 1; }

.sec {
  margin: 18px 0 10px; padding-bottom: 6px;
  font-size: 12px; font-weight: 600; color: #6B7280; letter-spacing: .5px;
  border-bottom: 1px solid #E5E7EB;
}
/* 标题右侧放按钮：两行高度对齐，不把标题挤歪 */
.pay-total {
  margin: 8px 0 4px; font-size: 12.5px; color: #6B7280; text-align: right;
}
.pay-total .ok { color: #16A34A; margin-left: 8px; }
.pay-total .bad { color: #DC2626; margin-left: 8px; }
.payee-row { display: flex; gap: 6px; margin-bottom: 6px; align-items: center; }
.payee-foot { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.bad { color: #DC2626; }
.sec-with-btn {
  display: flex; align-items: center; justify-content: space-between;
  padding-bottom: 6px;
}
.audit-sum { font-weight: 400; color: #9CA3AF; letter-spacing: 0; }
.head-sub { margin-left: 6px; font-weight: 400; opacity: .8; font-size: 13px; }
.audit-tip {
  margin: -4px 0 10px; padding: 9px 10px 10px;
  font-size: 12px; color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 4px;
}
.audit-line { margin-bottom: 7px; line-height: 1.4; }
.audit-tip .dot {
  display: inline-block; width: 6px; height: 6px; margin-right: 6px;
  border-radius: 50%; background: var(--el-color-primary);
  animation: audit-pulse 1s ease-in-out infinite; vertical-align: middle;
}
@keyframes audit-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: .25; }
}
</style>
