<template>
  <div>
    <div class="panel">
      <div class="panel-hd">
        <span>工具箱</span>
        <span class="sub">常用的小工具，点卡片直接打开</span>
        <el-button v-if="canEdit" type="primary" size="small" style="margin-left:auto"
                   @click="openNew">＋ 添加工具</el-button>
      </div>

      <div class="tool-wrap">
        <!-- 系统内置工具 -->
        <div v-for="b in builtins" :key="b.key" class="tool-card builtin">
          <div class="tool-top">
            <span class="tool-icon">{{ b.icon }}</span>
            <div class="tool-name">
              {{ b.name }}
              <el-tag size="small" effect="dark" type="success">内置</el-tag>
            </div>
          </div>
          <div class="tool-desc">{{ b.description }}</div>
          <div class="tool-url mono">系统自带，不用另开页面</div>
          <div class="tool-actions">
            <el-button type="primary" link size="small" @click="openBuiltin(b)">打开工具 →</el-button>
          </div>
        </div>

        <div v-for="t in tools" :key="t.id" class="tool-card" :class="{ off: !t.enabled }">
          <div class="tool-top">
            <span class="tool-icon">{{ t.icon || '🚀' }}</span>
            <div class="tool-name">
              {{ t.name }}
              <el-tag v-if="t.category" size="small" effect="plain">{{ t.category }}</el-tag>
            </div>
          </div>
          <div class="tool-desc">{{ t.description || '点一下就能打开' }}</div>
          <div class="tool-url mono">{{ t.url || '还没填地址' }}</div>
          <div class="tool-actions">
            <el-button type="primary" link size="small" :disabled="!t.url" @click="open(t)">
              打开工具 →
            </el-button>
            <template v-if="canEdit">
              <el-button link size="small" @click="openEdit(t)">编辑</el-button>
              <el-button link type="danger" size="small" @click="remove(t)">删除</el-button>
            </template>
          </div>
        </div>

        <div v-if="!tools.length" class="empty">
          还没有工具{{ canEdit ? '，点右上角「添加工具」加一个' : '' }}
        </div>
      </div>
    </div>

    <!-- 添加 / 编辑 -->
    <el-dialog v-model="dlg" :title="form.id ? '修改工具' : '添加工具'" width="480px">
      <el-form :model="form" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="比如：公文助手" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.url" placeholder="https://… 或 192.168.1.10:8080" />
        </el-form-item>
        <el-form-item label="一句话说明">
          <el-input v-model="form.description" placeholder="比如：公文智能排版" />
        </el-form-item>
        <el-form-item label="图标（一个表情就行）">
          <el-input v-model="form.icon" placeholder="📝" style="width:120px" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="公文 / 工具 / 其他" style="width:160px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useRouter } from 'vue-router'

const router = useRouter()

const builtins = [
  {
    key: 'watermark',
    name: '图片水印',
    icon: '🖼',
    description: '给图片加水印（文字、平铺、透明度），支持批量',
    route: 'watermark'
  }
]

const tools = ref([])
const canEdit = ref(false)
const dlg = ref(false)
const saving = ref(false)
const form = reactive({ id: null, name: '', url: '', description: '', icon: '', category: '' })

async function load() {
  try {
    const r = await api.tools()
    tools.value = r.tools || []
    canEdit.value = !!r.can_edit
  } catch (e) {
    ElMessage.error(e.message || '读取失败')
  }
}

function openBuiltin(b) {
  router.push({ name: b.route })
}

function openNew() {
  Object.assign(form, { id: null, name: '', url: '', description: '', icon: '', category: '' })
  dlg.value = true
}

function openEdit(t) {
  Object.assign(form, {
    id: t.id, name: t.name, url: t.url || '',
    description: t.description || '', icon: t.icon || '', category: t.category || ''
  })
  dlg.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('先起个名字')
    return
  }
  saving.value = true
  try {
    await api.toolSave({ ...form })
    ElMessage.success('已保存')
    dlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(t) {
  try {
    await ElMessageBox.confirm(`确定删掉「${t.name}」？`, '删除工具', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await api.toolDelete({ id: t.id })
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function open(t) {
  if (!t.url) {
    ElMessage.warning('这个工具还没填地址')
    return
  }
  window.open(t.url, '_blank', 'noopener')
}

onMounted(load)
</script>

<style scoped>
.tool-card.builtin {
  border-color: #C7E3C7;
  background: linear-gradient(180deg, #F7FDF7 0%, #FFFFFF 60%);
}
.tool-wrap {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 14px;
  padding: 16px;
}
.tool-card {
  border: 1px solid #E5E9F0;
  border-radius: 8px;
  padding: 14px 16px;
  background: #fff;
  display: flex; flex-direction: column; gap: 8px;
  transition: box-shadow .15s, border-color .15s;
}
.tool-card:hover { box-shadow: 0 6px 18px rgba(27, 79, 140, .10); border-color: #C9D6E8; }
.tool-card.off { opacity: .5; }
.tool-top { display: flex; align-items: center; gap: 10px; }
.tool-icon {
  width: 34px; height: 34px; flex: none;
  display: flex; align-items: center; justify-content: center;
  background: #F1F5FB; border-radius: 8px; font-size: 18px;
}
.tool-name { font-size: 14px; font-weight: 600; color: var(--brand-text); }
.tool-name .el-tag { margin-left: 6px; font-size: 11px; }
.tool-desc { font-size: 12.5px; color: #6B7280; min-height: 18px; }
.tool-url {
  font-size: 11px; color: #9AA6B6;
  background: #F7F9FC; border-radius: 4px; padding: 3px 6px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.tool-actions { display: flex; align-items: center; gap: 4px; margin-top: auto; }
.empty { grid-column: 1 / -1; text-align: center; color: #9AA6B6; padding: 40px 0; }
</style>
