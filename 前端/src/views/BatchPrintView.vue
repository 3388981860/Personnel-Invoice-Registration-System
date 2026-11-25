<template>
  <div class="print-page">
    <div class="toolbar no-print">
      <el-button @click="back">返回</el-button>
      <el-button type="primary" @click="doPrint">打印</el-button>
      <span class="hint">A4 横向 · 实际大小 · 去掉页眉页脚</span>
    </div>

    <div v-if="!b" class="loading">加载中…</div>

    <div v-else class="sheet">
      <!-- 标题区 -->
      <div class="head">
        <div class="head-left"></div>
        <div class="head-mid">
          <h1 class="title">费 用 报 销 单</h1>
          <div class="date-line">{{ dateParts }}</div>
        </div>
        <div class="head-right">
          <div class="no-box"><span>编号</span><b>{{ b.batch_no }}</b></div>
        </div>
      </div>

      <!-- 表单主体 -->
      <table class="form">
        <colgroup>
          <col style="width:76px" />
          <col style="width:300px" />
          <col v-for="i in 9" :key="'c' + i" style="width:34px" />
          <col style="width:180px" />
          <col style="width:72px" />
        </colgroup>
        <tbody>
          <!-- 第一行：部门 / 项目名称 -->
          <tr class="h-row">
            <th>部　门</th>
            <td colspan="5">{{ b.dept_name || '' }}</td>
            <th colspan="2">项目名称</th>
            <td colspan="5">{{ b.project_name || '' }}</td>
          </tr>

          <!-- 表头带：摘要 / 金额 / 科目 / 附单据数 -->
          <tr class="band">
            <th colspan="2" rowspan="2">摘　　要</th>
            <th colspan="9">金　　额</th>
            <th rowspan="2">科　　目</th>
            <th rowspan="2">附单<br />据数</th>
          </tr>
          <tr class="band">
            <th v-for="u in units" :key="u" class="unit">{{ u }}</th>
          </tr>

          <!-- 正文 4 行 -->
          <tr class="body-row">
            <td colspan="2" rowspan="4" class="summary">{{ summary }}</td>
            <td v-for="(c, i) in gridRow(0)" :key="'r0' + i">{{ c.text }}</td>
            <td rowspan="4" class="ke-mu"></td>
            <td rowspan="4" class="count-cell">{{ b.count || 0 }}</td>
          </tr>
          <tr class="body-row">
            <td v-for="(c, i) in gridRow(1)" :key="'r1' + i">{{ c.text }}</td>
          </tr>
          <tr class="body-row">
            <td v-for="(c, i) in gridRow(2)" :key="'r2' + i">{{ c.text }}</td>
          </tr>
          <tr class="body-row">
            <td v-for="(c, i) in gridRow(3)" :key="'r3' + i"
                :class="{ 'yen': c.yen }">{{ c.text }}</td>
          </tr>

          <!-- 大写行 -->
          <tr class="daxie-row">
            <th class="daxie-label">人民币<br />（大写）</th>
            <td colspan="1" class="daxie-text">{{ uppercase }}</td>
            <td v-for="u in daxieUnits" :key="'d' + u" class="daxie-unit">{{ u }}</td>
            <td colspan="2"></td>
          </tr>

          <!-- 审批行 -->
          <tr class="sign-row">
            <th colspan="3">领导批示</th>
            <th colspan="3">财务经理</th>
            <th colspan="4">分管领导</th>
            <th colspan="3">部门经理</th>
          </tr>
        </tbody>
      </table>

      <!-- 底部签字行（框外） -->
      <div class="foot-sign">
        <span>财务审核</span>
        <span>出　纳</span>
        <span>报销人　{{ b.person || '' }}</span>
        <span>领款人　{{ b.payee || '' }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'

const route = useRoute()
const router = useRouter()
const b = ref(null)

const units = ['百', '十', '万', '千', '百', '十', '元', '角', '分']
const daxieUnits = ['佰', '拾', '万', '仟', '佰', '拾', '元', '角', '分']

function money(v) {
  return '¥' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function cnDate(s) {
  if (!s) return ''
  const m = String(s).match(/(\d{4})-(\d{1,2})-(\d{1,2})/)
  if (!m) return s
  return `${m[1]} 年 ${Number(m[2])} 月 ${Number(m[3])} 日`
}

const dateParts = computed(() => {
  const s = b.value?.reimburse_date || b.value?.created_at || ''
  const m = String(s).match(/(\d{4})-(\d{1,2})-(\d{1,2})/)
  if (!m) return '年　　月　　日'
  return `${m[1]} 年 ${Number(m[2])} 月 ${Number(m[3])} 日`
})

/** 金额按 百十万千百十元角分 拆位 */
const digits = computed(() => {
  const cents = Math.round(Number(b.value?.amount || 0) * 100)
  const s = String(cents).padStart(9, '0')
  return s.split('')
})

/** 每行 9 个格子；最后一行为数字行 */
function gridRow(idx) {
  if (idx < 3) {
    const empty = []
    for (let i = 0; i < 9; i++) empty.push({ text: '', yen: false })
    return empty
  }
  const first = digits.value.findIndex(d => d !== '0')
  return digits.value.map((d, i) => ({
    text: d === '0' ? '' : d,
    yen: i === first
  }))
}

function intToCn(n) {
  const d = ['零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖']
  const u = ['', '拾', '佰', '仟']
  const big = ['', '万', '亿', '万亿']
  if (n === 0) return '零'
  let out = ''
  let seg = 0
  let rest = n
  while (rest > 0) {
    const part = rest % 10000
    let partStr = ''
    let x = part
    let pos = 0
    let zero = false
    while (x > 0) {
      const digit = x % 10
      if (digit === 0) {
        if (partStr) zero = true
      } else {
        partStr = d[digit] + u[pos] + (zero ? '零' : '') + partStr
        zero = false
      }
      x = Math.floor(x / 10)
      pos++
    }
    if (partStr) {
      out = partStr + big[seg] + (out && part < 1000 ? '零' : '') + out
    } else if (out) {
      out = '零' + out
    }
    rest = Math.floor(rest / 10000)
    seg++
  }
  return out.replace(/零+/g, '零').replace(/零$/, '')
}

const uppercase = computed(() => {
  const v = Number(b.value?.amount || 0)
  if (!v) return '零元整'
  const yuan = Math.floor(v)
  const cents = Math.round((v - yuan) * 100)
  let s = intToCn(yuan) + '元'
  if (cents === 0) return s + '整'
  const jiao = Math.floor(cents / 10)
  const fen = cents % 10
  s += jiao === 0 ? '零' : intToCn(jiao) + '角'
  s += fen > 0 ? intToCn(fen) + '分' : '整'
  return s
})

const summary = computed(() => {
  const v = b.value || {}
  if (v.note && v.note !== v.project_name) return v.note
  if (v.project_name) return v.project_name
  return '报销发票 ' + (v.count || 0) + ' 张'
})

function doPrint() {
  window.print()
}

function back() {
  router.push({ name: 'ledger' })
}

onMounted(async () => {
  try {
    const r = await api.batch(route.params.id)
    b.value = r.batch || r
  } catch (e) {
    b.value = null
  }
})
</script>

<style scoped>
.print-page { min-height: 100vh; background: #F3F4F6; padding: 20px 0 50px; }
.toolbar { max-width: 1000px; margin: 0 auto 14px; display: flex; align-items: center; gap: 12px; }
.toolbar .hint { font-size: 12px; color: #6B7280; }

.sheet {
  width: 297mm; min-height: 150mm; margin: 0 auto; background: #fff;
  padding: 12mm 14mm 8mm; box-sizing: border-box;
  box-shadow: 0 6px 24px rgba(0,0,0,.10);
}

/* 标题区 */
.head { display: flex; align-items: flex-start; }
.head-left { width: 150px; }
.head-mid { flex: 1; text-align: center; }
.head-right { width: 150px; display: flex; justify-content: flex-end; }
.title {
  font-size: 26px; font-weight: 700; letter-spacing: 10px;
  margin: 0 0 6px; padding-bottom: 4px;
  border-bottom: 3px double #222;
  display: inline-block;
}
.date-line { font-size: 14px; letter-spacing: 2px; margin-top: 8px; }
.no-box {
  display: flex; align-items: stretch; border: 1px solid #333; font-size: 12px;
}
.no-box span { padding: 4px 8px; background: #FAFAFA; border-right: 1px solid #333; }
.no-box b { padding: 4px 10px; font-weight: 400; font-family: Consolas, monospace; }

/* 表格 */
.form {
  width: 100%; border-collapse: collapse; table-layout: fixed;
  margin-top: 10px; border: 1px solid #333;
}
.form th, .form td {
  border: 1px solid #333; padding: 4px 6px;
  font-size: 13px; line-height: 1.5; vertical-align: middle;
}
.form th { background: #F7F9FA; font-weight: 500; text-align: center; }
.h-row th, .h-row td { height: 34px; }
.band th { height: 26px; }
.band .unit { font-size: 12px; padding: 2px 0; }
.body-row td { height: 30px; }
.body-row td { text-align: center; font-size: 16px; font-variant-numeric: tabular-nums; }
.summary { font-size: 14px; text-align: left !important; vertical-align: top; padding: 8px 10px; }
.count-cell { font-size: 13px; text-align: center; }
td.yen::before { content: '¥'; font-size: 11px; margin-right: 1px; }

.daxie-row th, .daxie-row td { height: 30px; }
.daxie-label { font-size: 12px; line-height: 1.35; }
.daxie-text { font-size: 15px; letter-spacing: 1px; text-align: center; }
.daxie-unit { font-size: 12px; text-align: center; padding: 2px 0; }
.sign-row th { height: 46px; font-size: 13px; vertical-align: middle; }

/* 底部签字 */
.foot-sign {
  display: flex; justify-content: space-between;
  margin-top: 12px; padding: 0 10px;
  font-size: 14px; letter-spacing: 1px;
}
.loading { text-align: center; padding: 60px; color: #6B7280; }

@media print {
  .no-print { display: none !important; }
  .print-page { background: #fff; padding: 0; }
  .sheet { box-shadow: none; width: auto; min-height: auto; padding: 0; }
  @page { size: A4 landscape; margin: 10mm; }
}
</style>
