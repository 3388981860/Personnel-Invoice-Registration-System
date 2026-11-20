import { ref } from 'vue'

/**
 * 换肤：三套配色，存在 localStorage，刷新后保持。
 * b = 政务深蓝（默认） / a = 经典企业蓝 / c = 极简浅灰
 */
export const SKINS = [
  { key: 'b', name: '政务深蓝', dot: '#1E2D45' },
  { key: 'a', name: '经典企业蓝', dot: '#1677FF' },
  { key: 'c', name: '极简浅灰', dot: '#5B5BD6' }
]

const KEY = 'invoice-skin'
export const skin = ref('b')
/** 每次换肤 +1，图表之类需要重绘的地方 watch 它 */
export const skinVersion = ref(0)

export function applySkin(name, save = true) {
  if (!['a', 'b', 'c'].includes(name)) name = 'b'
  skin.value = name
  // b 是默认色板（写在 :root 里），去掉属性即可
  if (name === 'b') document.documentElement.removeAttribute('data-theme')
  else document.documentElement.setAttribute('data-theme', name)
  if (save) {
    try { localStorage.setItem(KEY, name) } catch (e) { /* 隐私模式忽略 */ }
  }
  skinVersion.value += 1
}

export function initSkin() {
  let saved = 'b'
  try { saved = localStorage.getItem(KEY) || 'b' } catch (e) { /* 忽略 */ }
  applySkin(saved, false)
}

/** 取当前主题的主色，给 echarts 用 */
export function primaryColor() {
  const v = getComputedStyle(document.documentElement)
    .getPropertyValue('--el-color-primary').trim()
  return v || '#1B4F8C'
}
