<template>
  <div class="login-page">
    <div>
      <div class="login-card">
        <div class="login-brand">
          <span class="logo">票</span>
          <div>
            <b>人数发票登记台账</b>
            <span>INVOICE REGISTRATION SYSTEM</span>
          </div>
        </div>

        <div class="login-body">
          <h2>{{ mode === 'login' ? '登录' : '注册' }}</h2>
          <p class="login-tip">
            {{ mode === 'login' ? '请使用您的账号登录系统' : '自助注册，注册后即为普通用户' }}
          </p>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" size="large" placeholder="请输入用户名" :prefix-icon="User" />
            </el-form-item>

            <el-form-item v-if="mode === 'register'" label="姓名" prop="display_name">
              <el-input v-model="form.display_name" size="large" placeholder="真实姓名，用于审核显示" />
            </el-form-item>

            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password" size="large" type="password" show-password
                placeholder="请输入密码" :prefix-icon="Lock" @keyup.enter="submit"
              />
            </el-form-item>

            <el-form-item v-if="mode === 'register'" label="确认密码" prop="password2">
              <el-input
                v-model="form.password2" size="large" type="password" show-password
                placeholder="再输一次" :prefix-icon="Lock" @keyup.enter="submit"
              />
            </el-form-item>

            <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon style="margin-bottom:14px" />

            <el-button
              type="primary" size="large" style="width:100%;height:44px;letter-spacing:4px"
              :loading="loading" @click="submit"
            >
              {{ mode === 'login' ? '登 录' : '注册并登录' }}
            </el-button>
          </el-form>

          <div style="margin-top:16px;text-align:center;font-size:13px">
            <template v-if="mode === 'login'">
              还没有账号？<el-link type="primary" :underline="false" @click="switchMode('register')">注册一个</el-link>
            </template>
            <template v-else>
              已有账号？<el-link type="primary" :underline="false" @click="switchMode('login')">去登录</el-link>
            </template>
          </div>
        </div>
      </div>

      <div class="login-copy">© 2026 人数发票登记台账 · 温州信宇科技</div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { api } from '../api'
import { loadMe } from '../store'

const router = useRouter()
const route = useRoute()

const mode = ref('login')
const loading = ref(false)
const error = ref('')
const formRef = ref(null)

const form = reactive({ username: '', password: '', password2: '', display_name: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  password2: [{
    validator: (_r, v, cb) => {
      if (mode.value !== 'register') return cb()
      if (!v) return cb(new Error('请再输一次密码'))
      if (v !== form.password) return cb(new Error('两次输入的密码不一样'))
      cb()
    },
    trigger: 'blur'
  }]
}

function switchMode(m) {
  mode.value = m
  error.value = ''
  formRef.value?.clearValidate()
}

async function submit() {
  error.value = ''
  try {
    await formRef.value.validate()
  } catch (e) { return }

  loading.value = true
  try {
    if (mode.value === 'login') {
      await api.login(form.username.trim(), form.password)
    } else {
      await api.register({
        username: form.username.trim(),
        password: form.password,
        display_name: form.display_name.trim() || form.username.trim()
      })
    }
    await loadMe()
    ElMessage.success(mode.value === 'login' ? '欢迎回来' : '注册成功')
    const target = route.query.redirect || '/entry'
    router.replace(target)
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>
