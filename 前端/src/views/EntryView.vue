<template>
  <div>
    <div class="panel">
      <div class="panel-hd">新建报销单</div>

      <div style="display:flex;gap:16px;padding:16px 16px 0;align-items:stretch">
        <!-- 拖拽区 -->
        <div
          class="drop"
          :class="{ over: dragging }"
          @click="pickFiles"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
        >
          <el-icon :size="22"><Plus /></el-icon>
          <div style="font-size:13px;font-weight:500;margin-top:6px">把发票拖到这里</div>
          <div style="font-size:11px;color:#9CA3AF;margin-top:2px">或点击选择文件<br>支持 PDF / OFD，可一次多张</div>
          <input ref="fileInput" type="file" accept=".pdf,.ofd" multiple hidden @change="onPick" />
        </div>

        <!-- 单据信息 -->
        <el-form :model="form" label-position="top" style="flex:1">
          <el-row :gutter="12">
            <el-col :span="8">
              <el-form-item label="报销人" required>
                <el-input v-model="form.person" placeholder="谁报销" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="报销日期">
                <el-date-picker v-model="form.reimburse_date" type="date"
                                value-format="YYYY-MM-DD" style="width:100%" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="报销单号">
                <el-input v-model="form.batch_no" placeholder="留空自动生成" />
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="本单备注">
                <el-input v-model="form.note" placeholder="选填，作用于这一整单" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>

      <!-- 本单发票 -->
      <div style="padding:8px 16px 0;display:flex;align-items:center;justify-content:space-between">
        <b style="font-size:13px">本单发票</b>
        <span class="text-muted" style="font-size:12px">
          共 {{ items.length }} 张　合计 {{ money(total) }}
        </span>
      </div>

      <div style="padding:8px 16px 0">
        <el-table :data="items" size="small" border empty-text="还没有发票，拖入或点「手工加一行」">
          <el-table-column type="index" width="46" />
          <el-table-column label="发票号码" width="210">
            <template #default="{ row }">
              <el-input v-model="row.invoice_no" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="开票日期" width="150">
            <template #default="{ row }">
              <el-date-picker v-model="row.invoice_date" type="date" size="small" value-format="YYYY-MM-DD" style="width:100%" />
            </template>
          </el-table-column>
          <el-table-column label="金额（含税）" width="130" align="center">
            <template #default="{ row }">
              <el-input v-model="row.amount" size="small" style="text-align:right" />
            </template>
          </el-table-column>
          <el-table-column label="销售方" min-width="220">
            <template #default="{ row }">
              <el-input v-model="row.seller" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="提示" width="140" align="center">
            <template #default="{ row }">
              <span v-if="row._dup" style="color:#C62828">重复登记</span>
              <span v-else-if="row._warn" style="color:#ED6C02">{{ row._warn }}</span>
              <span v-else style="color:#2E7D32">✓ 正常</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="items.splice($index, 1)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 收款信息 -->
      <div class="pay-bar">
        <b style="font-size:13px">收款信息</b>
        <span class="pay-sum">
          合计 <b :class="payDiff === 0 ? 'ok' : 'bad'">{{ money(payTotal) }}</b>
          / 单合计 {{ money(total) }}
          <span v-if="payDiff !== 0" class="bad">
            （{{ payDiff > 0 ? '还差' : '多了' }} {{ money(Math.abs(payDiff)) }}）
          </span>
          <span v-else class="ok"> ✓ 对上了</span>
        </span>
        <span class="pay-ops">
          <el-button size="small" @click="fillFromInvoices">带出收款人</el-button>
          <el-button size="small" @click="splitEvenly">按人平分</el-button>
          <el-button size="small" @click="addPayee">+ 添加一行</el-button>
        </span>
      </div>

      <div style="padding:0 16px">
        <el-table :data="payees" size="small" border empty-text="还没有收款人，点「+ 添加一行」或「带出收款人」">
          <el-table-column type="index" width="46" />
          <el-table-column label="收款人" width="200">
            <template #default="{ row }">
              <el-input v-model="row.name" size="small" placeholder="钱打给谁" />
            </template>
          </el-table-column>
          <el-table-column label="金额" width="150" align="center">
            <template #default="{ row }">
              <el-input v-model="row.amount" size="small" style="text-align:right"
                        placeholder="0.00" />
            </template>
          </el-table-column>
          <el-table-column label="备注">
            <template #default="{ row }">
              <el-input v-model="row.note" size="small" placeholder="选填，例如：垫付过路费" />
            </template>
          </el-table-column>
          <el-table-column width="72" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="removePayee($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div style="display:flex;align-items:center;gap:10px;padding:14px 16px 16px">
        <el-button @click="addRow">+ 手工加一行</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交报销单</el-button>
        <el-button @click="clearAll">清空</el-button>
        <span v-if="busy" class="text-muted" style="font-size:12.5px">{{ busy }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api } from '../api'
import { applyStats } from '../store'

const router = useRouter()

const fileInput = ref(null)
const dragging = ref(false)
const busy = ref('')
const submitting = ref(false)

const today = new Date()
const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

const form = reactive({
  person: '', payee: '', reimburse_date: iso, batch_no: '', note: ''
})
const items = ref([])
// 收款信息：一行 = 一个人 + 一笔金额
const payees = ref([])

const total = computed(() => items.value.reduce((s, x) => s + (Number(x.amount) || 0), 0))
const payTotal = computed(() =>
  payees.value.reduce((s, x) => s + (Number(x.amount) || 0), 0))
const payDiff = computed(() => Math.round((total.value - payTotal.value) * 100) / 100)

function addPayee() {
  payees.value.push({ name: '', amount: '', note: '' })
}
function removePayee(i) {
  payees.value.splice(i, 1)
}
/** 按发票上的收款人带出来（同名合并，金额累加） */
function fillFromInvoices() {
  if (!items.value.length) return ElMessage.warning('本单还没有发票')
  const map = new Map()
  for (const it of items.value) {
    const name = String(it.payee || form.person || '未填').trim()
    map.set(name, (map.get(name) || 0) + (Number(it.amount) || 0))
  }
  payees.value = [...map.entries()].map(([name, amt]) => ({
    name, amount: amt.toFixed(2), note: ''
  }))
}
/** 平分：最后一个人兜住零头，保证合计一分不差 */
function splitEvenly() {
  const n = Math.max(1, payees.value.length)
  if (!payees.value.length) {
    payees.value = [{ name: '', amount: '', note: '' }]
  }
  const totalCents = Math.round(total.value * 100)
  const base = Math.floor(totalCents / n)
  const rest = totalCents - base * n
  payees.value.forEach((p, i) => {
    const cents = base + (i === n - 1 ? rest : 0)
    p.amount = (cents / 100).toFixed(2)
  })
}

function money(v) {
  return '¥' + Number(v || 0).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function pickFiles() { fileInput.value?.click() }

function onPick(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  uploadFiles(files)
}

function onDrop(e) {
  dragging.value = false
  uploadFiles(Array.from(e.dataTransfer.files || []))
}

function addRow() {
  items.value.push({ invoice_no: '', invoice_date: '', amount: '', seller: '', token: '', _dup: false, _warn: '' })
}

async function uploadFiles(files) {
  const pdfs = files.filter(f => /\.(pdf|ofd)$/i.test(f.name))
  if (!pdfs.length) {
    ElMessage.warning('只支持 PDF / OFD 发票')
    return
  }
  for (let i = 0; i < pdfs.length; i++) {
    busy.value = `识别中 ${i + 1}/${pdfs.length}…`
    try {
      const r = await api.parse(pdfs[i])
      const f = r.fields || {}
      const dup = !!r.exists
      items.value.push({
        invoice_no: f.invoice_no || '',
        invoice_date: f.invoice_date || '',
        amount: f.amount || '',
        seller: f.seller || '',
        token: r.token || '',
        _dup: dup,
        _warn: dup ? '' : ''
      })
      if (dup) ElMessage.warning(`${r.file_name || '这张发票'} 之前已经登记过了`)
      // 第一张带出来的报销人/收款人，顺手填上
      if (!form.person && f.person) form.person = f.person
      if (!form.payee && f.payee) form.payee = f.payee
    } catch (e) {
      ElMessage.error(`${pdfs[i].name}：${e.message || '识别失败'}`)
    }
  }
  busy.value = ''
}

function clearAll() {
  items.value = []
  form.batch_no = ''
  form.note = ''
}

async function submit() {
  if (!form.person.trim()) return ElMessage.warning('请填写报销人')
  if (!items.value.length) return ElMessage.warning('本单还没有发票')
  if (items.value.some(x => !String(x.invoice_no).trim())) {
    return ElMessage.warning('有发票号码是空的，请补上')
  }
  // 收款信息：必须有、姓名必须有、金额必须和单合计一分不差
  if (!payees.value.length) {
    return ElMessage.warning('请填写收款信息：钱付给谁、每个人多少')
  }
  if (payees.value.some(p => !String(p.name).trim())) {
    return ElMessage.warning('有收款人没填姓名')
  }
  if (payees.value.some(p => !(Number(p.amount) > 0))) {
    return ElMessage.warning('有收款人的金额还没填')
  }
  if (payDiff.value !== 0) {
    return ElMessage.warning(
      `收款合计 ${money(payTotal.value)}，发票合计 ${money(total.value)}，` +
      `${payDiff.value > 0 ? '还差' : '多了'} ${money(Math.abs(payDiff.value))}`)
  }

  submitting.value = true
  try {
    const r = await api.batchAdd({
      person: form.person.trim(),
      payee: String(payees.value[0]?.name || form.person).trim(),
      payees: payees.value.map(p => ({
        name: String(p.name).trim(),
        amount: Number(p.amount) || 0,
        note: String(p.note || '').trim()
      })),
      reimburse_date: form.reimburse_date || '',
      batch_no: form.batch_no.trim(),
      note: form.note.trim(),
      items: items.value.map(({ _dup, _warn, ...rest }) => rest)
    })
    applyStats(r.stats)
    ElMessage.success(`已提交报销单 ${r.batch_no || ''}`)
    items.value = []
    payees.value = []
    form.batch_no = ''
    form.note = ''
    router.push({ name: 'ledger' })
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.pay-bar {
  display: flex; align-items: center; gap: 14px;
  padding: 16px 16px 8px; flex-wrap: wrap;
}
.pay-sum { font-size: 12.5px; color: #6B7280; }
.pay-sum .ok { color: #16A34A; }
.pay-sum .bad { color: #DC2626; }
.pay-ops { margin-left: auto; display: flex; gap: 8px; }
.drop {
  flex: 0 0 240px; height: 150px;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  border: 2px dashed #D1D5DB; border-radius: 4px;
  background: #F9FAFB; color: #6B7280; cursor: pointer;
  transition: all .15s;
}
.drop:hover, .drop.over { border-color: #1B4F8C; background: #E3F0FB; color: #1B4F8C; }
</style>
