<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { outputDataSourceApi } from './api'

const props = defineProps({
  visible: { type: Boolean, default: false },
  selectedId: { type: [Number, String], default: '' },
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'selected'])

const drivers = ref([])
const items = ref([])
const tables = ref([])
const columns = ref([])
const inspectedTable = ref('')
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const inspecting = ref(false)
const deletePending = ref(false)
const message = ref('')
const errorMessage = ref('')
const testResult = ref(null)

function createForm() {
  return {
    id: '',
    name: '',
    dbType: 'mysql',
    driverClass: 'com.mysql.cj.jdbc.Driver',
    url: 'jdbc:mysql://127.0.0.1:3306/database',
    username: '',
    password: '',
    dialect: '',
    schemaName: '',
    hasPassword: false,
  }
}

const form = reactive(createForm())
const selectedSource = computed(() => items.value.find((item) => String(item.id) === String(form.id)) || null)
const canSave = computed(() => form.name.trim() && form.dbType.trim() && form.url.trim() && !saving.value && !props.readonly)

function resetFeedback() {
  message.value = ''
  errorMessage.value = ''
  testResult.value = null
  deletePending.value = false
}

function resetStructure() {
  tables.value = []
  columns.value = []
  inspectedTable.value = ''
}

function selectSource(source) {
  Object.assign(form, createForm(), source, {
    password: '',
    hasPassword: Boolean(source?.hasPassword),
  })
  resetFeedback()
  resetStructure()
}

function startCreate() {
  selectSource(null)
}

async function loadAll(preferredId = props.selectedId) {
  if (!props.visible) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [driverOptions, sources] = await Promise.all([
      outputDataSourceApi.drivers(),
      outputDataSourceApi.list(),
    ])
    drivers.value = driverOptions
    items.value = sources
    const preferred = sources.find((item) => String(item.id) === String(preferredId))
    if (preferred) selectSource(preferred)
    else if (!form.id || !sources.some((item) => String(item.id) === String(form.id))) startCreate()
  } catch (exception) {
    errorMessage.value = exception.message
  } finally {
    loading.value = false
  }
}

function applyDriverTemplate() {
  const driver = drivers.value.find((item) => item.dbType === form.dbType)
  if (!driver) return
  if (!form.driverClass || drivers.value.some((item) => item.driverClass === form.driverClass)) {
    form.driverClass = driver.driverClass
  }
  if (!form.url || drivers.value.some((item) => item.url === form.url)) {
    form.url = driver.url
  }
}

function requestPayload() {
  return {
    name: form.name.trim(),
    dbType: form.dbType,
    driverClass: form.driverClass.trim(),
    url: form.url.trim(),
    username: form.username.trim(),
    password: form.password,
    dialect: form.dialect,
    schemaName: form.schemaName.trim(),
  }
}

async function saveSource() {
  if (!canSave.value) return
  const wasUpdating = Boolean(form.id)
  saving.value = true
  resetFeedback()
  try {
    const payload = requestPayload()
    const saved = form.id
      ? await outputDataSourceApi.update(form.id, payload)
      : await outputDataSourceApi.create(payload)
    await loadAll(saved.id)
    message.value = wasUpdating ? '数据源配置已更新。' : '数据源配置已创建。'
    emit('selected', saved.id)
  } catch (exception) {
    errorMessage.value = exception.message
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  if (!form.dbType || !form.url) {
    errorMessage.value = '请先填写数据库类型和 JDBC URL。'
    return
  }
  testing.value = true
  resetFeedback()
  try {
    testResult.value = await outputDataSourceApi.test({
      id: form.id || null,
      ...requestPayload(),
    })
    if (!testResult.value.success) errorMessage.value = testResult.value.message
    else message.value = testResult.value.message
  } catch (exception) {
    errorMessage.value = exception.message
  } finally {
    testing.value = false
  }
}

async function removeSource() {
  if (!form.id) return
  if (!deletePending.value) {
    deletePending.value = true
    return
  }
  saving.value = true
  try {
    await outputDataSourceApi.remove(form.id)
    await loadAll('')
    message.value = '数据源配置已删除。'
  } catch (exception) {
    errorMessage.value = exception.message
  } finally {
    saving.value = false
    deletePending.value = false
  }
}

async function loadTables() {
  if (!form.id) {
    errorMessage.value = '请先保存数据源，再读取数据表。'
    return
  }
  inspecting.value = true
  resetFeedback()
  try {
    tables.value = await outputDataSourceApi.tables(form.id)
    message.value = `已读取 ${tables.value.length} 张数据表。`
  } catch (exception) {
    errorMessage.value = exception.message
  } finally {
    inspecting.value = false
  }
}

async function loadColumns(table) {
  if (!form.id || !table) return
  inspecting.value = true
  errorMessage.value = ''
  try {
    inspectedTable.value = table
    columns.value = await outputDataSourceApi.columns(form.id, table)
  } catch (exception) {
    columns.value = []
    errorMessage.value = exception.message
  } finally {
    inspecting.value = false
  }
}

function chooseSource(source) {
  selectSource(source)
  emit('selected', source.id)
}

function statusLabel(status) {
  return { success: '连接正常', failure: '连接失败', untested: '未测试' }[status] || '未测试'
}

watch(() => props.visible, (visible) => {
  if (visible) loadAll()
})

watch(() => props.selectedId, () => {
  if (props.visible) loadAll(props.selectedId)
})
</script>

<template>
  <div v-if="visible" class="data-source-mask" @click.self="emit('close')">
    <section class="data-source-modal" role="dialog" aria-modal="true" aria-labelledby="data-source-title">
      <header class="data-source-header">
        <div>
          <small>画布数据配置</small>
          <h2 id="data-source-title">数据库数据源</h2>
          <p>连接信息仅当前用户可见，密码加密保存；保存后可在画布输入、输出节点中直接引用。</p>
        </div>
        <button type="button" class="source-close" @click="emit('close')">关闭</button>
      </header>

      <div v-if="errorMessage" class="source-alert source-alert-error">{{ errorMessage }}</div>
      <div v-else-if="message" class="source-alert source-alert-success">{{ message }}</div>

      <div class="data-source-grid">
        <aside class="source-list-panel">
          <div class="source-list-head">
            <b>已配置数据源</b>
            <button type="button" class="source-add" :disabled="loading || readonly" @click="startCreate">＋ 新建</button>
          </div>
          <div v-if="loading" class="source-loading">正在加载数据源...</div>
          <div v-else-if="!items.length" class="source-empty">
            <strong>还没有数据库数据源</strong>
            <span>从右侧填写数据库地址，测试成功后即可在画布输入或输出节点中选择。</span>
          </div>
          <div v-else>
            <button
              v-for="source in items"
              :key="source.id"
              type="button"
              class="source-list-item"
              :class="{ active: String(source.id) === String(form.id) }"
              @click="chooseSource(source)"
            >
              <span class="source-db-mark">{{ source.dbType.slice(0, 2).toUpperCase() }}</span>
              <span class="source-list-copy">
                <b>{{ source.name }}</b>
                <small>{{ source.dbType }} · {{ source.url }}</small>
                <em :class="`source-status-${source.testStatus || 'untested'}`">
                  {{ statusLabel(source.testStatus) }}
                </em>
              </span>
            </button>
          </div>
        </aside>

        <form class="source-form-panel" @submit.prevent="saveSource">
          <div class="source-form-title">
            <div>
              <small>{{ form.id ? `数据源 #${form.id}` : '新建数据源' }}</small>
              <h3>{{ form.name || '配置新的数据库连接' }}</h3>
            </div>
            <span v-if="testResult" class="source-test-result" :class="{ failed: !testResult.success }">
              {{ testResult.success ? '✓' : '×' }} {{ testResult.latencyMs }} ms
            </span>
          </div>

          <div class="source-fields">
            <label class="source-field source-field-wide">
              <span>数据源名称 *</span>
              <input v-model="form.name" maxlength="100" placeholder="例如：生产 MySQL">
            </label>
            <label class="source-field">
              <span>数据库类型 *</span>
              <select v-model="form.dbType" @change="applyDriverTemplate">
                <option v-for="driver in drivers" :key="driver.dbType" :value="driver.dbType">{{ driver.label }}</option>
              </select>
            </label>
            <label class="source-field">
              <span>方言</span>
              <select v-model="form.dialect">
                <option value="">按数据库类型自动识别</option>
                <option value="mysql">MySQL 兼容</option>
                <option value="postgresql">PostgreSQL</option>
                <option value="oracle">Oracle</option>
                <option value="sqlserver">SQL Server</option>
                <option value="generic">通用 SQL</option>
              </select>
            </label>
            <label class="source-field source-field-wide">
              <span>JDBC URL *</span>
              <input v-model="form.url" maxlength="1000" spellcheck="false" placeholder="jdbc:mysql://127.0.0.1:3306/database">
            </label>
            <label class="source-field">
              <span>用户名</span>
              <input v-model="form.username" maxlength="255" autocomplete="username" placeholder="数据库账号">
            </label>
            <label class="source-field">
              <span>密码</span>
              <input
                v-model="form.password"
                type="password"
                maxlength="1000"
                autocomplete="new-password"
                :placeholder="form.hasPassword ? '留空则保留已保存密码' : '数据库密码（可为空）'"
              >
            </label>
            <label class="source-field">
              <span>Schema</span>
              <input v-model="form.schemaName" maxlength="255" placeholder="留空使用数据库默认值">
            </label>
            <label class="source-field">
              <span>驱动类</span>
              <input v-model="form.driverClass" maxlength="255" spellcheck="false" placeholder="由数据库类型自动选择">
            </label>
          </div>

          <div class="source-form-actions">
            <button type="button" class="source-btn source-btn-ghost" :disabled="testing" @click="testConnection">
              {{ testing ? '测试中...' : '测试连接' }}
            </button>
            <span class="source-action-spacer"></span>
            <button
              v-if="form.id"
              type="button"
              class="source-btn source-btn-danger"
              :disabled="saving || readonly"
              @click="removeSource"
            >
              {{ deletePending ? '再次点击确认删除' : '删除' }}
            </button>
            <button type="submit" class="source-btn source-btn-primary" :disabled="!canSave">
              {{ saving ? '保存中...' : (form.id ? '保存修改' : '创建数据源') }}
            </button>
          </div>

          <div v-if="testResult" class="source-test-card" :class="{ failed: !testResult.success }">
            <b>{{ testResult.success ? '连接测试成功' : '连接测试失败' }}</b>
            <p>{{ testResult.message }}</p>
            <small v-if="testResult.success">
              {{ testResult.databaseProduct }} {{ testResult.databaseVersion }} · {{ testResult.driverName }}
            </small>
          </div>

          <section class="source-inspector">
            <div class="source-inspector-head">
              <div>
                <b>连接结构</b>
                <span>读取目标表与字段，便于输出节点选择</span>
              </div>
              <button type="button" class="source-btn source-btn-small" :disabled="!form.id || inspecting" @click="loadTables">
                {{ inspecting ? '读取中...' : '读取数据表' }}
              </button>
            </div>
            <div v-if="tables.length" class="source-tables">
              <button
                v-for="table in tables"
                :key="table.name"
                type="button"
                :class="{ selected: inspectedTable === table.name }"
                @click="loadColumns(table.name)"
              >{{ table.name }}</button>
            </div>
            <div v-else class="source-inspector-empty">保存数据源后可读取数据库表结构。</div>
            <div v-if="columns.length" class="source-columns">
              <span v-for="column in columns" :key="column.name" :class="{ primary: column.primaryKey }">
                {{ column.name }}
                <em>{{ column.typeName }}</em>
                <i v-if="column.primaryKey">PK</i>
              </span>
            </div>
          </section>
        </form>
      </div>
    </section>
  </div>
</template>

<style scoped>
.data-source-mask {
  position: fixed;
  inset: 0;
  z-index: 45;
  display: grid;
  place-items: center;
  padding: 22px;
  background: rgba(238, 244, 255, .76);
  backdrop-filter: blur(12px) saturate(1.08);
}

.data-source-modal {
  width: min(1120px, 100%);
  max-height: calc(100vh - 44px);
  overflow: auto;
  color: #17304f;
  background: linear-gradient(145deg, #ffffff, #f5f9ff);
  border: 1px solid rgba(37, 99, 235, .24);
  border-radius: 24px;
  box-shadow: inset 0 1px 0 #fff, 0 30px 90px rgba(15, 23, 42, .24);
}

.data-source-header {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 22px 24px 18px;
  background:
    radial-gradient(420px 160px at 90% -40%, rgba(37, 99, 235, .16), transparent 72%),
    linear-gradient(135deg, #eef5ff, #ffffff);
  border-bottom: 1px solid #dbe7f5;
}

.data-source-header::after {
  content: "";
  position: absolute;
  right: -30px;
  bottom: -58px;
  width: 170px;
  height: 170px;
  border: 22px solid rgba(37, 99, 235, .06);
  border-radius: 50%;
  pointer-events: none;
}

.data-source-header > div,
.source-close { position: relative; z-index: 1; }

.data-source-header small,
.source-form-title small {
  color: #2563eb;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .8px;
}

.data-source-header h2,
.source-form-title h3 {
  margin: 3px 0;
  color: #0f2745;
}

.data-source-header p {
  margin: 0;
  color: #526983;
  font-size: 13px;
}

.source-close {
  padding: 8px 12px;
  color: #2563eb;
  background: #fff;
  border: 1px solid #bfdbfe;
  border-radius: 9px;
  cursor: pointer;
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}

.source-close:hover {
  transform: translateY(-1px);
  border-color: #93c5fd;
  box-shadow: 0 7px 18px rgba(37, 99, 235, .12);
}

.source-alert {
  margin: 14px 24px 0;
  padding: 10px 13px;
  border-radius: 10px;
  font-size: 13px;
}

.source-alert-error {
  color: #b91c1c;
  background: #fff1f2;
  border: 1px solid #fecdd3;
}

.source-alert-success {
  color: #166534;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
}

.data-source-grid {
  display: grid;
  grid-template-columns: 290px minmax(0, 1fr);
  min-height: 560px;
}

.source-list-panel {
  padding: 18px 14px;
  background: linear-gradient(180deg, #f1f7ff, #edf4ff);
  border-right: 1px solid #dbe7f5;
}

.source-list-head,
.source-form-title,
.source-form-actions,
.source-inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.source-list-head b {
  color: #183455;
  font-size: 13px;
}

.source-add {
  padding: 7px 10px;
  color: #fff;
  background: linear-gradient(135deg, #2563eb, #38bdf8);
  border: 0;
  border-radius: 8px;
  box-shadow: 0 7px 16px rgba(37, 99, 235, .18);
  cursor: pointer;
  transition: transform .18s ease, box-shadow .18s ease;
}

.source-add:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 10px 20px rgba(37, 99, 235, .24);
}

.source-add:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.source-loading,
.source-empty,
.source-inspector-empty {
  padding: 26px 14px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.65;
  text-align: center;
}

.source-empty {
  display: grid;
  gap: 7px;
}

.source-empty strong {
  color: #334155;
}

.source-list-item {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 10px;
  padding: 12px;
  color: #1e3a5f;
  text-align: left;
  background: rgba(255, 255, 255, .76);
  border: 1px solid #d5e2f2;
  border-radius: 14px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .9);
  cursor: pointer;
  transition: transform .2s cubic-bezier(.22, 1, .36, 1), background .2s ease, border-color .2s ease, box-shadow .2s ease;
}

.source-list-item:hover:not(.active) {
  transform: translateX(4px);
  background: #fff;
  border-color: #93c5fd;
  box-shadow: 0 8px 20px rgba(37, 99, 235, .10);
}

.source-list-item.active {
  background: #fff;
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, .13), 0 10px 22px rgba(37, 99, 235, .12);
}

.source-db-mark {
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #2563eb, #0891b2);
  border: 1px solid rgba(255, 255, 255, .72);
  border-radius: 11px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .28), 0 7px 16px rgba(37, 99, 235, .16);
  font-size: 11px;
  font-weight: 900;
}

.source-list-copy {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.source-list-copy b {
  color: #102a49;
  font-size: 13px;
}

.source-list-copy small {
  overflow: hidden;
  color: #64748b;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-list-copy em {
  width: fit-content;
  color: #64748b;
  font-size: 9px;
  font-style: normal;
  font-weight: 800;
}

.source-status-success { color: #15803d !important; }
.source-status-failure { color: #dc2626 !important; }

.source-form-panel {
  padding: 20px 24px 24px;
}

.source-form-title {
  margin-bottom: 16px;
}

.source-form-title h3 {
  font-size: 18px;
}

.source-test-result {
  padding: 6px 9px;
  color: #15803d;
  background: #dcfce7;
  border: 1px solid #bbf7d0;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 900;
}

.source-test-result.failed {
  color: #b91c1c;
  background: #fff1f2;
  border-color: #fecdd3;
}

.source-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 13px;
}

.source-field {
  min-width: 0;
  display: grid;
  gap: 6px;
}

.source-field-wide {
  grid-column: 1 / -1;
}

.source-field span {
  color: #334155;
  font-size: 11px;
  font-weight: 800;
}

.source-field input,
.source-field select {
  width: 100%;
  padding: 10px 11px;
  color: #0b1b33;
  background: #fff;
  border: 1px solid #cbd5e1;
  border-radius: 10px;
  outline: none;
  box-shadow: inset 0 1px 2px rgba(15, 23, 42, .04);
  transition: border-color .18s ease, box-shadow .18s ease, background .18s ease;
}

.source-field input:focus,
.source-field select:focus {
  background: #fff;
  border-color: #2563eb;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, .13);
}

.source-form-actions {
  margin-top: 17px;
}

.source-action-spacer {
  flex: 1;
}

.source-btn {
  padding: 9px 13px;
  border-radius: 9px;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  transition: transform .18s ease, border-color .18s ease, background .18s ease, box-shadow .18s ease;
}

.source-btn:hover:not(:disabled) {
  transform: translateY(-1px);
}

.source-btn:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.source-btn-ghost {
  color: #1d4ed8;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}

.source-btn-primary {
  color: #fff;
  background: linear-gradient(135deg, #2563eb, #38bdf8);
  border: 1px solid #2563eb;
  box-shadow: 0 8px 18px rgba(37, 99, 235, .18);
}

.source-btn-danger {
  color: #b91c1c;
  background: #fff1f2;
  border: 1px solid #fecdd3;
}

.source-btn-small {
  padding: 7px 10px;
  color: #1d4ed8;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}

.source-test-card {
  margin-top: 15px;
  padding: 12px 14px;
  color: #166534;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 11px;
}

.source-test-card.failed {
  color: #991b1b;
  background: #fff1f2;
  border-color: #fecdd3;
}

.source-test-card p {
  margin: 5px 0;
  color: inherit;
  font-size: 12px;
}

.source-test-card small {
  color: inherit;
  opacity: .8;
}

.source-inspector {
  margin-top: 17px;
  padding-top: 16px;
  border-top: 1px solid #dbe7f5;
}

.source-inspector-head b {
  display: block;
  color: #183455;
  font-size: 13px;
}

.source-inspector-head span {
  color: #64748b;
  font-size: 10px;
}

.source-tables,
.source-columns {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 12px;
}

.source-tables button,
.source-columns span {
  padding: 6px 8px;
  color: #334155;
  background: #fff;
  border: 1px solid #d7e2ef;
  border-radius: 7px;
  font-size: 10px;
}

.source-tables button {
  cursor: pointer;
}

.source-tables button.selected {
  color: #1d4ed8;
  background: #eff6ff;
  border-color: #93c5fd;
}

.source-columns span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.source-columns span.primary {
  color: #1d4ed8;
  background: #eff6ff;
  border-color: #bfdbfe;
}

.source-columns em {
  color: #64748b;
  font-size: 9px;
  font-style: normal;
}

.source-columns i {
  padding: 2px 4px;
  color: #fff;
  background: #2563eb;
  border-radius: 4px;
  font-size: 8px;
  font-style: normal;
}

@media (max-width: 820px) {
  .data-source-mask { padding: 8px; }
  .data-source-grid { grid-template-columns: 1fr; }
  .source-list-panel { max-height: 210px; overflow: auto; border-right: 0; border-bottom: 1px solid #dbe7f5; }
  .source-fields { grid-template-columns: 1fr; }
  .source-field-wide { grid-column: auto; }
}
</style>
