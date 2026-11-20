<template>
  <el-dialog
    :model-value="modelValue"
    title="账号设置"
    width="560px"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="acct-card">
      <el-avatar :size="48" class="avatar-brand" style="font-size:18px">{{ avatar }}</el-avatar>
      <div style="min-width:0">
        <div style="display:flex;align-items:center;gap:8px;font-size:15px;font-weight:600">
          {{ me?.display_name || me?.username }}
          <el-tag size="small" :type="isAdmin ? 'primary' : 'info'">{{ isAdmin ? '管理员' : '用户' }}</el-tag>
        </div>
        <div class="text-muted" style="font-size:12px;margin-top:4px">
          {{ me?.username }} · 构建 {{ state.build || '—' }} · 最近登录 {{ fmt(me?.last_login_at) }}
        </div>
      </div>
    </div>

    <div class="acct-sec">账号信息</div>
    <el-descriptions :column="2" border size="small">
      <el-descriptions-item label="用户名"><span class="mono">{{ me?.username }}</span></el-descriptions-item>
      <el-descriptions-item label="姓名">{{ me?.display_name }}</el-descriptions-item>
      <el-descriptions-item label="角色">{{ isAdmin ? '管理员' : '普通用户' }}</el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag size="small" :type="me?.status ? 'success' : 'danger'">{{ me?.status ? '启用' : '停用' }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="账号创建">{{ (me?.created_at || '').slice(0, 10) || '—' }}</el-descriptions-item>
      <el-descriptions-item label="最近登录">{{ fmt(me?.last_login_at) }}</el-descriptions-item>
    </el-descriptions>

    <div class="acct-sec">修改密码</div>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="原密码" prop="old">
        <el-input v-model="form.old" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码（至少 8 位）" prop="nu">
        <el-input v-model="form.nu" type="password" show-password />
      </el-form-item>
      <el-form-item label="确认新密码" prop="nu2">
        <el-input v-model="form.nu2" type="password" show-password @keyup.enter="save" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { state, isAdmin, loadMe } from '../store'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'saved'])

const me = computed(() => state.me || {})
const avatar = computed(() => (me.value.display_name || me.value.username || '?').slice(0, 1))

const formRef = ref(null)
const saving = ref(false)
const form = reactive({ old: '', nu: '', nu2: '' })

const rules = {
  old: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  nu: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '至少 8 位', trigger: 'blur' }
  ],
  nu2: [{
    validator: (_r, v, cb) => {
      if (!v) return cb(new Error('请再输一次'))
      if (v !== form.nu) return cb(new Error('两次输入不一致'))
      cb()
    },
    trigger: 'blur'
  }]
}

watch(() => props.modelValue, (open) => {
  if (open) {
    form.old = ''; form.nu = ''; form.nu2 = ''
    formRef.value?.clearValidate()
  }
})

function fmt(s) {
  if (!s) return '从未登录'
  return String(s).slice(0, 16)
}

async function save() {
  try {
    await formRef.value.validate()
  } catch (e) { return }
  saving.value = true
  try {
    await api.changePassword(form.old, form.nu)
    ElMessage.success('密码已修改')
    await loadMe()
    emit('update:modelValue', false)
    emit('saved')
  } catch (e) {
    ElMessage.error(e.message || '修改失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.acct-card {
  display: flex; align-items: center; gap: 14px;
  padding: 16px 18px; margin-bottom: 20px;
  background: #F3F4F6; border-radius: 4px;
}
.acct-sec {
  margin: 18px 0 10px; padding-bottom: 6px;
  font-size: 12px; font-weight: 600; color: #6B7280; letter-spacing: .5px;
  border-bottom: 1px solid #E5E7EB;
}
</style>
