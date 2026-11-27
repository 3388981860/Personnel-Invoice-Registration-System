<template>
  <div class="wm-page">
    <div class="panel">
      <div class="panel-hd">
        <span>图片水印</span>
        <span class="sub">加水印、去水印地发图，文件不出服务器（在浏览器里处理）</span>
        <div class="hd-actions">
          <el-button size="small" @click="back">← 返回工具箱</el-button>
          <el-button size="small" @click="reset">重来</el-button>
        </div>
      </div>

      <div class="wm-body">
        <!-- 左：上传 + 设置 -->
        <div class="wm-left">
          <div class="wm-sec">1. 选图片</div>
          <div class="drop-zone" :class="{ over: dragOver }"
               @click="pick" @dragover.prevent="dragOver = true"
               @dragleave="dragOver = false" @drop.prevent="onDrop">
            <div class="dz-icon">🖼</div>
            <div class="dz-main">点这里选图片，或把图片拖进来</div>
            <div class="dz-sub">支持 JPG / PNG / WebP，一次可以选多张</div>
            <input ref="fileInput" type="file" accept="image/*" multiple hidden @change="onPick" />
          </div>
          <div v-if="files.length" class="file-list">
            <div v-for="(f, i) in files" :key="i" class="file-item"
                 :class="{ active: i === current }" @click="selectFile(i)">
              <img :src="f.thumb" class="fi-thumb" alt="" />
              <div class="fi-info">
                <div class="fi-name">{{ f.name }}</div>
                <div class="fi-size">{{ f.w }}×{{ f.h }} · {{ kb(f.size) }}</div>
              </div>
            </div>
          </div>

          <div class="wm-sec">2. 设置水印</div>
          <el-form label-position="top" class="wm-form">
            <el-form-item label="水印文字">
              <el-input v-model="opt.text" type="textarea" :rows="2" placeholder="比如：仅供 XX 项目使用" />
            </el-form-item>
            <div class="form-row">
              <el-form-item label="颜色">
                <el-color-picker v-model="opt.color" />
              </el-form-item>
              <el-form-item label="字号">
                <el-slider v-model="opt.size" :min="10" :max="100" style="width:130px" />
              </el-form-item>
              <el-form-item label="透明度">
                <el-slider v-model="opt.opacity" :min="5" :max="100" style="width:130px" />
              </el-form-item>
            </div>
            <div class="form-row">
              <el-form-item label="位置">
                <el-select v-model="opt.position" style="width:120px">
                  <el-option value="bottom-right" label="右下角" />
                  <el-option value="bottom-left" label="左下角" />
                  <el-option value="top-right" label="右上角" />
                  <el-option value="top-left" label="左上角" />
                  <el-option value="center" label="居中" />
                  <el-option value="tile" label="平铺满图" />
                </el-select>
              </el-form-item>
              <el-form-item label="字体">
                <el-select v-model="opt.font" style="width:130px">
                  <el-option value="Microsoft YaHei" label="微软雅黑" />
                  <el-option value="SimHei" label="黑体" />
                  <el-option value="SimSun" label="宋体" />
                  <el-option value="Arial" label="Arial" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="opt.position === 'tile'" label="倾斜">
                <el-slider v-model="opt.rotate" :min="-60" :max="0" style="width:110px" />
              </el-form-item>
            </div>
            <div class="quick">
              <span class="quick-label">快捷水印：</span>
              <el-button size="small" @click="usePreset('user')">当前用户 + 日期</el-button>
              <el-button size="small" @click="usePreset('company')">公司名 + 日期</el-button>
              <el-button size="small" @click="usePreset('internal')">仅供内部使用</el-button>
            </div>
          </el-form>

          <div class="wm-actions">
            <el-button type="primary" :disabled="!files.length" @click="redraw">应用水印</el-button>
            <el-button :disabled="!files.length" @click="downloadCurrent">下载这张</el-button>
            <el-button :disabled="files.length < 2" @click="downloadAll">全部下载</el-button>
          </div>
        </div>

        <!-- 右：预览 -->
        <div class="wm-right">
          <div class="wm-sec">3. 预览</div>
          <div v-if="!files.length" class="preview-empty">左边选图片，这里出预览</div>
          <div v-else class="preview-wrap">
            <canvas ref="cv" class="preview-cv"></canvas>
            <div class="preview-tip">
              {{ files[current]?.name }} · 原始 {{ files[current]?.w }}×{{ files[current]?.h }}
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { state } from '../store'
import { useRouter } from 'vue-router'

const router = useRouter()
const fileInput = ref(null)
const cv = ref(null)
const dragOver = ref(false)
const files = ref([])
const current = ref(0)

const opt = reactive({
  text: '',
  color: '#FFFFFF',
  size: 30,
  opacity: 70,
  position: 'bottom-right',
  font: 'Microsoft YaHei',
  rotate: -25
})

const today = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const userName = computed(() => state.me?.display_name || state.me?.username || '')

function usePreset(kind) {
  if (kind === 'user') opt.text = `${userName.value}  ${today()}\n仅供核对使用`
  else if (kind === 'company') opt.text = `人数（浙江）科技有限公司  ${today()}`
  else opt.text = '仅供内部使用，请勿外传'
  redraw()
}

async function selectFile(i) {
  current.value = i
  await nextTick()
  redraw()
}

function pick() {
  fileInput.value?.click()
}

function onPick(e) {
  addFiles(Array.from(e.target.files || []))
  e.target.value = ''
}

function onDrop(e) {
  dragOver.value = false
  addFiles(Array.from(e.dataTransfer.files || []).filter(f => f.type.startsWith('image/')))
}

async function addFiles(list) {
  const imgs = list.filter(f => f.type.startsWith('image/'))
  if (!imgs.length) {
    ElMessage.warning('请选图片文件')
    return
  }
  for (const f of imgs) {
    const url = URL.createObjectURL(f)
    const img = await loadImage(url)
    files.value.push({
      name: f.name,
      size: f.size,
      type: f.type,
      url,
      img,
      w: img.naturalWidth,
      h: img.naturalHeight,
      thumb: url
    })
  }
  if (!opt.text) usePreset('company')
  current.value = files.value.length - imgs.length
  await nextTick()
  redraw()
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = src
  })
}

/** 画水印 */
function redraw() {
  const f = files.value[current.value]
  const c = cv.value
  if (!f || !c) return

  c.width = f.w
  c.height = f.h
  const ctx = c.getContext('2d')
  ctx.drawImage(f.img, 0, 0)

  const lines = String(opt.text || '').split('\n').filter(s => s.trim())
  if (!lines.length) return

  // 字号跟着图片宽度走，小图大字都不会溢出
  const base = Math.max(11, Math.round(f.w * opt.size / 1000))
  ctx.font = `600 ${base}px "${opt.font}", "Microsoft YaHei", sans-serif`
  ctx.fillStyle = opt.color
  ctx.globalAlpha = opt.opacity / 100
  ctx.textBaseline = 'top'
  ctx.shadowColor = 'rgba(0,0,0,0.45)'
  ctx.shadowBlur = Math.max(2, base / 12)

  const lineH = Math.round(base * 1.35)
  const pad = Math.round(Math.min(f.w, f.h) * 0.035)
  const blockH = lineH * lines.length

  if (opt.position === 'tile') {
    ctx.save()
    ctx.translate(f.w / 2, f.h / 2)
    ctx.rotate((opt.rotate * Math.PI) / 180)
    const gapX = Math.round(base * (Math.max(...lines.map(s => s.length)) * 1.1 + 8))
    const gapY = Math.round(lineH * 2.6)
    const cols = Math.ceil((f.w + f.h) / gapX) + 2
    const rows = Math.ceil((f.w + f.h) / gapY) + 2
    ctx.textAlign = 'left'
    for (let r = -rows; r <= rows; r++) {
      for (let c = -cols; c <= cols; c++) {
        const x = c * gapX + (r % 2 ? gapX / 2 : 0)
        const y = r * gapY
        lines.forEach((line, i) => ctx.fillText(line, x, y + i * lineH))
      }
    }
    ctx.restore()
    return
  }

  let x = pad
  let y = pad
  const maxW = Math.max(...lines.map(s => ctx.measureText(s).width))
  if (opt.position.includes('right')) x = f.w - pad - maxW
  if (opt.position.includes('bottom')) y = f.h - pad - blockH
  if (opt.position === 'center') {
    x = (f.w - maxW) / 2
    y = (f.h - blockH) / 2
  }
  ctx.textAlign = 'left'
  lines.forEach((line, i) => ctx.fillText(line, x, y + i * lineH))
}

function canvasBlob(f) {
  return new Promise((resolve) => {
    const c = document.createElement('canvas')
    c.width = f.w
    c.height = f.h
    c.getContext('2d').drawImage(cv.value, 0, 0)
    const jpeg = /jpe?g/i.test(f.type)
    c.toBlob(b => resolve({ blob: b, ext: jpeg ? 'jpg' : 'png' }), jpeg ? 'image/jpeg' : 'image/png', 0.92)
  })
}

async function downloadCurrent() {
  const f = files.value[current.value]
  if (!f) return
  const { blob, ext } = await canvasBlob(f)
  saveBlob(blob, withSuffix(f.name, ext))
}

async function downloadAll() {
  for (let i = 0; i < files.value.length; i++) {
    current.value = i
    redraw()
    await new Promise(r => setTimeout(r, 60))
    const { blob, ext } = await canvasBlob(files.value[i])
    saveBlob(blob, withSuffix(files.value[i].name, ext))
    await new Promise(r => setTimeout(r, 260))
  }
}

function withSuffix(name, ext) {
  const base = name.replace(/\.[^.]+$/, '')
  return `${base}_水印.${ext}`
}

function saveBlob(blob, name) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 4000)
}

function kb(n) {
  return n > 1024 * 1024
    ? (n / 1024 / 1024).toFixed(1) + ' MB'
    : Math.round(n / 1024) + ' KB'
}

function back() {
  router.push({ name: 'tools' })
}

function reset() {
  files.value.forEach(f => URL.revokeObjectURL(f.url))
  files.value = []
  current.value = 0
  const c = cv.value
  if (c) {
    c.width = 0
    c.height = 0
  }
}

watch(() => [opt.text, opt.color, opt.size, opt.opacity, opt.position, opt.font, opt.rotate], () => {
  redraw()
})

onMounted(() => {
  // 打开就带一个像样的默认水印
  if (!opt.text) usePreset('company')
})
</script>

<style scoped>
.hd-actions { margin-left: auto; display: flex; gap: 8px; }
.wm-page { height: 100%; }
.wm-body {
  display: grid;
  grid-template-columns: 380px 1fr;
  gap: 18px;
  padding: 14px 16px 18px;
  align-items: start;
}
.wm-sec {
  font-size: 13px; font-weight: 600; color: #1F2937;
  margin: 2px 0 8px; padding-left: 8px; border-left: 3px solid var(--brand-accent);
}
.drop-zone {
  border: 1.5px dashed #C9D4E3; border-radius: 8px;
  padding: 22px 16px; text-align: center; cursor: pointer;
  background: #FAFCFF; transition: all .18s;
}
.drop-zone:hover, .drop-zone.over { border-color: var(--brand-accent); background: #F2F7FF; }
.dz-icon { font-size: 26px; }
.dz-main { font-size: 13px; color: #374151; margin-top: 6px; }
.dz-sub { font-size: 12px; color: #9AA6B6; margin-top: 4px; }

.file-list { margin-top: 10px; max-height: 170px; overflow: auto; }
.file-item {
  display: flex; align-items: center; gap: 10px;
  padding: 6px 8px; border-radius: 6px; cursor: pointer;
  border: 1px solid transparent;
}
.file-item:hover { background: #F6F9FE; }
.file-item.active { background: #EDF4FF; border-color: #C7DCFB; }
.fi-thumb { width: 40px; height: 40px; object-fit: cover; border-radius: 4px; }
.fi-name { font-size: 12.5px; color: #1F2937; max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fi-size { font-size: 11.5px; color: #9AA6B6; }

.wm-form { margin-top: 4px; }
.wm-form :deep(.el-form-item) { margin-bottom: 10px; }
.form-row { display: flex; gap: 14px; flex-wrap: wrap; }
.quick { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 4px; }
.quick-label { font-size: 12px; color: #6B7280; }
.wm-actions { display: flex; gap: 8px; margin-top: 12px; }

.preview-wrap { display: flex; flex-direction: column; gap: 8px; }
.preview-cv {
  max-width: 100%; max-height: calc(100vh - 240px);
  border-radius: 6px; border: 1px solid #E5EAF2; background: #F8FAFC;
  object-fit: contain;
}
.preview-tip { font-size: 12px; color: #9AA6B6; }
.preview-empty {
  border: 1px dashed #E0E7F1; border-radius: 8px;
  padding: 60px 0; text-align: center; color: #B4BFCF; font-size: 13px;
}
</style>
