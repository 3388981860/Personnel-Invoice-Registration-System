<template>
  <div>
    <div class="panel" style="padding:0">
      <div class="panel-hd">
        <span>数据总览</span>
        <span class="sub">{{ sub }}</span>
      </div>

      <div style="padding:16px">
        <el-row :gutter="12">
          <el-col v-for="c in cards" :key="c.label" :span="6" style="margin-bottom:12px">
            <div class="kpi" :style="{ borderTopColor: c.color }">
              <div class="kpi-k">{{ c.label }}</div>
              <div class="kpi-v" :style="{ color: c.color }">{{ c.value }}<small>{{ c.unit }}</small></div>
            </div>
          </el-col>
        </el-row>

        <div ref="trendRef" class="chart" />
        <el-row :gutter="12">
          <el-col :span="8"><div ref="reviewRef" class="chart" /></el-col>
          <el-col :span="8"><div ref="auditRef" class="chart" /></el-col>
          <el-col :span="8"><div ref="personRef" class="chart" /></el-col>
        </el-row>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { primaryColor, skinVersion } from '../theme'

const data = ref({})
const trendRef = ref(null)
const reviewRef = ref(null)
const auditRef = ref(null)
const personRef = ref(null)
const charts = []

const sub = computed(() => {
  const o = data.value.overview || {}
  return `共 ${o.claims || 0} 张发票，${o.users || 0} 个在用账号`
})

const cards = computed(() => {
  const o = data.value.overview || {}
  const money = (v) => '¥' + Number(v || 0).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return [
    { label: '待审核单据', value: o.pending || 0, unit: '单', color: '#ED6C02' },
    { label: '已退回单据', value: o.returned || 0, unit: '单', color: '#C62828' },
    { label: '本月发票', value: o.month_count || 0, unit: '张', color: 'var(--el-color-primary)' },
    { label: '本月金额', value: money(o.month_amount), unit: '', color: 'var(--el-color-primary)' },
    { label: '累计发票', value: o.claims || 0, unit: '张', color: '#2E7D32' },
    { label: '累计金额', value: money(o.claims_amount), unit: '', color: '#2E7D32' },
    { label: '已通过单据', value: o.approved || 0, unit: '单', color: '#2E7D32' },
    { label: '平均审核时长', value: o.avg_review_hours ? o.avg_review_hours.toFixed(1) : '—', unit: o.avg_review_hours ? '小时' : '', color: '#6B7280' }
  ]
})

const AXIS = {
  axisLine: { lineStyle: { color: '#D1D5DB' } },
  axisLabel: { color: '#6B7280', fontSize: 11 },
  splitLine: { lineStyle: { color: '#F3F4F6' } }
}

/** 主色（带透明度），换肤后自动跟着变 */
function P(alpha) {
  const hex = primaryColor().replace('#', '')
  const n = parseInt(hex.length === 3
    ? hex.split('').map((x) => x + x).join('')
    : hex, 16)
  const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255
  return alpha === undefined ? primaryColor() : `rgba(${r},${g},${b},${alpha})`
}

function init(el) {
  const c = echarts.init(el)
  charts.push(c)
  return c
}

async function load() {
  try {
    const r = await api.stats()
    data.value = r
    draw(r)
  } catch (e) {
    ElMessage.error(e.message || '统计读取失败')
  }
}

function draw(r) {
  const trend = r.trend || []
  const trendChart = init(trendRef.value)
  trendChart.setOption({
    title: { text: '近 12 个月趋势', textStyle: { fontSize: 13, color: '#4B5563' }, left: 0, top: 0 },
    grid: { left: 8, right: 8, top: 42, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    legend: { data: ['张数（张）', '金额（元）'], right: 0, top: 0, textStyle: { fontSize: 11 } },
    xAxis: { type: 'category', data: trend.map(t => t.month), ...AXIS },
    yAxis: [
      { type: 'value', ...AXIS },
      { type: 'value', ...AXIS }
    ],
    series: [
      { name: '张数（张）', type: 'bar', barWidth: 18, itemStyle: { color: P(0.45), borderRadius: [2, 2, 0, 0] }, data: trend.map(t => t.count) },
      { name: '金额（元）', type: 'line', yAxisIndex: 1, smooth: true, symbolSize: 5, itemStyle: { color: P() }, areaStyle: { color: P(0.08) }, data: trend.map(t => t.amount) }
    ]
  })

  const o = r.overview || {}
  init(reviewRef.value).setOption({
    title: { text: '单据审核状态', textStyle: { fontSize: 13, color: '#4B5563' }, left: 0, top: 0 },
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie', radius: ['40%', '58%'], center: ['50%', '46%'],
      label: { show: false }, labelLine: { show: false },
      data: [
        { name: '待审核', value: o.pending || 0, itemStyle: { color: '#ED6C02' } },
        { name: '已退回', value: o.returned || 0, itemStyle: { color: '#C62828' } },
        { name: '已通过', value: o.approved || 0, itemStyle: { color: '#2E7D32' } }
      ]
    }]
  })

  init(auditRef.value).setOption({
    title: { text: '查验结果分布', textStyle: { fontSize: 13, color: '#4B5563' }, left: 0, top: 0 },
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie', radius: ['40%', '58%'], center: ['50%', '46%'],
      label: { show: false }, labelLine: { show: false },
      data: (r.by_audit || []).map(a => ({
        name: { 0: '未查验', 1: '通过', 2: '不通过', 3: '无需查验' }[a.status] || '其他',
        value: a.count,
        itemStyle: { color: { 0: '#C7CDD6', 1: '#2E7D32', 2: '#C62828', 3: '#9CA3AF' }[a.status] }
      }))
    }]
  })

  const persons = (r.by_person || []).slice(0, 8)
  init(personRef.value).setOption({
    title: { text: '报销人 TOP8（金额）', textStyle: { fontSize: 13, color: '#4B5563' }, left: 0, top: 0 },
    grid: { left: 8, right: 16, top: 42, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'value', ...AXIS },
    yAxis: { type: 'category', data: persons.map(p => p.name).reverse(), ...AXIS },
    series: [{ type: 'bar', barWidth: 12, itemStyle: { color: P(), borderRadius: [0, 2, 2, 0] }, data: persons.map(p => p.amount).reverse() }]
  })
}

function onResize() { charts.forEach(c => c.resize()) }

watch(skinVersion, () => {
  charts.forEach(c => c.dispose())
  charts.length = 0
  if (data.value.overview) draw(data.value)
})

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  charts.forEach(c => c.dispose())
  charts.length = 0
})
</script>

<style scoped>
.kpi {
  background: #fff; border: 1px solid #E5E7EB; border-top: 3px solid var(--el-color-primary);
  border-radius: 4px; padding: 12px 16px;
}
.kpi-k { font-size: 12px; color: #6B7280; margin-bottom: 4px; }
.kpi-v { font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; }
.kpi-v small { font-size: 12px; font-weight: 400; color: #9CA3AF; margin-left: 3px; }
.chart { width: 100%; height: 280px; margin-bottom: 12px; }
</style>
