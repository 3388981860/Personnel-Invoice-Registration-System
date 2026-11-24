<template>
  <div class="print-page">
    <div class="toolbar no-print">
      <el-button @click="back">返回</el-button>
      <el-button type="primary" @click="doPrint">打印</el-button>
      <span class="hint">按 A4 竖版排版，打印时选「实际大小」、去掉页眉页脚</span>
    </div>

    <div v-if="!r" class="loading">加载中…</div>

    <div v-else class="sheet">
      <h1 class="title">人数(浙江)科技有限公司发票开具申请单</h1>

      <table class="form">
        <colgroup>
          <col style="width:110px" />
          <col style="width:220px" />
          <col style="width:110px" />
          <col />
        </colgroup>
        <tbody>
          <tr>
            <th>单位全称</th>
            <td colspan="3">{{ r.buyer_name || '' }}</td>
          </tr>
          <tr>
            <th>企业税号</th>
            <td colspan="3">{{ r.buyer_tax_no || '' }}</td>
          </tr>
          <tr>
            <th>开票金额</th>
            <td class="money-cell">{{ money(r.amount) }}</td>
            <th>开票内容</th>
            <td>{{ r.content || '' }}</td>
          </tr>
          <tr>
            <th>备　　注</th>
            <td colspan="3">{{ r.note || '' }}</td>
          </tr>
          <tr>
            <th>开票类型</th>
            <td colspan="3">
              <span class="box">{{ isType('专用发票') ? '☑' : '☐' }}</span> 专用发票
              <span class="gap" />
              <span class="box">{{ isType('普通发票') ? '☑' : '☐' }}</span> 普通发票
            </td>
          </tr>
          <tr>
            <th>开票部门</th>
            <td colspan="3">
              <span class="box">{{ r.dept === '市场部' ? '☑' : '☐' }}</span> 市场部
              <span class="gap" />
              <span class="box">{{ r.dept === '技术部' ? '☑' : '☐' }}</span> 技术部
            </td>
          </tr>
          <tr>
            <th>收款银行</th>
            <td colspan="3">
              <span class="box">{{ r.bank ? '☑' : '☐' }}</span> {{ r.bank || '温州银行' }}
              <span v-if="r.account" class="muted">（账号：{{ r.account }}）</span>
            </td>
          </tr>
          <tr>
            <th>申 请 人</th>
            <td class="sign-cell">
              <img v-if="signUrl" :src="signUrl" class="sign-img" alt="签名" />
              <span v-else class="muted">（未签名）</span>
            </td>
            <th>代办人</th>
            <td>{{ r.agent_name || '' }}</td>
          </tr>
        </tbody>
      </table>

      <div class="foot">
        <span>单号：{{ r.no }}</span>
        <span v-if="r.sign_at">签名时间：{{ r.sign_at }}</span>
        <span v-if="r.reviewed_at">审核时间：{{ r.reviewed_at }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { state } from '../store'

const route = useRoute()
const router = useRouter()
const r = ref(null)
const signUrl = ref('')

function money(v) {
  return '¥' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function isType(t) {
  return (r.value?.inv_type || '').includes(t.slice(0, 2))
}

function doPrint() {
  window.print()
}

function back() {
  router.push({ name: 'invoice-requests' })
}

onMounted(async () => {
  try {
    const d = await api.invoiceRequest(route.params.id)
    r.value = d.request
    // 客户档案里补开户行账号
    if (r.value?.customer_id) {
      const cs = await api.customers({})
      const c = (cs.customers || []).find(x => x.id === r.value.customer_id)
      if (c && c.account) r.value.account = c.account
    }
    if (r.value?.sign_rel) {
      signUrl.value = `/api/invoice_request/sign_img?id=${route.params.id}&t=${Date.now()}`
    }
  } catch (e) {
    r.value = null
  }
})
</script>

<style scoped>
.print-page {
  min-height: 100vh; background: #F3F4F6; padding: 24px 0 60px;
}
.toolbar {
  max-width: 780px; margin: 0 auto 16px; display: flex; align-items: center; gap: 12px;
}
.toolbar .hint { font-size: 12px; color: #6B7280; }
.sheet {
  width: 210mm; min-height: 148mm; margin: 0 auto; background: #fff;
  padding: 18mm 16mm; box-sizing: border-box;
  box-shadow: 0 6px 24px rgba(0,0,0,.10);
}
.title {
  font-size: 20px; font-weight: 700; text-align: center;
  letter-spacing: 2px; margin: 0 0 16px;
}
.form { width: 100%; border-collapse: collapse; table-layout: fixed; }
.form th, .form td {
  border: 1px solid #333; padding: 9px 10px;
  font-size: 14px; line-height: 1.6; vertical-align: middle;
}
.form th { background: #FAFAFA; font-weight: 500; text-align: center; }
.money-cell { text-align: right; font-variant-numeric: tabular-nums; }
.box { font-size: 15px; margin-right: 3px; }
.gap { display: inline-block; width: 30px; }
.sign-cell { height: 64px; text-align: center; }
.sign-img { max-height: 56px; max-width: 100%; object-fit: contain; }
.muted { color: #9AA6B6; font-size: 12px; }
.foot {
  display: flex; justify-content: space-between;
  margin-top: 12px; font-size: 12px; color: #6B7280;
}
.loading { text-align: center; padding: 60px; color: #6B7280; }

@media print {
  .no-print { display: none !important; }
  .print-page { background: #fff; padding: 0; }
  .sheet { box-shadow: none; width: auto; padding: 0; min-height: auto; }
  @page { size: A4 portrait; margin: 16mm; }
}
</style>
