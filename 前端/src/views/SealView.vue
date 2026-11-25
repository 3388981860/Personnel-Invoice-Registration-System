<template>
  <div>
    <div class="panel">
      <div class="panel-hd">
        <span>用印申请</span>
        <span class="sub">提交后到钉钉审批，审批人：{{ approver }}</span>
        <div style="margin-left:auto;display:flex;gap:8px">
          <el-button size="small" :loading="syncing" @click="refreshStatus">刷新状态</el-button>
          <el-button type="primary" size="small" @click="openForm">+ 新建申请</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows" size="small" style="width:100%"
                empty-text="还没有用印申请">
        <el-table-column prop="dt_business_id" label="审批编号" width="210">
          <template #default="{ row }">
            <span class="mono">{{ row.dt_business_id || '同步中…' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="doc_name" label="用印文件名称" min-width="260" show-overflow-tooltip />
        <el-table-column prop="doc_category" label="文件类别" width="100" align="center" />
        <el-table-column prop="seal_type" label="印章" width="90" align="center" />
        <el-table-column prop="copies" label="份数" width="72" align="center" />
        <el-table-column prop="applicant_name" label="经办人" width="100" align="center" />
        <el-table-column prop="biz_date" label="日期" width="110" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ row.status_text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="提交时间" width="160" align="center" />
        <el-table-column prop="finish_time" label="完成时间" width="160" align="center">
          <template #default="{ row }">{{ row.finish_time || '—' }}</template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建申请 -->
    <el-dialog v-model="dlg" title="新建用印申请" width="620px" append-to-body>
      <el-form :model="form" label-width="110px">
        <el-form-item label="经办人" required>
          <el-input v-model="form.person" placeholder="默认自己" />
        </el-form-item>
        <el-form-item label="日期" required>
          <el-date-picker v-model="form.biz_date" type="date" value-format="YYYY-MM-DD"
                          style="width:100%" />
        </el-form-item>
        <el-form-item label="用印文件名称" required>
          <el-input v-model="form.doc_name" type="textarea" :rows="2"
                    placeholder="例如：2026年度瓯海区政务信息系统集中运维支撑服务项目合同" />
        </el-form-item>
        <el-form-item label="文件份数" required>
          <el-input-number v-model="form.copies" :min="1" :max="999" />
        </el-form-item>
        <el-form-item label="文件类别" required>
          <el-select v-model="form.doc_category" style="width:100%">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="加盖何种印章" required>
          <el-select v-model="form.seal_type" style="width:100%">
            <el-option v-for="s in seals" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="用印文件照片" required>
          <div style="width:100%">
            <input ref="fileInput" type="file" accept="image/*" multiple
                   style="display:none" @change="onPick" />
            <el-button size="small" :loading="uploading" @click="fileInput?.click()">
              + 添加图片
            </el-button>
            <span class="text-muted" style="font-size:12px;margin-left:8px">
              必填（钉钉模板要求），已选 {{ photos.length }} 张
            </span>
            <div v-if="photos.length" class="thumbs">
              <span v-for="(p, i) in photos" :key="i" class="thumb">
                图 {{ i + 1 }}
                <span class="thumb-x" @click="photos.splice(i, 1)">×</span>
              </span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="用印文件附件" required>
          <div style="width:100%">
            <input ref="attInput" type="file" multiple style="display:none" @change="onPickAtt" />
            <el-button size="small" :loading="attUploading" @click="attInput?.click()">
              + 添加附件
            </el-button>
            <span class="text-muted" style="font-size:12px;margin-left:8px">
              必填，已选 {{ attachments.length }} 个
            </span>
            <div v-if="attachments.length" class="thumbs">
              <span v-for="(a, i) in attachments" :key="i" class="thumb">
                {{ a.name }}
                <span class="thumb-x" @click="attachments.splice(i, 1)">×</span>
              </span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>

      <el-alert type="info" :closable="false" show-icon
                title="提交后生成钉钉审批单：审批人李洪兵，抄送王潘亮、黄周瑜。结果约 1 分钟同步回这里。" />

      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交到钉钉</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { state } from '../store'

const rows = ref([])
const loading = ref(false)
const syncing = ref(false)
const dlg = ref(false)
const submitting = ref(false)
const uploading = ref(false)
const categories = ref(['公告类', '规章制度类', '合同类', '其他'])
const seals = ref(['公章', '合同章', '法人章', '其他'])
const approver = ref('李洪兵')
const photos = ref([])
const fileInput = ref(null)
const attachments = ref([])
const attInput = ref(null)
const attUploading = ref(false)

const form = reactive({
  person: '',
  biz_date: '',
  doc_name: '',
  copies: 1,
  doc_category: '合同类',
  seal_type: '公章',
  note: ''
})

function tagType(s) {
  if (s === 'approved') return 'success'
  if (s === 'refused') return 'danger'
  if (s === 'canceled') return 'info'
  return 'warning'
}

async function load() {
  loading.value = true
  try {
    const r = await api.sealList()
    rows.value = r.rows || []
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  } finally {
    loading.value = false
  }
}

async function refreshStatus() {
  syncing.value = true
  try {
    await api.sealSync()
    await load()
    ElMessage.success('状态已刷新')
  } catch (e) {
    ElMessage.error(e.message || '刷新失败')
  } finally {
    syncing.value = false
  }
}

async function openForm() {
  const today = new Date()
  const iso = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0')
    + '-' + String(today.getDate()).padStart(2, '0')
  form.person = state.me?.display_name || state.me?.username || ''
  form.biz_date = iso
  form.doc_name = ''
  form.copies = 1
  form.doc_category = '合同类'
  form.seal_type = '公章'
  form.note = ''
  photos.value = []
  attachments.value = []
  dlg.value = true
  try {
    const r = await api.sealOptions()
    categories.value = r.categories || categories.value
    seals.value = r.seals || seals.value
    approver.value = r.approver || approver.value
  } catch (e) {
    // 用默认值
  }
}

async function onPick(e) {
  const files = Array.from(e.target.files || [])
  if (!files.length) return
  uploading.value = true
  try {
    for (const f of files) {
      const r = await api.sealUpload(f)
      if (r.media_id) photos.value.push(r.media_id)
    }
    ElMessage.success(`已上传 ${files.length} 张`)
  } catch (err) {
    ElMessage.error(err.message || '上传失败')
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function onPickAtt(e) {
  const files = Array.from(e.target.files || [])
  if (!files.length) return
  attUploading.value = true
  try {
    for (const f of files) {
      const r = await api.sealUpload(f, 'file')
      if (r.media_id) attachments.value.push({ name: f.name, media_id: r.media_id })
    }
    ElMessage.success(`已上传 ${files.length} 个附件`)
  } catch (err) {
    ElMessage.error(err.message || '附件上传失败')
  } finally {
    attUploading.value = false
    if (attInput.value) attInput.value.value = ''
  }
}

async function submit() {
  if (!form.doc_name.trim()) return ElMessage.warning('请填写用印文件名称')
  if (!form.person.trim()) return ElMessage.warning('请填写经办人')
  if (!attachments.value.length) return ElMessage.warning('请上传用印文件附件')
  if (!photos.value.length) return ElMessage.warning('请至少上传 1 张用印文件照片（钉钉模板要求必填）')
  submitting.value = true
  try {
    const r = await api.sealSubmit({
      person: form.person.trim(),
      biz_date: form.biz_date,
      doc_name: form.doc_name.trim(),
      copies: form.copies,
      doc_category: form.doc_category,
      seal_type: form.seal_type,
      note: form.note.trim(),
      photo_ids: photos.value,
      attachment_ids: attachments.value.map(a => a.media_id)
    })
    ElMessage.success('已提交，请到钉钉里审批')
    dlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.thumbs { margin-top: 8px; display: flex; gap: 8px; flex-wrap: wrap; }
.thumb {
  font-size: 12px; padding: 4px 8px; border: 1px solid #E0E5EC;
  border-radius: 6px; background: #FAFBFC; color: #4B5563;
}
.thumb-x { color: #C0392B; cursor: pointer; margin-left: 6px; }
</style>
