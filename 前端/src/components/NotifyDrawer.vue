<template>
  <el-drawer
    :model-value="modelValue"
    title="消息"
    direction="rtl"
    size="360px"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="load"
  >
    <template #header>
      <div style="display:flex;align-items:center;justify-content:space-between;width:100%">
        <b>消息</b>
        <el-button link type="primary" size="small" @click="readAll">全部标为已读</el-button>
      </div>
    </template>

    <el-empty v-if="!loading && !items.length" description="还没有消息" />
    <div v-else v-loading="loading">
      <div
        v-for="n in items"
        :key="n.id"
        class="np-item"
        :class="{ unread: !n.read_at }"
        @click="open(n)"
      >
        <div class="np-top">
          <span v-if="!n.read_at" class="np-dot" />
          <span class="np-title">{{ n.title }}</span>
          <span class="np-time">{{ short(n.created_at) }}</span>
        </div>
        <div class="np-body">{{ n.body }}</div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { state } from '../store'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'read'])

const router = useRouter()
const items = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await api.notifications()
    items.value = r.items || []
    state.unread = r.unread || 0
    emit('read', state.unread)
  } catch (e) { /* 静默 */ } finally {
    loading.value = false
  }
}

async function readAll() {
  await api.readNotifications([])
  await load()
}

async function open(n) {
  if (!n.read_at) {
    await api.readNotifications([n.id])
    n.read_at = new Date().toISOString()
    state.unread = Math.max(0, state.unread - 1)
    emit('read', state.unread)
  }
  // 公告没有单据号，跳到「公告」页看全文
  if (n.kind === 'notice' || n.announcement_id) {
    emit('update:modelValue', false)
    router.push({ name: 'notice' })
    return
  }
  if (n.batch_id) {
    emit('update:modelValue', false)
    router.push({ name: 'ledger', query: { batch: n.batch_id } })
  }
}

function short(s) {
  return String(s || '').slice(5, 16)
}
</script>

<style scoped>
.np-item {
  padding: 11px 4px; border-bottom: 1px solid #E5E7EB;
  border-left: 3px solid transparent; cursor: pointer;
}
.np-item:hover { background: #F9FAFB; }
.np-item.unread { background: #F0F4FA; border-left-color: #1B4F8C; }
.np-top { display: flex; align-items: center; gap: 6px; margin-bottom: 2px; }
.np-dot { width: 6px; height: 6px; border-radius: 50%; background: #1B4F8C; flex: none; }
.np-title { flex: 1; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.np-item.unread .np-title { font-weight: 600; }
.np-time { font-size: 11px; color: #9CA3AF; flex: none; }
.np-body { padding-left: 12px; font-size: 12px; color: #6B7280; line-height: 1.55; }
</style>
