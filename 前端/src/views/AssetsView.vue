<template>
  <div class="fill-page">
    <div class="panel">
      <div class="panel-hd">
        <span>固定资产</span>
        <span class="sub">
          共 {{ stats.total }} 台 ·
          在用 {{ stats.in_use }} ·
          闲置 <b style="color:#C62828">{{ stats.idle }}</b> ·
          公用 {{ stats.public }}
          <template v-if="stats.incomplete">
            · <b style="color:#E6A23C">{{ stats.incomplete }} 条待完善</b>
          </template>
        </span>
        <div style="margin-left:auto;display:flex;gap:8px;flex-wrap:wrap">
          <div class="filter-row" style="width:560px">
            <el-input v-model="q" size="small" placeholder="编号 / 名称 / 使用人 / IP / 序列号"
                      clearable @keyup.enter="load" @clear="load" />
            <el-select v-model="fStatus" size="small" @change="load">
              <el-option value="all" label="全部状态" />
              <el-option value="在用" label="在用" />
              <el-option value="闲置" label="闲置" />
              <el-option value="公用" label="公用" />
              <el-option value="报废" label="报废" />
              <el-option value="维修" label="维修" />
            </el-select>
            <el-select v-model="fCategory" size="small" clearable
                       placeholder="全部类别" @change="load">
              <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
            </el-select>
          </div>
          <el-checkbox v-model="onlyIncomplete" style="margin:0 2px">
            只看待完善{{ stats.incomplete ? '(' + stats.incomplete + ')' : '' }}
          </el-checkbox>
          <el-button @click="load">刷新</el-button>
          <el-button :disabled="!selected.length" @click="openBatch">
            批量补年限{{ selected.length ? '(' + selected.length + ')' : '' }}
          </el-button>
          <el-button @click="redetect">重判状态</el-button>
          <el-button @click="exportExcel">导出</el-button>
          <el-button type="primary" @click="openNew">+ 登记资产</el-button>
        </div>
      </div>

      <div class="asset-sum">
        <div class="as-item"><span>原值合计</span><b class="money">{{ money(stats.price) }}</b></div>
        <div class="as-item"><span>净值合计</span><b class="money">{{ money(stats.net) }}</b></div>
      </div>

      <el-table :data="pagedList" v-loading="loading" size="small" border
                max-height="calc(100vh - 300px)" @selection-change="onSelect">
        <el-table-column type="selection" width="42" :selectable="() => true" />
        <el-table-column prop="code" label="资产编号" width="130">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="名称" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tooltip v-if="row.incomplete" placement="top">
              <template #content>
                这条信息不全，折旧算不准：{{ (row.missing_fields || []).join('、') }}
              </template>
              <el-icon class="warn-ico"><WarningFilled /></el-icon>
            </el-tooltip>
            {{ row.name }}
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="90" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="user_name" label="使用人" width="140" show-overflow-tooltip />
        <el-table-column prop="dept" label="部门" width="110" show-overflow-tooltip />
        <el-table-column prop="location" label="位置" width="110" show-overflow-tooltip />
        <el-table-column prop="buy_date" label="采购日期" width="105" />
        <el-table-column label="原值" width="105" align="right">
          <template #default="{ row }"><span class="money">{{ money(row.price) }}</span></template>
        </el-table-column>
        <el-table-column label="累计折旧" width="105" align="right">
          <template #default="{ row }"><span class="money">{{ money(row.acc_dep) }}</span></template>
        </el-table-column>
        <el-table-column label="净值" width="105" align="right">
          <template #default="{ row }"><span class="money">{{ money(row.net_value) }}</span></template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="130" show-overflow-tooltip />
        <el-table-column label="操作" width="130" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="isAdmin" link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize"
                       :page-sizes="[20, 50, 100, 200]" :total="shown.length"
                       layout="total, sizes, prev, pager, next, jumper" background />
      </div>
    </div>

    <!-- 批量补年限 -->
    <el-dialog v-model="batchDlg" title="批量补使用年限" width="420px">
      <div style="margin-bottom:14px;font-size:13px;color:#4B5563">
        已选 <b>{{ selected.length }}</b> 台资产，设置使用年限后，月折旧和净值会自动重算。
      </div>
      <el-form label-width="90px">
        <el-form-item label="使用年限">
          <el-input v-model="batchYears" style="width:130px">
            <template #append>年</template>
          </el-input>
        </el-form-item>
      </el-form>
      <div style="font-size:12px;color:#9AA6B6;margin-left:90px">
        电脑、硬盘一般是 5 年
      </div>
      <template #footer>
        <el-button @click="batchDlg = false">取消</el-button>
        <el-button type="primary" :loading="batchSaving" @click="saveBatch">确定</el-button>
      </template>
    </el-dialog>

    <!-- 登记/编辑 -->
    <el-dialog v-model="dlg" :title="form.id ? '编辑资产' : '登记资产'" width="760px">
      <el-form :model="form" label-width="100px" label-position="right">
        <div class="sec">基本信息</div>
        <el-row :gutter="10">
          <el-col :span="8"><el-form-item label="资产编号"><el-input v-model="form.code" placeholder="留空自动生成" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="类型"><el-input v-model="form.type" placeholder="电脑 / 硬盘" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="10">
          <el-col :span="8"><el-form-item label="类别"><el-input v-model="form.category" placeholder="电子设备" /></el-form-item></el-col>
          <el-col :span="8">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width:100%">
                <el-option v-for="s in ['在用','闲置','公用','报废','维修']" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="财务状态"><el-input v-model="form.finance_status" placeholder="正在计提折旧" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="10">
          <el-col :span="8">
            <el-form-item label="使用人">
              <el-select v-model="form.user_name" filterable allow-create clearable
                         default-first-option placeholder="选钉钉同事，或填「共用」"
                         style="width:100%">
                <el-option label="共用（公用设备）" value="共用" />
                <el-option v-for="m in members" :key="'u' + m.userid"
                           :label="m.name + (m.mobile ? ' · ' + m.mobile : '')" :value="m.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="部门">
              <el-select v-model="form.dept" filterable allow-create clearable
                         default-first-option placeholder="选钉钉部门" style="width:100%">
                <el-option v-for="d in departments" :key="'d' + d" :label="d" :value="d" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="位置"><el-input v-model="form.location" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="10">
          <el-col :span="8">
            <el-form-item label="采购日期">
              <el-date-picker v-model="form.buy_date" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="原值"><el-input v-model="form.price" placeholder="只要数字" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="可使用年限"><el-input v-model="form.life_years" placeholder="5" /></el-form-item></el-col>
        </el-row>
        <div class="dep-hint" v-if="depInfo">
          月折旧 {{ money(depInfo.monthly) }} × 已用 {{ depInfo.months }} 个月 =
          累计 {{ money(depInfo.acc) }}，净值 {{ money(depInfo.net) }}
        </div>

        <el-divider content-position="left">
          IT 明细
          <el-button link type="primary" size="small" style="margin-left:8px" @click="showIt = !showIt">
            {{ showIt ? '收起' : '展开' }}
          </el-button>
        </el-divider>
        <div v-show="showIt">
          <el-row :gutter="10">
            <el-col :span="8"><el-form-item label="计算机名"><el-input v-model="form.computer_name" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="系统账号"><el-input v-model="form.os_user" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="操作系统"><el-input v-model="form.os_version" /></el-form-item></el-col>
          </el-row>
          <el-row :gutter="10">
            <el-col :span="8"><el-form-item label="型号"><el-input v-model="form.model" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="序列号"><el-input v-model="form.serial_no" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="BIOS 日期"><el-input v-model="form.bios_date" /></el-form-item></el-col>
          </el-row>
          <el-form-item label="CPU"><el-input v-model="form.cpu" /></el-form-item>
          <el-form-item label="主板"><el-input v-model="form.mainboard" /></el-form-item>
          <el-form-item label="内存"><el-input v-model="form.memory" /></el-form-item>
          <el-form-item label="硬盘"><el-input v-model="form.disk" /></el-form-item>
          <el-form-item label="硬盘序列号"><el-input v-model="form.disk_sn" /></el-form-item>
          <el-form-item label="显卡"><el-input v-model="form.gpu" /></el-form-item>
          <el-form-item label="显示器"><el-input v-model="form.monitor" /></el-form-item>
          <el-row :gutter="10">
            <el-col :span="8"><el-form-item label="IP"><el-input v-model="form.ip" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="MAC"><el-input v-model="form.mac" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="网卡"><el-input v-model="form.nic" /></el-form-item></el-col>
          </el-row>
          <el-form-item label="打印机"><el-input v-model="form.printer" /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="form.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { api } from '../api'
import { isAdmin } from '../store'

const list = ref([])
const stats = ref({ total: 0, in_use: 0, idle: 0, public: 0, price: 0, net: 0 })
const loading = ref(false)
const q = ref('')
const fStatus = ref('all')
const fCategory = ref('')
const page = ref(1)
const pageSize = ref(50)
const dlg = ref(false)
const saving = ref(false)
const showIt = ref(false)
const onlyIncomplete = ref(false)
const selected = ref([])
const batchDlg = ref(false)
const batchYears = ref('5')
const batchSaving = ref(false)
const departments = ref([])
const members = ref([])

const EMPTY = {
  id: null, code: '', name: '', type: '', category: '', status: '在用', finance_status: '',
  user_name: '', dept: '', location: '', buy_date: '', price: '', life_years: '',
  note: '', computer_name: '', os_user: '', os_version: '', os_install: '', model: '',
  bios_date: '', serial_no: '', cpu: '', mainboard: '', memory: '', disk: '', disk_sn: '',
  gpu: '', monitor: '', nic: '', ip: '', mac: '', printer: ''
}
const form = reactive({ ...EMPTY })

const categories = computed(() => [...new Set(list.value.map(a => a.category).filter(Boolean))])
const shown = computed(() =>
  onlyIncomplete.value ? list.value.filter(a => a.incomplete) : list.value)

const paged = computed(() => {
  const s = (page.value - 1) * pageSize.value
  return shown.value.slice(s, s + pageSize.value)
})
const pagedList = computed(() => paged.value)

function money(v) {
  return '¥' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function statusType(s) {
  return { 在用: 'success', 闲置: 'danger', 公用: 'info', 报废: 'info', 维修: 'warning' }[s] || 'info'
}

/** 钉钉部门 + 成员 */
async function loadDingPicker() {
  try {
    const r = await api.dingtalkPicker()
    departments.value = (r.departments || []).filter(Boolean)
    members.value = (r.members || []).filter(m => m.name)
  } catch (e) {
    departments.value = []
    members.value = []
  }
  // 兜底：把已有资产里的人/部门也放进选项，方便选回
  const extra = new Set(list.value.map(a => a.dept).filter(Boolean))
  if (!departments.value.length) departments.value = [...extra]
}

async function load() {
  loading.value = true
  try {
    const r = await api.assets({
      status: fStatus.value === 'all' ? '' : fStatus.value,
      category: fCategory.value, q: q.value
    })
    list.value = r.assets || []
    stats.value = r.stats || stats.value
    page.value = 1
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  } finally {
    loading.value = false
  }
}

function openNew() {
  Object.assign(form, EMPTY)
  showIt.value = false
  dlg.value = true
}

function openEdit(row) {
  Object.assign(form, { ...EMPTY, ...row, price: row.price ?? '', life_years: row.life_years ?? '' })
  showIt.value = true
  dlg.value = true
}

async function save() {
  if (!form.name.trim()) return ElMessage.warning('填资产名称')
  saving.value = true
  try {
    await api.assetSave({ ...form })
    ElMessage.success('已保存')
    dlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`删除资产「${row.code} ${row.name}」？`, '删除', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await api.assetDelete({ id: row.id })
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function onSelect(rows) {
  selected.value = rows
}

function openBatch() {
  batchYears.value = '5'
  batchDlg.value = true
}

async function saveBatch() {
  const y = Number(batchYears.value)
  if (!y || y <= 0) return ElMessage.warning('填个正确的年限')
  batchSaving.value = true
  try {
    const r = await api.assetBatchLifeYears({
      ids: selected.value.map(a => a.id), life_years: y
    })
    ElMessage.success(`已给 ${r.changed || 0} 台设成年限 ${y} 年`)
    batchDlg.value = false
    selected.value = []
    await load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    batchSaving.value = false
  }
}

async function redetect() {
  try {
    const r = await api.assetRedetect({})
    ElMessage.success(`已重判，${r.changed || 0} 台状态有变化`)
    await load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function exportExcel() {
  const p = new URLSearchParams()
  if (fStatus.value !== 'all') p.set('status', fStatus.value)
  if (fCategory.value) p.set('category', fCategory.value)
  if (q.value) p.set('q', q.value)
  window.open('/api/assets/export?' + p.toString(), '_blank')
}

/** 折旧预览：月折旧 = 原值 / (年限×12) */
const depInfo = computed(() => {
  const price = Number(form.price || 0)
  const years = Number(form.life_years || 0)
  if (!price || !years) return null
  const monthly = price / (years * 12)
  let months = 0
  if (form.buy_date) {
    const d = new Date(form.buy_date)
    const now = new Date()
    months = (now.getFullYear() - d.getFullYear()) * 12 + (now.getMonth() - d.getMonth())
    if (months < 0) months = 0
  }
  const acc = Math.min(price, monthly * months)
  return { monthly, months, acc, net: Math.max(0, price - acc) }
})

onMounted(async () => {
  await load()
  loadDingPicker()
})
</script>

<style scoped>
.warn-ico {
  color: #E6A23C; font-size: 14px; vertical-align: middle;
  margin-right: 4px; cursor: help;
}
.asset-sum {
  display: flex; gap: 26px; padding: 10px 16px;
  background: #F4F7FC; border-bottom: 1px solid #E3EAF4;
}
.as-item { font-size: 12.5px; color: #6B7280; display: flex; align-items: baseline; gap: 8px; }
.as-item b { font-size: 15px; color: var(--brand-text); }
.sec {
  font-size: 13px; font-weight: 600; color: #1F2937;
  margin: 4px 0 10px; padding-left: 8px; border-left: 3px solid var(--brand-accent);
}
.dep-hint {
  font-size: 12px; color: #6B7280; background: #F7F9FC;
  border-radius: 4px; padding: 6px 10px; margin: -4px 0 8px 100px;
}
</style>
