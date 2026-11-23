<template>
  <div>
    <div class="panel">
      <div class="panel-hd">
        <span>今日考勤</span>
        <span class="sub">{{ data.date }} {{ data.weekday }}
          <span v-if="data.weekend" class="rest-tag">休息日</span>
        </span>
        <span class="sub" style="margin-left:8px">同步于 {{ shortTime(data.synced_at) }}</span>
        <div style="margin-left:auto;display:flex;gap:8px">
          <el-button size="small" :loading="loading" @click="load(true)">刷新</el-button>
        </div>
      </div>

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
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const loading = ref(false)
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

onMounted(() => load(false))
</script>

<style scoped>
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
