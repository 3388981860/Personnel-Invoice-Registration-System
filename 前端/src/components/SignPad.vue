<template>
  <div v-if="modelValue" class="sign-mask" @click.self="$emit('update:modelValue', false)">
    <div class="sign-box">
      <div class="sign-hd">
        <span>请在下方签上你的名字</span>
        <el-button link @click="$emit('update:modelValue', false)">×</el-button>
      </div>
      <canvas
        ref="cv"
        class="sign-cv"
        @pointerdown="start"
        @pointermove="move"
        @pointerup="end"
        @pointerleave="end"
      />
      <div class="sign-tip">用鼠标按住写字，或手指 / 触控笔书写</div>
      <div class="sign-ft">
        <el-button @click="clear">重写</el-button>
        <el-button @click="$emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :disabled="empty" @click="confirm">确认签名</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'done'])

const cv = ref(null)
const empty = ref(true)
let ctx = null
let drawing = false

function setup() {
  const c = cv.value
  if (!c) return
  const dpr = window.devicePixelRatio || 1
  const rect = c.getBoundingClientRect()
  c.width = rect.width * dpr
  c.height = rect.height * dpr
  ctx = c.getContext('2d')
  ctx.scale(dpr, dpr)
  ctx.lineWidth = 2.4
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  ctx.strokeStyle = '#111827'
  clear()
}

function pos(e) {
  const r = cv.value.getBoundingClientRect()
  return { x: e.clientX - r.left, y: e.clientY - r.top }
}

function start(e) {
  if (!ctx) return
  drawing = true
  cv.value.setPointerCapture?.(e.pointerId)
  const p = pos(e)
  ctx.beginPath()
  ctx.moveTo(p.x, p.y)
  empty.value = false
}

function move(e) {
  if (!drawing || !ctx) return
  const p = pos(e)
  ctx.lineTo(p.x, p.y)
  ctx.stroke()
}

function end() {
  drawing = false
}

function clear() {
  if (!ctx) return
  const c = cv.value
  ctx.clearRect(0, 0, c.width, c.height)
  empty.value = true
}

function confirm() {
  const c = cv.value
  // 去掉四周空白：直接整张 canvas 导出，白底透明
  emit('done', c.toDataURL('image/png'))
  emit('update:modelValue', false)
}

watch(() => props.modelValue, (v) => {
  if (v) {
    // 等弹层渲染完再初始化画布
    setTimeout(setup, 30)
  }
})

onMounted(() => {
  if (props.modelValue) setup()
})
</script>

<style scoped>
.sign-mask {
  position: fixed; inset: 0; z-index: 3000;
  background: rgba(17, 24, 39, .45);
  display: flex; align-items: center; justify-content: center;
}
.sign-box {
  width: 560px; background: #fff; border-radius: 10px;
  box-shadow: 0 20px 60px rgba(0,0,0,.25); overflow: hidden;
}
.sign-hd {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 18px; border-bottom: 1px solid #EEF1F5;
  font-size: 14px; font-weight: 600; color: #1F2937;
}
.sign-cv {
  display: block; width: 100%; height: 220px;
  background: #FFFDF8;
  background-image: linear-gradient(#F3F4F6 1px, transparent 1px);
  background-size: 100% 44px;
  cursor: crosshair;
  touch-action: none;
}
.sign-tip {
  padding: 8px 18px 0; font-size: 12px; color: #9AA6B6;
}
.sign-ft {
  display: flex; justify-content: flex-end; gap: 10px;
  padding: 14px 18px;
}
</style>
