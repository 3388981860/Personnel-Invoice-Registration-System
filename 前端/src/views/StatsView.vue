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

        <!-- 合同回款 -->
        <div v-if="contract.totals" class="sec-title">
          合同回款
          <span class="sub">合同 <b>{{ contract.totals.contract_count }}</b> 份 ·
            {{ contract.totals.project_count }} 个项目</span>
        </div>
        <el-row v-if="contract.totals" :gutter="12">
          <el-col :span="6" style="margin-bottom:12px">
            <div class="kpi" style="border-top-color:#1B4F8C">
              <div class="kpi-k">收入合同额</div>
              <div class="kpi-v" style="color:#1B4F8C">{{ money(contract.totals.income_total) }}</div>
            </div>
          </el-col>
          <el-col :span="6" style="margin-bottom:12px">
            <div class="kpi" style="border-top-color:#C8A45C">
              <div class="kpi-k">已开票</div>
              <div class="kpi-v" style="color:#C8A45C">{{ money(contract.totals.invoiced) }}</div>
            </div>
          </el-col>
          <el-col :span="6" style="margin-bottom:12px">
            <div class="kpi" style="border-top-color:#16A34A">
              <div class="kpi-k">已回款</div>
              <div class="kpi-v" style="color:#16A34A">{{ money(contract.totals.received) }}</div>
            </div>
          </el-col>
          <el-col :span="6" style="margin-bottom:12px">
            <div class="kpi" style="border-top-color:#DC2626">
              <div class="kpi-k">外协应付 / 已付</div>
              <div class="kpi-v" style="color:#DC2626">
                {{ money(contract.totals.cost_total) }}
                <small style="font-size:12px;color:#6B7280">
                  / {{ money(contract.totals.cost_paid) }}
                </small>
              </div>
            </div>
          </el-col>
        </el-row>
        <el-alert
          v-if="contract.totals && !contract.totals.income_total"
          type="info"
          :closable="false"
          show-icon
          style="margin:4px 0 12px"
          title="合同金额还没录，所以这里都是 0"
          description="去「合同台账」点开项目，每份合同点「编辑」把合同金额填上，这里的图表和比例就会动起来。"
        />
        <el-row v-else-if="contract.totals" :gutter="12">
          <el-col :span="12"><div ref="unitRef" class="chart" /></el-col>
          <el-col :span="12"><div ref="watchRef" class="chart" /></el-col>
        </el-row>

        <!-- 按项目集 -->
        <template v-if="contract.by_set && contract.by_set.length">
          <div class="sec-title" style="margin-top:16px">
            按项目集
            <span class="sub">共 {{ contract.by_set.length }} 个集</span>
          </div>
          <div class="set-table">
            <div class="set-row set-head">
              <span class="c-name">项目集</span>
              <span class="c-num">项目</span>
              <span class="c-num">合同</span>
              <span class="c-money">收入合同</span>
              <span class="c-money">已开票</span>
              <span class="c-money">已回款</span>
              <span class="c-bar">回款进度</span>
            </div>
            <div v-for="s in contract.by_set" :key="s.id" class="set-row">
              <span class="c-name">
                {{ s.name }}
                <span class="set-code mono">{{ s.code }}</span>
              </span>
              <span class="c-num">{{ s.projects }}</span>
              <span class="c-num">{{ s.contracts }}</span>
              <span class="c-money mono">{{ money(s.income) }}</span>
              <span class="c-money mono">{{ money(s.invoiced) }}</span>
              <span class="c-money mono">{{ money(s.received) }}</span>
              <span class="c-bar">
                <el-progress
                  :percentage="s.paid_ratio || 0"
                  :stroke-width="10"
                  :color="ratioColor(s.paid_ratio)"
                />
                <span class="sr-remain" v-if="s.remain > 0">未收 {{ money(s.remain) }}</span>
              </span>
            </div>
          </div>
        </template>

        <div class="sec-title">发票与查验</div>
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
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { primaryColor, skinVersion } from '../theme'

const data = ref({})
const trendRef = ref(null)
const unitRef = ref(null)
const watchRef = ref(null)
const reviewRef = ref(null)
const auditRef = ref(null)
const personRef = ref(null)
const charts = []

const sub = computed(() => {
  const o = data.value.overview || {}
  return `共 ${o.claims || 0} 张发票，${o.users || 0} 个在用账号`
})

const contract = reactive({ totals: null, by_unit: [], watch: [], by_set: [] })

/** 回款进度条的颜色：低红、中黄、高绿 */
function ratioColor(p) {
  const v = Number(p || 0)
  if (v >= 90) return '#16A34A'
  if (v >= 50) return '#C8A45C'
  if (v > 0) return '#E0A32E'
  return '#CBD5E1'
}

function money(v) {
  return '¥' + Number(v || 0).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

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

async function loadContract() {
  try {
    const r = await api.contractsOverview()
    contract.totals = r.totals
    contract.by_unit = r.by_unit || []
  contract.by_set = r.by_set || []
    contract.watch = r.watch || []
    nextTick(() => drawContract())
  } catch (e) {
    // 合同模块没数据就不显示，不用报错
  }
}

function drawContract() {
  const primary = getComputedStyle(document.documentElement)
    .getPropertyValue('--el-color-primary').trim() || '#1B4F8C'

  if (unitRef.value) {
    const units = contract.by_unit.slice(0, 8)
    const chart = echarts.init(unitRef.value)
    chart.setOption({
      title: { text: '各单位收入合同额', left: 'center', textStyle: { fontSize: 13 } },
      tooltip: { trigger: 'axis' },
      grid: { left: 60, right: 20, top: 44, bottom: 60 },
      xAxis: {
        type: 'category',
        data: units.map(x => x.unit),
        axisLabel: { interval: 0, rotate: 30, fontSize: 11 }
      },
      yAxis: { type: 'value' },
      series: [{
        type: 'bar',
        data: units.map(x => x.income),
        barMaxWidth: 30,
        itemStyle: { color: primary, borderRadius: [3, 3, 0, 0] }
      }]
    })
    charts.push(chart)
  }

  if (watchRef.value) {
    const list = contract.watch.slice(0, 8)
    const chart = echarts.init(watchRef.value)
    chart.setOption({
      title: { text: '回款进度（未收最多的）', left: 'center', textStyle: { fontSize: 13 } },
      tooltip: {
        trigger: 'axis',
        formatter: (ps) => {
          const i = ps[0].dataIndex
          const w = list[i]
          return `${w.project}<br/>应收 ${money(w.amount)}<br/>`
            + `已收 ${money(w.received)}（${w.paid_ratio}%）<br/>`
            + `未收 ${money(w.remain)}`
        }
      },
      grid: { left: 110, right: 40, top: 44, bottom: 30 },
      xAxis: { type: 'value' },
      yAxis: {
        type: 'category',
        data: list.map(x => (x.project || '').slice(0, 10)),
        axisLabel: { fontSize: 11 }
      },
      series: [{
        type: 'bar',
        data: list.map(x => x.remain),
        barMaxWidth: 18,
        itemStyle: { color: '#DC2626', borderRadius: [0, 3, 3, 0] }
      }]
    })
    charts.push(chart)
  }
}

onMounted(() => {
  load()
  loadContract()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  charts.forEach(c => c.dispose())
  charts.length = 0
})
</script>

<style scoped>
.set-table { border: 1px solid #E8EDF4; border-radius: 6px; overflow: hidden; }
.set-row {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 14px; font-size: 13px; color: var(--brand-text);
  border-bottom: 1px solid #F1F4F8;
}
.set-row:last-child { border-bottom: none; }
.set-row.set-head {
  background: #F5F8FC; font-size: 12px; color: #6B7280; font-weight: 500;
  padding: 8px 14px;
}
.c-name { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.set-code { font-size: 11px; color: #9AA6B6; margin-left: 6px; }
.c-num { flex: 0 0 52px; text-align: right; color: #4B5563; }
.c-money { flex: 0 0 108px; text-align: right; font-variant-numeric: tabular-nums; }
.c-bar { flex: 0 0 220px; display: flex; align-items: center; gap: 8px; }
.c-bar .el-progress { flex: 1; }
.sr-remain { flex: none; font-size: 11.5px; color: #C62828; }
.sec-title {
  margin: 16px 0 10px; padding-bottom: 6px;
  font-size: 13px; font-weight: 600; color: #374151;
  border-bottom: 1px solid #E5E7EB;
  display: flex; align-items: baseline; gap: 10px;
}
.sec-title .sub { font-weight: 400; font-size: 12px; color: #9CA3AF; }
.kpi {
  background: #fff; border: 1px solid #E5E7EB; border-top: 3px solid var(--el-color-primary);
  border-radius: 4px; padding: 12px 16px;
}
.kpi-k { font-size: 12px; color: #6B7280; margin-bottom: 4px; }
.kpi-v { font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; }
.kpi-v small { font-size: 12px; font-weight: 400; color: #9CA3AF; margin-left: 3px; }
.chart { width: 100%; height: 280px; margin-bottom: 12px; }
</style>
