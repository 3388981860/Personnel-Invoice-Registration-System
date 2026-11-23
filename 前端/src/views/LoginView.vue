<template>
  <div class="login-page">
    <div class="login-card">
      <!-- 左：品牌信息 -->
      <div class="login-right">
        <div class="qr-box">
          <span class="logo big">
            <svg viewBox="0 0 24 24" width="30" height="30" fill="currentColor" aria-hidden="true">
              <path d="M12 2.6 21.4 7.4H2.6L12 2.6Z"/>
              <rect x="4.6" y="9.1" width="2.5" height="8.2" rx=".5"/>
              <rect x="10.75" y="9.1" width="2.5" height="8.2" rx=".5"/>
              <rect x="16.9" y="9.1" width="2.5" height="8.2" rx=".5"/>
              <rect x="2.9" y="18.4" width="18.2" height="2.5" rx=".7"/>
            </svg>
          </span>
          <div class="qr-title">人数行政平台</div>
          <div class="qr-sub">ADMINISTRATION PLATFORM</div>

          <div class="qr-slogan">
            <transition name="slogan" mode="out-in">
              <div :key="sloganIdx" class="slogan-lines">
                <div v-for="(line, i) in sloganLines" :key="i">{{ line }}</div>
              </div>
            </transition>
          </div>

          <div class="qr-foot">
            <span>服务状态：{{ statusText }}</span>
          </div>
        </div>
      </div>
      <!-- 右：登录 -->
      <div class="login-left">
        <div class="login-head">
          <span class="logo">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
              <path d="M12 2.6 21.4 7.4H2.6L12 2.6Z"/>
              <rect x="4.6" y="9.1" width="2.5" height="8.2" rx=".5"/>
              <rect x="10.75" y="9.1" width="2.5" height="8.2" rx=".5"/>
              <rect x="16.9" y="9.1" width="2.5" height="8.2" rx=".5"/>
              <rect x="2.9" y="18.4" width="18.2" height="2.5" rx=".7"/>
            </svg>
          </span>
          <span class="head-name">人数行政平台</span>
          <el-link
            v-if="mode === 'login'"
            type="primary" :underline="false" class="head-link"
            @click="switchMode('register')"
          >注册账号</el-link>
          <el-link
            v-else
            type="primary" :underline="false" class="head-link"
            @click="switchMode('login')"
          >返回登录</el-link>
        </div>

        <div class="login-form">
          <h2>{{ mode === 'login' ? '账号登录' : '注册账号' }}</h2>
          <p class="login-tip">
            {{ mode === 'login' ? '请使用管理员分配的账号登录' : '填写后即为普通用户，权限由管理员分配' }}
          </p>

          <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent>
            <el-form-item prop="username">
              <el-input
                v-model="form.username" size="large" placeholder="用户名"
                :prefix-icon="User" @keyup.enter="submit"
              />
            </el-form-item>

            <el-form-item v-if="mode === 'register'" prop="display_name">
              <el-input v-model="form.display_name" size="large" placeholder="姓名（用于审核显示）" />
            </el-form-item>

            <el-form-item prop="password">
              <el-input
                v-model="form.password" size="large" type="password" show-password
                placeholder="密码" :prefix-icon="Lock" @keyup.enter="submit"
              />
            </el-form-item>

            <el-form-item v-if="mode === 'register'" prop="password2">
              <el-input
                v-model="form.password2" size="large" type="password" show-password
                placeholder="再输一次密码" :prefix-icon="Lock" @keyup.enter="submit"
              />
            </el-form-item>

            <div v-if="mode === 'login'" class="login-row">
              <el-checkbox v-model="remember">记住用户名</el-checkbox>
              <el-link type="info" :underline="false" @click="forgot">忘记密码？</el-link>
            </div>

            <el-alert
              v-if="error" :title="error" type="error" :closable="false"
              show-icon style="margin-bottom:14px"
            />

            <el-button
              type="primary" size="large" class="login-btn"
              :loading="loading" @click="submit"
            >
              {{ mode === 'login' ? '登 录' : '注 册 并 登 录' }}
            </el-button>
          </el-form>

          <div v-if="mode === 'login'" class="ding-wrap">
            <div class="ding-split"><span>或</span></div>
            <el-button class="ding-btn" size="large" :loading="dingLoading" @click="loginByDingTalk">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
                <path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm5.2 7.1-3.7 4.3c-.6.7-1.4 1.1-2.3 1.1-.5 0-.9-.1-1.3-.4-.4-.2-.6-.6-.6-1 0-.3.2-.6.5-.9l.6-.6-2.9-.7c-.4-.1-.6-.5-.4-.9.1-.3.4-.5.7-.5l4.9.6-1.4 1.5c-.2.2-.2.4 0 .5.1.1.3.1.4 0l3.4-2.6c.4-.3 1-.3 1.4 0 .4.3.5.9.2 1.3z"/>
              </svg>
              钉钉登录
            </el-button>
          </div>
        </div>
      </div>

    </div>

    <div class="login-copy">© {{ year }} 人数行政平台 · 浙江人数科技</div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { api } from '../api'
import { loadMe, state } from '../store'

const router = useRouter()
const route = useRoute()

const year = new Date().getFullYear()

/** 登录页标语，每 5 秒换一句 */
/** 登录页标语，每句固定两行，每 5 秒换一句 */
const slogans = [
  ['让数据多跑路，', '让员工少跑腿。'],
  ['数字赋能效率，', '科技驱动未来。'],
  ['精于业，简于行，', '让每一次审批都高效流转。'],
  ['数据创造价值，', '协同成就卓越。']
]
const sloganIdx = ref(0)

const sloganLines = computed(() => slogans[sloganIdx.value] || [])
let sloganTimer = null
const mode = ref('login')
const loading = ref(false)
const error = ref('')
const formRef = ref(null)

const form = reactive({ username: '', password: '', password2: '', display_name: '' })
const remember = ref(true)
const dingLoading = ref(false)

/** 跳钉钉授权页 */
async function loginByDingTalk() {
  dingLoading.value = true
  try {
    const r = await api.dingtalkLoginUrl()
    window.location.href = r.url
  } catch (e) {
    ElMessage.error(e.message || '钉钉登录不可用')
    dingLoading.value = false
  }
}

const statusText = ref('检测中…')
const build = ref(state.build || '—')

const REMEMBER_KEY = 'invoice-last-user'

onMounted(async () => {
  // 钉钉登录失败时，后端会带 ding_error 回到这里
  if (route.query.ding_error) {
    error.value = String(route.query.ding_error)
  }

  // 标语轮播
  sloganTimer = setInterval(() => {
    sloganIdx.value = (sloganIdx.value + 1) % slogans.length
  }, 5000)

  // 记住上次登录的用户名
  try {
    const last = localStorage.getItem(REMEMBER_KEY)
    if (last) {
      form.username = last
      remember.value = true
    }
  } catch (e) { /* 隐私模式下 localStorage 可能不可用 */ }

  // 探一下服务是否正常（不阻塞界面）
  try {
    const r = await api.ping()
    statusText.value = r.ok === false ? '异常' : '正常'
    build.value = r.build || state.build || '—'
  } catch (e) {
    statusText.value = '连不上'
  }
})

onBeforeUnmount(() => {
  if (sloganTimer) clearInterval(sloganTimer)
})

/** 忘了密码：系统里没有自助重置，只能找管理员 */
function forgot() {
  ElMessage.warning('请联系管理员重置密码（管理员在服务器上执行 部署/重置密码.sh）')
}

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
    if (mode.value === 'login') {
      try {
        if (remember.value) localStorage.setItem(REMEMBER_KEY, form.username.trim())
        else localStorage.removeItem(REMEMBER_KEY)
      } catch (e) { /* 忽略 */ }
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
