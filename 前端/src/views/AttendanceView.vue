<template>
  <div>
    <div class="panel">
      <div class="panel-hd">
        <el-radio-group v-model="tab" size="small" style="margin-right:12px">
          <el-radio-button value="today">今日</el-radio-button>
          <el-radio-button value="month">月度</el-radio-button>
        </el-radio-group>
        <span>{{ tab === 'today' ? '今日考勤' : '月度统计' }}</span>
        <span class="sub">{{ data.date }} {{ data.weekday }}
          <span v-if="data.weekend" class="rest-tag">休息日</span>
        </span>
        <span v-if="tab === 'today'" class="sub" style="margin-left:8px">同步于 {{ shortTime(data.synced_at) }}</span>
        <div style="margin-left:auto;display:flex;gap:8px">
          <el-button size="small" :loading="loading" @click="load(true)">刷新</el-button>
        </div>
      </div>

      <template v-if="tab === 'today'">
      <el-row :gutter="12" class="kpi-row">
        <el-col :span="6">
          <div class="kpi"><div class="kpi-label">应到</div><div class="kpi-num">{{ data.total }}</div></div>
        </el-col>
        <el-col :span="6">
          <div class="kpi ok"><div class="kpi-label">已打卡</div><div class="kpi-num">{{ data.checked }}</div></div>
        </el-col>
        <el-col :span="6">
          <div class="kpi warn"><div class="kpi-label">迟到</div><div class="kpi-num">{{ data.late }}</div></div>
        </el-col>
        <el-col :span="6">
          <div class="kpi danger"><div class="kpi-label">未打卡</div><div class="kpi-num">{{ data.absent }}</div></div>
        </el-col>
      </el-row>

      <el-table :data="data.rows" v-loading="loading" size="small"
                max-height="calc(100vh - 330px)"
                empty-text="还没有打卡数据，点右上角「刷新」">
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column label="上班打卡" width="170">
          <template #default="{ row }">
            <span v-if="row.on_duty" class="mono">{{ row.on_duty.slice(11) }}</span>
            <span v-else class="text-muted">—</span>
            <el-tag v-if="row.on_result && row.on_result !== '正常'" size="small"
                    :type="row.on_result.includes('迟到') ? 'warning' : 'info'"
                    style="margin-left:6px">{{ row.on_result }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下班打卡" width="170">
          <template #default="{ row }">
            <span v-if="row.off_duty" class="mono">{{ row.off_duty.slice(11) }}</span>
            <span v-else class="text-muted">—</span>
            <el-tag v-if="row.off_result && row.off_result !== '正常'" size="small"
                    :type="row.off_result.includes('早退') ? 'warning' : 'info'"
                    style="margin-left:6px">{{ row.off_result }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="address" label="打卡地点" min-width="220" show-overflow-tooltip />
      </el-table>
      </template>

      <!-- 月度统计 -->
      <template v-else>
        <div class="month-bar">
          <el-date-picker v-model="month" type="month" value-format="YYYY-MM"
                          :clearable="false" style="width:140px" @change="loadMonth" />
          <el-button size="small" :loading="syncing" @click="syncMonth">同步本月数据</el-button>
          <el-button size="small" type="primary" plain :loading="exporting" @click="exportMonth">导出 Excel</el-button>
          <el-button size="small" @click="openCalendar">工作日设置</el-button>
          <el-button size="small" @click="openExempt">免考勤人员</el-button>
          <el-button size="small" :loading="reminding" @click="sendRemind">发未打卡提醒</el-button>
          <span class="text-muted" style="font-size:12px">
            应出勤 {{ monthData.workdays }} 天
            <span v-if="monthData.syncing" style="margin-left:8px;color:#E6A23C">
              正在同步 {{ jobPercent }}%（可以先去忙别的）
            </span>
          </span>
        </div>

        <el-table :data="monthData.rows" v-loading="monthLoading" size="small"
                  max-height="calc(100vh - 330px)"
                  empty-text="这个月还没有数据，点「同步本月数据」">
          <el-table-column prop="name" label="姓名" min-width="140" />
          <el-table-column prop="attended" label="出勤" min-width="100" align="center" />
          <el-table-column prop="late" label="迟到" min-width="100" align="center">
            <template #default="{ row }">
              <span :class="{ 'bad': row.late > 0 }">{{ row.late }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="early" label="早退" min-width="100" align="center" />
          <el-table-column prop="leave_days" label="请假" min-width="100" align="center" />
          <el-table-column prop="fix_days" label="补卡" min-width="100" align="center" />
          <el-table-column prop="absent" label="缺卡" min-width="100" align="center">
            <template #default="{ row }">
              <span :class="{ 'bad': row.absent > 0 }">{{ row.absent }}</span>
            </template>
          </el-table-column>
          <el-table-column label="出勤率" min-width="110" align="center">
            <template #default="{ row }">
              <span :class="{ 'bad': row.rate < 80 }">{{ row.rate }}%</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>

    <!-- 免考勤人员 -->
    <el-dialog v-model="exemptDlg" title="免考勤人员" width="560px" append-to-body>
      <div class="text-muted" style="font-size:12.5px;margin-bottom:12px">
        勾上的人不参与考勤统计，也不会收到「未打卡」提醒。适合外包、兼职、不打卡的岗位。
      </div>
      <el-input v-model="exemptKw" placeholder="搜索姓名" size="small" clearable
                style="width:200px;margin-bottom:10px" />
      <div class="exempt-list">
        <div v-for="m in filteredMembers" :key="m.userid" class="exempt-item">
          <el-checkbox :model-value="m.exempt" @change="toggleExempt(m)">
            {{ m.name }}
            <span v-if="m.mobile" class="text-muted" style="font-size:11px">· {{ m.mobile }}</span>
          </el-checkbox>
        </div>
        <div v-if="!filteredMembers.length" class="text-muted" style="padding:16px 0;text-align:center">
          没有这个人
        </div>
      </div>
      <template #footer>
        <span class="text-muted" style="margin-right:auto;font-size:12px">
          已免考勤 {{ exemptCount }} 人
        </span>
        <el-button @click="exemptDlg = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 工作日设置 -->
    <el-dialog v-model="calDlg" title="工作日设置" width="820px" append-to-body>
      <div class="cal-bar">
        <el-select v-model="holidayJump" placeholder="假期" clearable
                   style="width:130px" @change="jumpToHoliday">
          <el-option v-for="h in holidayList" :key="h.date"
                     :label="h.name + ' · ' + h.date.slice(5)" :value="h.date" />
        </el-select>
        <el-button size="small" :loading="calSyncing" @click="syncHolidays">同步国家节假日</el-button>
        <span class="text-muted" style="font-size:12px">
          点日期格切换 工作日 ↔ 休息日；虚线框是自动同步的，实线框是你手动标的
        </span>
      </div>

      <el-calendar v-model="calCursor">
        <template #date-cell="{ data }">
          <div class="cal-cell" :class="cellClass(data)" @click.stop="toggleDay(data)">
            <div class="cal-cell-top">
              <span class="cal-cell-day">{{ Number(data.day.slice(-2)) }}</span>
              <span v-if="isToday(data.day)" class="cal-cell-today">今</span>
            </div>
            <div v-if="markOf(data.day)" class="cal-cell-name">{{ markOf(data.day).name }}</div>
            <div v-else class="cal-cell-lunar">{{ lunarOf(data.day) }}</div>
          </div>
        </template>
      </el-calendar>

      <template #footer>
        <span class="text-muted" style="margin-right:auto;font-size:12px">
          本月工作日 {{ monthWorkdayCount }} 天
        </span>
        <el-button @click="calDlg = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { Solar } from 'lunar-javascript'

const tab = ref('today')
const loading = ref(false)
const month = ref(new Date().toISOString().slice(0, 7))
const monthLoading = ref(false)
const syncing = ref(false)
const jobPercent = ref(0)
const monthData = reactive({ workdays: 0, rows: [], syncing: false })
const calDlg = ref(false)

const calSyncing = ref(false)
const calCursor = ref(new Date())
const calDays = ref({})
const holidayList = ref([])
const holidayJump = ref('')
const reminding = ref(false)
const exporting = ref(false)
const exemptDlg = ref(false)
const exemptKw = ref('')
const members = ref([])
const data = reactive({
  date: '', weekday: '', weekend: false, total: 0, checked: 0,
  late: 0, absent: 0, leave: 0, rows: [], synced_at: ''
})

const STATUS = {
  normal: ['正常', 'success'],
  late: ['迟到', 'warning'],
  absent: ['未打卡', 'danger'],
  leave: ['请假', 'info'],
  rest: ['休息', 'info']
}

function statusText(s) { return (STATUS[s] || ['—', 'info'])[0] }
function statusType(s) { return (STATUS[s] || ['—', 'info'])[1] }
function shortTime(t) { return t ? String(t).slice(5, 16) : '—' }

async function load(force = false) {
  loading.value = true
  try {
    const r = force ? await api.attendanceRefresh() : await api.attendanceToday()
    Object.assign(data, {
      date: r.date, weekday: r.weekday, weekend: r.weekend,
      total: r.total, checked: r.checked, late: r.late, absent: r.absent,
      leave: r.leave, rows: r.rows || [], synced_at: r.synced_at
    })
    if (force) ElMessage.success('已刷新')
  } catch (e) {
    ElMessage.error(e.message || '读取考勤失败')
  } finally {
    loading.value = false
  }
}

function loadMonth() {
  if (!month.value) return
  monthLoading.value = true
  api.attendanceMonth(month.value).then(r => {
    monthData.workdays = r.workdays || 0
    monthData.rows = r.rows || []
    monthData.syncing = !!r.syncing
    syncing.value = !!r.syncing
    if (r.syncing) pollProgress()
  }).catch(e => {
    ElMessage.error(e.message || '读取月度数据失败')
  }).finally(() => {
    monthLoading.value = false
  })
}

function syncMonth() {
  syncing.value = true
  api.attendanceSyncMonth({ month: month.value }).then(() => {
    ElMessage.info('已经开始同步，完成后会自动刷新')
    pollProgress()
  }).catch(e => {
    ElMessage.error(e.message || '同步失败')
    syncing.value = false
  })
}

let pollTimer = null
function pollProgress() {
  clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    try {
      const p = await api.attendanceProgress(month.value)
      jobPercent.value = p.percent || 0
      if (!p.running) {
        clearInterval(pollTimer)
        syncing.value = false
        jobPercent.value = 100
        loadMonth()
      }
    } catch (e) {
      clearInterval(pollTimer)
      syncing.value = false
    }
  }, 1500)
}

const filteredMembers = computed(() => {
  const k = exemptKw.value.trim()
  const list = members.value
  return k ? list.filter(m => String(m.name || '').includes(k)) : list
})
const exemptCount = computed(() => members.value.filter(m => m.exempt).length)

async function openExempt() {
  exemptDlg.value = true
  await loadExempt()
}

async function loadExempt() {
  try {
    const r = await api.attendanceExempt()
    members.value = r.members || []
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  }
}

async function toggleExempt(m) {
  const next = !m.exempt
  try {
    await api.attendanceExemptSet({ userid: m.userid, name: m.name, exempt: next })
    m.exempt = next
    load(false)          // 今日看板立刻反映
    loadMonth()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

/** 农历/节气：优先显示节气，没有就显示农历日 */
function lunarOf(day) {
  try {
    const [y, m, d] = day.split('-').map(Number)
    const lunar = Solar.fromYmd(y, m, d).getLunar()
    const jieqi = lunar.getJieQi()
    if (jieqi) return jieqi
    return lunar.getDayInChinese()
  } catch (e) {
    return ''
  }
}

async function openCalendar() {
  calDlg.value = true
  await loadCalendarMonth(calCursor.value)
  preloadYear()
}

/** 依次把 12 个月拉回来（串行，避免一次打 12 个请求） */
let preloading = false
async function preloadYear() {
  if (preloading) return
  preloading = true
  try {
    const y = calCursor.value.getFullYear()
    for (let m = 1; m <= 12; m++) {
      const ym = y + '-' + String(m).padStart(2, '0')
      const r = await api.attendanceCalendar(ym)
      const next = { ...calDays.value }
      ;(r.days || []).forEach(x => { next[x.date] = x })
      calDays.value = next
    }
    buildHolidayList()
  } catch (e) {
    // 预加载失败不影响当前月
  } finally {
    preloading = false
  }
}

/** 只取当前显示的这个月，缓存到 calDays（date → day 对象） */
async function loadCalendarMonth(d) {
  const ym = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0')
  try {
    const r = await api.attendanceCalendar(ym)
    const next = { ...calDays.value }
    ;(r.days || []).forEach(x => { next[x.date] = x })
    calDays.value = next
    buildHolidayList()
  } catch (e) {
    ElMessage.error(e.message || '读取日历失败')
  }
}

/** 从已加载的月份里摘出节假日，做成左上角下拉 */
function buildHolidayList() {
  const y = calCursor.value.getFullYear()
  holidayList.value = Object.values(calDays.value)
    .filter(d => d.kind === 'holiday' && d.date.startsWith(String(y)))
    .sort((a, b) => a.date.localeCompare(b.date))
    .map(d => ({ date: d.date, name: d.name || '假期' }))
}

function jumpToHoliday(date) {
  if (!date) return
  calCursor.value = new Date(date + 'T00:00:00')
  holidayJump.value = ''
}

/** 只有真正标过的日子才返回（接口给每天都返回一条，未标记的 kind 为空） */
function markOf(day) {
  const m = calDays.value[day]
  return m && m.kind ? m : null
}

function isToday(day) {
  const d = new Date()
  const s = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0')
    + '-' + String(d.getDate()).padStart(2, '0')
  return day === s
}

function cellClass(data) {
  const m = markOf(data.day)
  const d = new Date(data.day + 'T00:00:00')
  const weekend = d.getDay() === 0 || d.getDay() === 6
  return {
    off: m ? m.kind === 'holiday' : weekend,
    marked: !!m,
    auto: !!(m && m.source === 'auto'),
    manual: !!(m && m.source === 'manual')
  }
}

async function toggleDay(data) {
  const day = data.day
  const m = markOf(day)
  const d = new Date(day + 'T00:00:00')
  const weekend = d.getDay() === 0 || d.getDay() === 6
  const kind = m ? '' : (weekend ? 'workday' : 'holiday')
  try {
    await api.attendanceCalendarSet({ date: day, kind, name: '' })
    await loadCalendarMonth(calCursor.value)
    loadMonth()
  } catch (e) {
    ElMessage.error(e.message || '设置失败')
  }
}

async function syncHolidays() {
  calSyncing.value = true
  try {
    const r = await api.attendanceCalendarSync({ year: calCursor.value.getFullYear() })
    ElMessage.success('已同步 ' + r.count + ' 天（' + r.year + ' 年）')
    await loadCalendarMonth(calCursor.value)
    loadMonth()
  } catch (e) {
    ElMessage.error(e.message || '同步失败')
  } finally {
    calSyncing.value = false
  }
}

async function sendRemind() {
  reminding.value = true
  try {
    const r = await api.attendanceRemind()
    ElMessage.success('已提醒 ' + r.absent + ' 位未打卡的同事')
  } catch (e) {
    ElMessage.error(e.message || '提醒失败')
  } finally {
    reminding.value = false
  }
}

/** 月历翻页时，把左上的月份选择器也跟着动 */
watch(calCursor, async (v) => {
  const s = v.getFullYear() + '-' + String(v.getMonth() + 1).padStart(2, '0')
  if (s !== month.value) month.value = s
  await loadCalendarMonth(v)
})

const monthWorkdayCount = computed(() => {
  const y = calCursor.value.getFullYear()
  const m = calCursor.value.getMonth() + 1
  const prefix = y + '-' + String(m).padStart(2, '0')
  return Object.values(calDays.value)
    .filter(d => d.date.startsWith(prefix) && d.is_workday).length
})

async function exportMonth() {
  // 用 fetch 拿成 blob 再触发下载：不受弹窗拦截影响
  try {
    exporting.value = true
    const res = await fetch(api.attendanceReportUrl(month.value), { credentials: 'include' })
    if (!res.ok) {
      throw new Error('导出失败（' + res.status + '）')
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `考勤报表_${month.value}.xlsx`
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 3000)
    ElMessage.success('考勤报表已开始下载')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  load(false)
  loadMonth()
})
</script>

<style scoped>
.month-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px 10px;
}
.bad { color: #C62828; font-weight: 600; }
.cal-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }

/* 日历格子 */
.cal-cell { width: 100%; height: 100%; padding: 5px 5px 3px; box-sizing: border-box; cursor: pointer; }
.cal-cell-top { display: flex; align-items: center; gap: 3px; }
.cal-cell-day { font-size: 16px; font-weight: 600; }
.cal-cell.off .cal-cell-day { color: #C62828; }
.cal-cell-today {
  font-size: 10px; color: #fff; background: #1B4F8C;
  border-radius: 3px; padding: 0 3px; line-height: 14px;
}
.cal-cell-lunar {
  font-size: 10.5px; color: #9AA6B6; margin-top: 3px; line-height: 1.2;
}
.exempt-list { max-height: 340px; overflow-y: auto; }
.exempt-item { padding: 5px 2px; border-bottom: 1px solid #F2F4F7; }
.exempt-item:last-child { border-bottom: none; }
.cal-cell-name {
  font-size: 10.5px; color: #B8860B; margin-top: 3px;
  line-height: 1.2; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cal-cell.marked { background: #FFF9E8; }
.cal-cell.manual { box-shadow: inset 0 0 0 1.5px #C8A45C; border-radius: 3px; }
.cal-cell.auto { box-shadow: inset 0 0 0 1.5px #C8A45C dashed; border-radius: 3px; }
:deep(.el-calendar-table .el-calendar-day) { padding: 0; height: 66px; }
:deep(.el-calendar-table td.is-selected) { background: #EEF4FF; }
:deep(.el-calendar__header) { padding: 6px 8px; }
:deep(.el-calendar__body) { padding: 4px 8px 8px; }
:deep(.el-calendar-table thead th) { font-size: 12.5px; color: #6B7280; font-weight: 500; }

.kpi-row { padding: 14px 16px 4px; }
.kpi {
  border: 1px solid #E5E9F0; border-left: 3px solid #1B4F8C;
  border-radius: 6px; padding: 10px 14px; background: #fff;
}
.kpi.ok { border-left-color: #2E7D32; }
.kpi.warn { border-left-color: #E6A23C; }
.kpi.danger { border-left-color: #C62828; }
.kpi-label { font-size: 12px; color: #6B7280; }
.kpi-num { font-size: 24px; font-weight: 700; color: var(--brand-text); line-height: 1.3; }
.kpi.ok .kpi-num { color: #2E7D32; }
.kpi.warn .kpi-num { color: #E6A23C; }
.kpi.danger .kpi-num { color: #C62828; }
.rest-tag {
  margin-left: 6px; font-size: 11px; color: #6B7280;
  background: #F1F3F5; border-radius: 3px; padding: 1px 5px;
}
</style>
