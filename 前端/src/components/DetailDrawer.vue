<template>
  <el-drawer
    :model-value="modelValue"
    :title="type === 'batch' ? '报销单详情' : '发票详情'"
    direction="rtl"
    size="620px"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="load"
  >
    <div v-loading="loading">
      <!-- ============ 报销单 ============ -->
      <template v-if="type === 'batch' && batch">
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

        <div class="sec">本单明细（{{ batch.invoices?.length || 0 }} 张）</div>
        <el-table :data="batch.invoices || []" size="small" border>
          <el-table-column type="index" width="44" />
          <el-table-column prop="invoice_no" label="发票号码" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.invoice_no }}</span></template>
          </el-table-column>
          <el-table-column prop="invoice_date" label="开票日期" width="104" />
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }"><span class="money">{{ money(row.amount) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="86" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="Number(row.status) === 1 ? 'success' : 'danger'">
                {{ Number(row.status) === 1 ? '已报销' : '未报销' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>

        <div class="sec">操作</div>
        <el-space wrap>
          <el-button
            v-if="isAdmin && batch.review_status !== 'approved'"
            type="success" @click="review('approve')"
          >审核通过</el-button>
          <el-button
            v-if="isAdmin && batch.review_status !== 'approved'"
            type="danger" plain @click="reviewDialog = true"
          >退回修改</el-button>
          <el-button
            v-if="batch.review_status === 'submitted' && isAdmin"
            @click="toggleBatchStatus"
          >{{ Number(batch.status) === 1 ? '整单改回未报销' : '整单标记已报销' }}</el-button>
          <el-button
            v-if="batch.review_status === 'returned'"
            type="primary" @click="resubmit"
          >修改完成，重新提交</el-button>
          <el-button
            v-if="canDelete"
            type="danger" plain @click="removeBatch"
          >{{ batch.review_status === 'submitted' ? '撤回并删除' : '删除整单' }}</el-button>
        </el-space>
      </template>

      <!-- ============ 发票 ============ -->
      <template v-else-if="invoice">
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

        <div class="sec">查验</div>
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
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { state, isAdmin } from '../store'

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

const shotUrl = computed(() => invoice.value?.id
  ? api.auditShotUrl(invoice.value.id, invoice.value.audit_at)
  : '')

const canDelete = computed(() => {
  const b = batch.value
  if (!b || !state.me) return false
  if (Number(b.owner_id) !== Number(state.me.id)) return false
  return b.review_status !== 'approved'
})

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
    await api.batchReview({ id: batch.value.id, action, note: '', issues: [] })
    ElMessage.success(action === 'approve' ? '已通过' : '已退回')
    emit('update:modelValue', false)
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function doReview() {
  try {
    await api.batchReview({
      id: batch.value.id,
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
    await api.batchResubmit(batch.value.id)
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
    await api.batchStatus(batch.value.id, next)
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
    const r = await api.batchDelete(batch.value.id)
    ElMessage.success(`已删除（${r.deleted || 0} 张）`)
    emit('update:modelValue', false)
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}
</script>

<style scoped>
.sec {
  margin: 18px 0 10px; padding-bottom: 6px;
  font-size: 12px; font-weight: 600; color: #6B7280; letter-spacing: .5px;
  border-bottom: 1px solid #E5E7EB;
}
</style>
