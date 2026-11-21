<template>
  <div>
    <div class="panel">
      <div class="panel-hd">
        <span>公告</span>
        <span class="sub">共 {{ list.length }} 条</span>
        <el-button v-if="canPublish" type="primary" size="small" style="margin-left:auto"
                   @click="openNew">＋ 发布公告</el-button>
      </div>

      <div class="notice-wrap">
        <div v-for="a in list" :key="a.id" class="notice-item">
          <div class="notice-hd">
            <span class="notice-title">{{ a.title }}</span>
            <span class="notice-time">{{ a.created_at }}</span>
            <span class="notice-ack">
              <el-button v-if="!a.read" type="primary" size="small" plain
                         @click="ack(a)">我已知晓</el-button>
              <el-tag v-else size="small" type="success" effect="plain">已知晓</el-tag>
            </span>
            <el-button v-if="canPublish" link type="danger" size="small"
                       @click="remove(a)">删除</el-button>
          </div>
          <div class="notice-body">{{ a.content }}</div>
          <div class="notice-foot">
            <span>发布人：{{ a.author || '—' }}</span>
            <span v-if="canPublish" class="ack-stat">
              <span class="ack-num">{{ a.ack_count || 0 }}/{{ a.sent_count || 0 }}</span> 人已确认
              <el-tooltip v-if="a.pending_names" placement="top">
                <template #content>
                  <div style="max-width:260px;line-height:1.7">
                    还没确认：{{ a.pending_names }}
                  </div>
                </template>
                <span class="ack-pending">· 未确认 {{ (a.sent_count || 0) - (a.ack_count || 0) }} 人</span>
              </el-tooltip>
              <span v-else-if="a.sent_count > 0" class="ack-pending done">· 全部已确认</span>
              <span v-else class="ack-pending">· 没有通知记录</span>
            </span>
          </div>
        </div>
        <div v-if="!list.length" class="empty">还没有公告</div>
      </div>
    </div>

    <el-dialog v-model="dlg" title="发布公告" width="560px">
      <el-form :model="form" label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="60" show-word-limit
                    placeholder="比如：本月报销截止到 25 号" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="6"
                    placeholder="写清楚要点，大家会在右上角消息里收到" />
        </el-form-item>
      </el-form>
      <div class="text-muted" style="font-size:12px">
        发布后，所有在用账号的消息里都会收到一条提醒。
      </div>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { refreshBadges } from '../store'

const list = ref([])
const canPublish = ref(false)
const dlg = ref(false)
const saving = ref(false)
const form = reactive({ title: '', content: '' })

async function load() {
  try {
    const r = await api.announcements()
    list.value = r.announcements || []
    canPublish.value = !!r.can_publish
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  }
}

function openNew() {
  form.title = ''
  form.content = ''
  dlg.value = true
}

async function save() {
  if (!form.title.trim()) return ElMessage.warning('写个标题')
  if (!form.content.trim()) return ElMessage.warning('写点内容')
  saving.value = true
  try {
    const r = await api.announcementSave({ ...form })
    ElMessage.success(`已发布，已通知 ${r.notified || 0} 个账号`)
    dlg.value = false
    await load()
    refreshBadges()
  } catch (e) {
    ElMessage.error(e.message || '发布失败')
  } finally {
    saving.value = false
  }
}

async function ack(a) {
  try {
    await api.announcementAck({ id: a.id, title: a.title })
    a.read = true
    refreshBadges()
    ElMessage.success('已标记为已知晓')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function remove(a) {
  try {
    await ElMessageBox.confirm(`删除公告「${a.title}」？大家消息里的提醒也会一起删掉。`,
      '删除公告', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await api.announcementDelete({ id: a.id })
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.notice-wrap { padding: 8px 16px 16px; }
.notice-item {
  border-bottom: 1px solid #EEF1F5;
  padding: 14px 0;
}
.notice-item:last-child { border-bottom: none; }
.notice-hd { display: flex; align-items: baseline; gap: 10px; }
.notice-title { font-size: 14.5px; font-weight: 600; color: var(--brand-text); }
.notice-time { font-size: 12px; color: #9AA6B6; }
.notice-hd .notice-ack { margin-left: auto; }
.notice-hd .notice-ack .el-button { margin-right: 0; }
.notice-hd > .el-button { margin-left: 10px; }
.notice-body {
  font-size: 13px; color: #4B5563; line-height: 1.75;
  margin-top: 8px; white-space: pre-wrap;
}
.notice-foot {
  font-size: 12px; color: #9AA6B6; margin-top: 8px;
  display: flex; gap: 14px; align-items: center; flex-wrap: wrap;
}
.ack-stat { display: inline-flex; align-items: center; gap: 4px; }
.ack-num { color: var(--brand-text); font-weight: 600; font-variant-numeric: tabular-nums; }
.ack-pending { color: #94A3B8; cursor: help; }
.ack-pending.done { color: #2E7D32; cursor: default; }
.empty { text-align: center; color: #9AA6B6; padding: 40px 0; }
</style>
