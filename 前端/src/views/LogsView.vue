<template>
  <div class="fill-page">
    <div class="panel">
      <div class="panel-hd">
        <span>操作日志</span>
        <span class="sub">共 {{ filtered.length }} 条</span>
        <div style="margin-left:auto;display:flex;gap:8px">
          <el-input v-model="keyword" size="small" placeholder="搜索说明 / 操作人" style="width:190px" clearable />
          <el-select v-model="days" size="small" style="width:120px" @change="load">
            <el-option :value="7" label="近 7 天" />
            <el-option :value="30" label="近 30 天" />
            <el-option :value="90" label="近 90 天" />
            <el-option :value="3650" label="全部" />
          </el-select>
        </div>
      </div>

      <el-table v-loading="loading" :data="paged" size="small" style="width:100%"
                max-height="calc(100vh - 164px)">
        <el-table-column prop="created_at" label="时间" width="170" />
        <el-table-column label="对象" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ targetText(row.target_type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="160">
          <template #default="{ row }">
            <el-tag size="small" :type="actionType(row.action)" class="action-tag">{{ actionText(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="说明" min-width="260" show-overflow-tooltip />
        <el-table-column prop="username" label="操作人" width="120" />
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[20, 50, 100, 200]"
          :total="filtered.length"
          layout="total, sizes, prev, pager, next, jumper"
          background
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const logs = ref([])
const loading = ref(false)
const keyword = ref('')
const days = ref(30)
const page = ref(1)
const pageSize = ref(50)

watch(keyword, () => { page.value = 1 })

const filtered = computed(() => {
  const k = keyword.value.trim()
  if (!k) return logs.value
  return logs.value.filter(l =>
    String(l.detail || '').includes(k) || String(l.username || '').includes(k))
})

/** 当前页的数据 */
const paged = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

const TARGET = {
  invoice: '发票', batch: '单据', user: '账号', contract: '合同',
  project: '项目', attendance: '考勤', audit_log: '日志', '': '其他'
}
const ACTION = {
  // 账号
  login: '登录', logout: '退出', register: '自助注册',
  user_create: '新建账号', user_update: '修改账号', user_reset_pw: '重置密码',
  change_password: '改密码', reset_password: '重置密码',
  // 发票
  create_invoice: '登记发票', update_invoice: '修改发票', delete_invoice: '删除发票',
  submit: '提交单据', submit_batch: '提交单据', resubmit: '重新提交',
  review_approved: '审核通过', review_returned: '审核退回', review_return: '审核退回',
  withdraw: '撤回', delete_batch: '删除单据', batch_paid: '标记已报销',
  // 合同
  save_project: '保存项目', delete_project: '删除项目',
  save_contract: '保存合同', delete_contract: '删除合同',
  save_billing: '记录开票回款', delete_billing: '删除开票回款',
  // 考勤
  attendance_calendar: '考勤日历', attendance_sync: '同步考勤',
  attendance_exempt: '免考勤设置', attendance_remind: '考勤提醒',
  // 其它
  audit: '查验', tool_save: '维护工具', tool_delete: '删除工具',
  notice_publish: '发布公告'
}

function targetText(t) { return TARGET[t] || t || '其他' }
function actionText(a) { return ACTION[a] || a }
function actionType(a) {
  if (['delete_invoice', 'delete_batch', 'review_return', 'withdraw'].includes(a)) return 'danger'
  if (['review_approve', 'user_create'].includes(a)) return 'success'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const r = await api.logs({ days: days.value, limit: 500 })
    logs.value = r.logs || []
    page.value = 1
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>

.action-tag {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
.pager {
  flex: none;
  display: flex; justify-content: flex-end;
  padding: 10px 16px;
  border-top: 1px solid #EEF1F5;
  background: #fff;
  border-radius: 0 0 6px 6px;
}
</style>
