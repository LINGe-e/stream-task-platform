<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { taskApi, aiApi, engineApi, outputDataSourceApi } from './api'
import { auditCanvasConfig, validateControlConfig, CONTROL_CONFIG_SPECS } from './canvasConfig.js'
import { canConnectNodeTypes, detectSinkKind, detectSourceKind, extractFlowSpec } from './aiFlowRules.js'
import OutputDataSourceManager from './OutputDataSourceManager.vue'

const props = defineProps({
  taskId: { type: Number, required: true },
  readonly: { type: Boolean, default: false },
  highlightNodeId: { type: String, default: '' },
  nodeReplay: { type: Object, default: null },
})
const emit = defineEmits(['back'])

const nodes = ref([])
const edges = ref([])
const task = ref(null)
const outputDataSources = ref([])
const dataSourceManagerVisible = ref(false)
const notice = ref('')
const error = ref('')
const saving = ref(false)
const selectedNodeId = ref(null)
const selectedEdgeId = ref(null)
const pendingSourceId = ref(null)
const editingNode = ref(null)
const editForm = ref({
  label: '',
  config: '',
  targetTable: '',
  connectionId: '',
  writeMode: 'append',
  keyFieldsText: '',
  fieldMappings: [],
  autoCreate: false,
  batchSize: 500,
})
const nodeTestInput = ref(defaultNodeTestInput())
const nodeTestResult = ref(null)
const nodeTesting = ref(false)
const nodeTestPanel = ref(null)
const nodeTestPanelPosition = ref({ x: 8, y: 52 })
const nodeTestPanelDrag = ref(null)
const dragging = ref(null)
const configReportVisible = ref(true)
const warningsAcknowledged = ref(false)
const resetCanvasConfirmVisible = ref(false)
const ONBOARDING_STORAGE_KEY = 'a22-flow-onboarding-v1'
const onboardingVisible = ref(loadOnboardingPreference())
const graphSaved = ref(false)

// Redis Source 配置
const redisSourceType = ref('list')
const redisSourceKey = ref('')
const redisSourcePattern = ref('')
const redisSourceCount = ref('1000')

// JDBC Source 配置
const jdbcSourceUrl = ref('')
const jdbcSourceUsername = ref('')
const jdbcSourcePassword = ref('')
const jdbcSourceTable = ref('')
const jdbcSourceLimit = ref('10000')
const jdbcSourceQuery = ref('')

// HDFS Source 配置
const hdfsPath = ref('')
const hdfsFormat = ref('csv')
const hdfsPattern = ref('')
const hdfsLimit = ref('100000')
const hdfsFs = ref('')

// S3 Source 配置
const s3Endpoint = ref('')
const s3AccessKey = ref('')
const s3SecretKey = ref('')
const s3Bucket = ref('')
const s3Key = ref('')
const s3Format = ref('csv')
const s3Region = ref('us-east-1')

// Redis Sink 配置
const redisSinkStream = ref('')

// JDBC Sink 配置
const jdbcSinkUrl = ref('')
const jdbcSinkUsername = ref('')
const jdbcSinkPassword = ref('')
const jdbcSinkTable = ref('')

// HDFS Sink 配置
const hdfsSinkPath = ref('')
const hdfsSinkFormat = ref('csv')
const hdfsSinkOverwrite = ref(true)
const hdfsSinkFs = ref('')

// S3 Sink 配置
const s3SinkEndpoint = ref('')
const s3SinkAccessKey = ref('')
const s3SinkSecretKey = ref('')
const s3SinkBucket = ref('')
const s3SinkKey = ref('')
const s3SinkFormat = ref('csv')
const s3SinkRegion = ref('us-east-1')

// Redis Lookup 配置
const redisLookupSourceField = ref('user_id')
const redisLookupKeyPattern = ref('user:{value}')
const redisLookupType = ref('hash')
const redisLookupDefault = ref('')
const redisLookupFields = ref([
  { redisField: 'phone', targetField: 'phone' },
  { redisField: 'email', targetField: 'email' }
])

function addRedisLookupField() {
  redisLookupFields.value.push({ redisField: '', targetField: '' })
}

function removeRedisLookupField(index) {
  redisLookupFields.value.splice(index, 1)
}

// 计算属性：JDBC URL 占位符
const jdbcUrlPlaceholder = computed(() => {
  if (editingNode.value?.data.kind === 'postgresql') return 'jdbc:postgresql://localhost:5432/mydb'
  if (editingNode.value?.data.kind === 'oracle') return 'jdbc:oracle:thin:@localhost:1521:orcl'
  return 'jdbc:mysql://127.0.0.1:3307/mydb'
})
const aiPrompt = ref('读取 CSV，过滤金额大于 1000，字段脱敏，写入 MySQL')
const aiGenerating = ref(false)
const aiPlan = ref(null)
const aiFileInput = ref(null)
const aiImageInput = ref(null)
const aiAttachments = ref([])
let nodeId = 1
let errorTimer = null
let noticeTimer = null

function defaultNodeTestInput() {
  return JSON.stringify([
    { order_id: 'T001', customer_name: '张三', first_name: '张', last_name: '三', amount: '1288.50', status: 'NORMAL', phone: '13812345678', id_card: '330102199901011234', email: 'zhangsan@example.com', created_at: '2026-09-02' },
    { order_id: 'T002', customer_name: '李四', first_name: '李', last_name: '四', amount: 'abc', status: 'ERROR', phone: '', id_card: '330102199901011235', email: 'bad-email', created_at: '2026/09/02' },
    { order_id: 'T001', customer_name: '张三重复', first_name: '张', last_name: '', amount: '1288.50', status: 'NORMAL', phone: '13812345678', id_card: '330102199901011234', email: 'zhangsan@example.com', created_at: '2026-09-02' }
  ], null, 2)
}

const palette = [
  ['source', 'hdfs', 'HDFS 输入', 'HDFS', 'Read CSV/JSON files from HDFS'],
  ['transform', 'xml-json', 'XML/JSON Transform', 'XML', 'Convert XML and JSON string fields'],
  ['sink', 'oceanbase', 'OceanBase 输出', 'OB', 'Write to an OceanBase MySQL-compatible tenant'],
  ['sink', 'hdfs', 'HDFS 输出', 'HDFS', 'Write CSV/JSON files to HDFS'],
  ['source', 'json-file', 'JSON 输入', 'JSON', '读取 JSON 文件'],
  ['source', 'csv', 'CSV 输入', 'CSV', '读取 CSV 文件'],
  ['source', 'excel', 'Excel 输入', 'XLS', '读取 Excel 文件'],
  ['source', 'mysql', 'MySQL 输入', 'SQL', '读取 MySQL 表'],
  ['source', 'jdbc', '数据库输入', 'DB', '连接用户自己的数据库并读取数据表'],
  ['source', 'redis', 'Redis 输入', 'RDS', '读取 Redis List/Set/Hash'],
  ['source', 'kafka', 'Kafka 输入', 'KFK', '消费 Kafka 消息'],
  ['transform', 'field-map', '字段映射', 'MAP', '重命名字段'],
  ['transform', 'filter', '条件过滤', 'FLT', '按条件过滤数据'],
  ['transform', 'dedup', '去重', 'UNIQ', '按字段去重'],
  ['transform', 'mask', '字段脱敏', 'MASK', '手机号、证件号等敏感字段脱敏'],
  ['transform', 'concat', '字段拼接', 'JOIN', '拼接多个字段'],
  ['transform', 'data-repair', '数据补全', 'FIX', '根据数据库补全缺失字段'],
  ['transform', 'redis-lookup', 'Redis 查询补全', 'RDS+', '按 Redis Hash 补全字段'],
  ['sink', 'mysql', 'MySQL 输出', 'SQL', '写入 MySQL 表'],
  ['transform', 'data-validate', '数据校验', 'QA', '校验字段并生成审计信息'],
  ['sink', 'kafka', 'Kafka 输出', 'KFK', '发送到 Kafka'],
  ['sink', 'jdbc', 'JDBC 输出', 'JDBC', '写入外部 JDBC 表'],
  ['sink', 'csv', 'CSV 输出', 'CSV', '写入 CSV 文件'],
  ['sink', 'excel', 'Excel 输出', 'XLS', '写入 Excel 文件'],
].map(([type, kind, label, icon, desc]) => ({ type, kind, label, icon, desc }))

const controlPalette = [
  { type: 'control', kind: 'start', label: '开始', icon: 'START', desc: '流程起点，画布默认存在' },
  { type: 'control', kind: 'end', label: '结束', icon: 'END', desc: '流程终点，画布默认存在' },
]

const paletteGroups = [
  { key: 'source', title: '输入控件', desc: '读取文件、数据库或消息', items: palette.filter((item) => item.type === 'source') },
  { key: 'transform', title: '处理控件', desc: '字段加工、过滤与转换', items: palette.filter((item) => item.type === 'transform') },
  { key: 'sink', title: '输出控件', desc: '写入数据库、文件或队列', items: palette.filter((item) => item.type === 'sink') },
  { key: 'control', title: '控制控件', desc: '流程开始和结束节点', items: controlPalette },
]

const isRunning = computed(() => task.value?.status === 'RUNNING')
const isEditable = computed(() => !props.readonly && !isRunning.value)
const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedNodeId.value))
const canTestSelectedNode = computed(() => (
  !pendingSourceId.value && selectedNode.value?.data?.nodeType === 'transform'
))
const configReport = computed(() => auditCanvasConfig(nodes.value))
const editDiagnostics = computed(() => {
  if (!editingNode.value) return { level: 'ok', issues: [], spec: null }
  const result = validateControlConfig({
    nodeType: editingNode.value.data.nodeType,
    kind: editingNode.value.data.kind,
    config: editConfigDraft.value,
    targetTable: editForm.value.targetTable,
  })
  if (editConnectionMissing.value) {
    return {
      ...result,
      level: 'error',
      issues: [...result.issues, {
        level: 'error',
        message: '当前画布引用的数据库数据源不存在或已删除。',
        hint: '请重新选择数据源，或打开“管理数据源”重新创建。',
      }],
    }
  }
  return result
})
const editHasError = computed(() => editDiagnostics.value.level === 'error' || editConnectionMissing.value)
const isManagedDbSinkEditor = computed(() => {
  const node = editingNode.value
  return node?.data?.nodeType === 'sink'
    && ['mysql', 'jdbc', 'oceanbase', 'postgresql', 'oracle'].includes(node?.data?.kind)
})
const isManagedDbSourceEditor = computed(() => {
  const node = editingNode.value
  return node?.data?.nodeType === 'source'
    && ['mysql', 'jdbc', 'oceanbase', 'postgresql', 'oracle'].includes(node?.data?.kind)
})
const isManagedDbEditor = computed(() => isManagedDbSourceEditor.value || isManagedDbSinkEditor.value)
const editConnectionMissing = computed(() => (
  isManagedDbEditor.value
  && editForm.value.connectionId
  && outputDataSources.value.length > 0
  && !outputDataSources.value.some((item) => String(item.id) === String(editForm.value.connectionId))
))
const businessNodeCount = computed(() => nodes.value.filter((node) => (
  !['start', 'end'].includes(node.data.nodeType)
)).length)
const onboardingTasks = computed(() => {
  const hasSource = nodes.value.some((node) => node.data.nodeType === 'source')
  const hasTransform = nodes.value.some((node) => node.data.nodeType === 'transform')
  const hasSink = nodes.value.some((node) => node.data.nodeType === 'sink')
  const hasConnection = edges.value.length > 0
  const configReady = businessNodeCount.value > 0
    && configReport.value.errors.length === 0
    && (configReport.value.warnings.length === 0 || warningsAcknowledged.value)
  return [
    { key: 'source', label: '添加输入', description: '从左侧拖入一个文件、数据库或消息输入', done: hasSource },
    { key: 'transform', label: '添加处理', description: '拖入过滤、映射或脱敏等处理控件', done: hasTransform },
    { key: 'sink', label: '添加输出', description: '拖入数据库、文件或队列输出控件', done: hasSink },
    { key: 'connect', label: '连接节点', description: '点击节点连接点，再点击目标节点完成连线', done: hasConnection },
    { key: 'check', label: '通过配置体检', description: '打开右侧配置体检，处理错误并确认警告', done: configReady },
    { key: 'save', label: '保存画布', description: '检查无误后保存，当前进度会提交到任务', done: graphSaved.value },
  ]
})
const onboardingDoneCount = computed(() => onboardingTasks.value.filter((task) => task.done).length)
const onboardingProgress = computed(() => Math.round(
  (onboardingDoneCount.value / onboardingTasks.value.length) * 100,
))
const onboardingNextTask = computed(() => onboardingTasks.value.find((task) => !task.done) || null)

const dagStats = computed(() => {
  const sourceCount = nodes.value.filter((node) => node.data.nodeType === 'source').length
  const transformCount = nodes.value.filter((node) => node.data.nodeType === 'transform').length
  const sinkCount = nodes.value.filter((node) => node.data.nodeType === 'sink').length
  const branchCount = nodes.value.filter((node) => edges.value.filter((edge) => edge.source === node.id).length > 1).length
  return { sourceCount, transformCount, sinkCount, branchCount, edgeCount: edges.value.length }
})

function loadOnboardingPreference() {
  try {
    return window.localStorage.getItem(ONBOARDING_STORAGE_KEY) !== 'dismissed'
  } catch {
    return true
  }
}

function storeOnboardingPreference(isVisible) {
  try {
    window.localStorage.setItem(ONBOARDING_STORAGE_KEY, isVisible ? 'visible' : 'dismissed')
  } catch {
    // 本地存储不可用时，引导仍可在当前页面正常开关。
  }
}

function openOnboarding() {
  onboardingVisible.value = true
  storeOnboardingPreference(true)
}

function dismissOnboarding() {
  onboardingVisible.value = false
  storeOnboardingPreference(false)
}


function typeLabel(type) {
  return { start: '开始', source: '输入', transform: '处理', sink: '输出', end: '结束' }[type] || '节点'
}

function typeColor(type) {
  
  return { start: '#5a67d8', source: '#2668e8', transform: '#e49325', sink: '#16a34a', end: '#7b879c' }[type] || '#2668e8'
}

function typeBg(type) {
  return { sink: 'rgba(220,252,231,0.96)' }[type] || ''
}

function shapeClass(item = {}) {
  if (['start', 'end'].includes(item.kind) || ['start', 'end'].includes(item.nodeType)) return 'shape-pill'
  if (item.kind === 'filter') return 'shape-diamond'
  if (['mysql', 'jdbc', 'postgresql', 'oracle', 'oceanbase'].includes(item.kind)) return 'shape-database'
  if (item.kind === 'redis') return 'shape-cache'
  if (item.kind === 'redis-lookup') return 'shape-cache'
  if (item.kind === 'kafka') return 'shape-queue'
  if (item.kind === 'redis') return 'shape-cache'
  if (item.kind === 'redis-lookup') return 'shape-cache'
  if (item.kind === 'postgresql') return 'shape-database'
  if (item.kind === 'oracle') return 'shape-database'
  if (item.kind === 'jdbc') return 'shape-database'
  if (item.kind === 's3') return 'shape-cloud'
  if (['json-file', 'csv', 'excel', 'xml', 'xml-json', 'hdfs'].includes(item.kind)) return 'shape-document'
  if (item.kind === 'concat') return 'shape-join'
  if (item.type === 'sink') return 'shape-output'
  if (item.type === 'source') return 'shape-input'
  return 'shape-process'
}

function nodeShapeClass(node) {
  return `node-${shapeClass({ ...node.data, type: node.data.nodeType })}`
}

function defaultNode(type, position) {
  const start = type === 'start'
  return {
    id: type,
    type: 'native',
    position,
    data: {
      nodeType: type,
      kind: type,
      label: start ? '开始' : '结束',
      icon: start ? 'START' : 'END',
      desc: start ? '流程起点' : '流程终点',
      config: '',
      targetTable: '',
    },
  }
}

function resetGraph() {
  nodes.value = [defaultNode('start', { x: 80, y: 250 }), defaultNode('end', { x: 760, y: 250 })]
  edges.value = []
  nodeId = 1
  graphSaved.value = false
}

function cancelCanvasReset() {
  resetCanvasConfirmVisible.value = false
}

function confirmCanvasReset() {
  resetGraph()
  selectedNodeId.value = null
  selectedEdgeId.value = null
  pendingSourceId.value = null
  nodeTestResult.value = null
  dragging.value = null
  warningsAcknowledged.value = false
  stopNodeTestPanelDrag()
  resetCanvasConfirmVisible.value = false
  error.value = ''
  notice.value = '画布已重置为初始状态，仅保留“开始”和“结束”节点。'
}

function validText(value) {
  return typeof value === 'string'
    && value.trim()
    && !value.includes('?')
    && !value.includes('锟')
    && !/[浠诲鏁濮杩]/.test(value)
}

function cleanNodeData(data = {}) {
  const nodeType = data.nodeType || data.type || 'transform'
  const match = palette.find((item) => item.type === nodeType && item.kind === data.kind)
  if (nodeType === 'start' || nodeType === 'end') return defaultNode(nodeType, { x: 0, y: 0 }).data
  return {
    ...data,
    nodeType,
    kind: data.kind || match?.kind || nodeType,
    label: validText(data.label) ? data.label : (match?.label || typeLabel(nodeType)),
    icon: match?.icon || data.icon || 'NODE',
    desc: validText(data.desc) ? data.desc : (match?.desc || ''),
    config: typeof data.config === 'string' ? data.config : '',
    targetTable: typeof data.targetTable === 'string' ? data.targetTable : '',
  }
}

function nextId() {
  while (nodes.value.some((node) => node.id === `node_${nodeId}`)) nodeId += 1
  return `node_${nodeId++}`
}

function addNode(item, position) {
  if (item.type === 'control') return setError('开始和结束节点已固定在画布中，可直接拖动调整位置。')
  if (!isEditable.value) return setError('运行中任务不能修改画布，请先停止任务。')
  const count = nodes.value.filter((node) => !['start', 'end'].includes(node.data.nodeType)).length
  const id = nextId()
  nodes.value.push({
    id,
    type: 'native',
    position: position || nextHorizontalPosition(count),
    data: cleanNodeData({
      nodeType: item.type,
      kind: item.kind,
      label: item.label,
      icon: item.icon,
      desc: item.desc,
      config: defaultConfig(item),
      targetTable: item.type === 'sink' && item.kind === 'mysql' ? 'a22_demo_output' : '',
    }),
  })
  selectedNodeId.value = id
  if (item.type === 'sink') {
    autoConnect()
  } else {
    notice.value = `已添加“${item.label}”，请手动连接上游和下游。`
    error.value = ''
  }
}

function defaultConfig(item) {
  if (item.kind === 'field-map') return 'customer_name:customer,amount:amount'
  if (item.kind === 'filter') return '{"field":"remark","operator":"contains_any","keywords":["退款","异常"],"ignoreCase":true}'
  if (item.kind === 'dedup') return '{"fields":["transaction_id"],"keep":"last","orderFields":["created_at"]}'
  if (item.kind === 'mask') return '{"fields":["phone","id_card"],"rules":[{"field":"phone","type":"phone","showLast":4},{"field":"id_card","type":"idcard","showLast":4}]}'
  if (item.kind === 'concat') return '{"fields":["first_name","last_name"],"target":"full_name","separator":" ","skipBlank":true}'
  if (item.kind === 'type-convert') return '{"rules":[{"field":"amount","targetType":"DECIMAL"},{"field":"event_time","targetType":"DATETIME","outputFormat":"yyyy-MM-dd HH:mm:ss"}]}'
  if (item.kind === 'data-repair') return '{"lookupTable":"dim_users","keys":[{"recordField":"user_id","lookupField":"user_id"},{"recordField":"org_id","lookupField":"org_id"}],"fields":[{"sourceField":"phone","lookupField":"phone","targetField":"phone"},{"sourceField":"id_card","lookupField":"id_card","targetField":"id_card"}],"preMapping":{"uid":"user_id","org":"org_id"},"postMapping":{},"limit":"50000"}'
  if (item.kind === 'redis-lookup') return '{"sourceField":"user_id","redisKeyPattern":"user:{value}","redisType":"hash","targetFields":[{"redisField":"phone","targetField":"phone"},{"redisField":"email","targetField":"email"}],"defaultValue":""}'
  if (item.kind === 'data-validate') return '{"tagRows":true,"issueField":"_issues","validField":"_audit_valid","rules":[{"field":"phone","required":true,"regex":"^\\\\d{11}$"},{"field":"amount","min":0,"max":1000000},{"field":"status","allowed":["ACTIVE","INACTIVE"]},{"field":"created_at","required":true,"dateFormat":"yyyy-MM-dd"}]}'
  if (item.type === 'source' && item.kind === 'jdbc') return '{"url":"jdbc:mysql://127.0.0.1:3307/a22","username":"dev_ymq","password":"","table":"source_table","limit":"1000"}'
  if (item.type === 'source' && item.kind === 'mysql') return '{"table":"orders","limit":"1000"}'
  if (item.type === 'source' && item.kind === 'hdfs') return '{"fs":"file:///","path":"./hdfs-data/input/data.csv","format":"csv","limit":"100000"}'
  if (item.type === 'source' && item.kind === 'redis') return '{"type":"list","key":"a22:input","count":"1000"}'
  if (item.type === 'source' && item.kind === 'kafka') return '{"topic":"orders","limit":"1000","timeoutMs":"5000"}'
  if (item.type === 'sink' && item.kind === 'mysql') return '{"targetTable":"a22_demo_output"}'
  if (item.type === 'sink' && item.kind === 'oceanbase') return '{"url":"jdbc:mysql://127.0.0.1:2881/a22?useUnicode=true&characterEncoding=utf8&useSSL=false","username":"root@test","password":"root","table":"a22_demo_output"}'
  if (item.type === 'sink' && item.kind === 'jdbc') return '{"url":"jdbc:mysql://127.0.0.1:3307/a22","username":"dev_ymq","password":"","table":"target_table"}'
  if (item.type === 'sink' && item.kind === 'hdfs') return '{"fs":"file:///","path":"./hdfs-data/output/result.csv","format":"csv","overwrite":"true"}'
  if (item.type === 'sink' && item.kind === 'kafka') return '{"topic":"orders_result","keyField":"order_id"}'
  if (item.type === 'sink' && item.kind === 'redis') return '{"stream":"a22:task-output"}'
  if (item.type === 'sink' && item.kind === 'csv') return '{"fileName":"orders_result.csv"}'
  if (item.type === 'sink' && item.kind === 'excel') return '{"fileName":"orders_result.xlsx"}'
  if (item.kind === 'xml-json') return '{"field":"payload","target":"payload_json","direction":"xml-to-json"}'
  return ''
}

function onPaletteDragStart(event, item) {
  if (item.type === 'control' || !isEditable.value) return event.preventDefault()
  event.dataTransfer.setData('application/a22-node', JSON.stringify(item))
}

function onDrop(event) {
  if (!isEditable.value) return
  const raw = event.dataTransfer.getData('application/a22-node')
  if (!raw) return
  const box = event.currentTarget.getBoundingClientRect()
  addNode(JSON.parse(raw), { x: event.clientX - box.left - 75, y: event.clientY - box.top - 40 })
}

function startDrag(event, node) {
  if (!isEditable.value || event.target.closest('button')) return
  dragging.value = { id: node.id, x: node.position.x, y: node.position.y, sx: event.clientX, sy: event.clientY }
  window.addEventListener('pointermove', moveNode)
  window.addEventListener('pointerup', stopDrag, { once: true })
}

function moveNode(event) {
  const drag = dragging.value
  if (!drag) return
  const node = nodes.value.find((item) => item.id === drag.id)
  if (!node) return
  node.position = {
    x: Math.max(20, drag.x + event.clientX - drag.sx),
    y: Math.max(60, drag.y + event.clientY - drag.sy),
  }
}

function stopDrag() {
  dragging.value = null
  window.removeEventListener('pointermove', moveNode)
}

function startNodeTestPanelDrag(event) {
  if (event.button !== 0 || event.target.closest('button')) return
  const panel = nodeTestPanel.value
  const board = panel?.parentElement
  if (!panel || !board) return
  event.preventDefault()
  nodeTestPanelDrag.value = {
    x: nodeTestPanelPosition.value.x,
    y: nodeTestPanelPosition.value.y,
    startX: event.clientX,
    startY: event.clientY,
    boardWidth: board.clientWidth,
    boardHeight: board.clientHeight,
    panelWidth: panel.offsetWidth,
    panelHeight: panel.offsetHeight,
  }
  window.addEventListener('pointermove', moveNodeTestPanel)
  window.addEventListener('pointerup', stopNodeTestPanelDrag, { once: true })
  window.addEventListener('pointercancel', stopNodeTestPanelDrag, { once: true })
}

function moveNodeTestPanel(event) {
  const drag = nodeTestPanelDrag.value
  if (!drag) return
  const gap = 8
  const maxX = Math.max(gap, drag.boardWidth - drag.panelWidth - gap)
  const maxY = Math.max(gap, drag.boardHeight - drag.panelHeight - gap)
  nodeTestPanelPosition.value = {
    x: Math.min(maxX, Math.max(gap, drag.x + event.clientX - drag.startX)),
    y: Math.min(maxY, Math.max(gap, drag.y + event.clientY - drag.startY)),
  }
}

function stopNodeTestPanelDrag() {
  nodeTestPanelDrag.value = null
  window.removeEventListener('pointermove', moveNodeTestPanel)
  window.removeEventListener('pointerup', stopNodeTestPanelDrag)
  window.removeEventListener('pointercancel', stopNodeTestPanelDrag)
}

function startConnect(event, node) {
  event.stopPropagation()
  if (!isEditable.value) return
  if (node.data.nodeType === 'end') return setError('结束节点不能有下游连线。')
  selectedEdgeId.value = null
  pendingSourceId.value = node.id
  selectedNodeId.value = node.id
  notice.value = `已选择上游节点“${node.data.label}”，请点击下游节点。`
  error.value = ''
}

function clickNode(node) {
  selectedEdgeId.value = null
  if (pendingSourceId.value && pendingSourceId.value !== node.id) {
    addEdge(pendingSourceId.value, node.id)
    pendingSourceId.value = null
    selectedNodeId.value = null
    return
  }
  selectedNodeId.value = node.id
  nodeTestResult.value = null
}

async function testSelectedNode() {
  if (!canTestSelectedNode.value) return setError('请选择一个处理控件进行节点测试。')
  let records
  try {
    records = JSON.parse(nodeTestInput.value || '[]')
  } catch (exception) {
    return setError(`测试数据不是有效 JSON：${exception.message}`)
  }
  if (!Array.isArray(records)) return setError('测试数据必须是 JSON 数组。')
  nodeTesting.value = true
  nodeTestResult.value = null
  try {
    const node = selectedNode.value
    nodeTestResult.value = await engineApi.testNode({
      kind: node.data.kind,
      label: node.data.label,
      config: node.data.config || '',
      records,
    })
    notice.value = `节点测试完成：输入 ${nodeTestResult.value.inputCount} 条，输出 ${nodeTestResult.value.outputCount} 条。`
  } catch (exception) {
    setError(`节点测试失败：${exception.message}`)
  } finally {
    nodeTesting.value = false
  }
}

function resetNodeTestInput() {
  nodeTestInput.value = defaultNodeTestInput()
  nodeTestResult.value = null
}

function closeNodeTest() {
  selectedNodeId.value = null
  nodeTestResult.value = null
}

function applyNodeReplay(replay) {
  if (!replay?.nodeId) return
  const node = nodes.value.find((item) => item.id === replay.nodeId)
  if (!node) return
  selectedNodeId.value = replay.nodeId
  nodeTestResult.value = null
  const records = Array.isArray(replay.records) ? replay.records : []
  if (records.length) {
    nodeTestInput.value = JSON.stringify(records, null, 2)
  } else {
    nodeTestInput.value = defaultNodeTestInput()
  }
  const sampleText = records.length ? `已带入 ${records.length} 条样例` : '未找到输入文件样例，已使用内置复杂样例'
  notice.value = `运行记录 #${replay.runId || '-'} 回放到“${node.data.label || node.id}”：${sampleText}，可点击“测试当前节点”。`
  setTimeout(() => {
    document.querySelector(`[data-node-id="${replay.nodeId}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' })
  }, 100)
}

function highlightNodeFromRun(nodeId) {
  if (!nodeId) return
  const node = nodes.value.find((item) => item.id === nodeId)
  if (!node) return
  selectedNodeId.value = nodeId
  notice.value = `已定位到运行失败节点“${node.data.label || node.id}”。`
  setTimeout(() => {
    document.querySelector(`[data-node-id="${nodeId}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' })
  }, 100)
}

function allowed(from, to) {
  return canConnectNodeTypes(from, to)
}

function selectEdge(edge) {
  if (!isEditable.value) return
  selectedEdgeId.value = edge.id
  selectedNodeId.value = null
  pendingSourceId.value = null
  notice.value = `已选中连线“${edgeLabel(edge) || '未命名'}”；可按 Delete 删除，或双击连线删除。`
  error.value = ''
}

function clearEdgeSelection() {
  selectedEdgeId.value = null
}

function removeEdge(id) {
  if (!isEditable.value) return setError('运行中任务不能修改画布，请先停止任务。')
  const edge = edges.value.find((item) => item.id === id)
  if (!edge) return
  edges.value = edges.value.filter((item) => item.id !== id)
  if (selectedEdgeId.value === id) selectedEdgeId.value = null
  notice.value = `已删除连线“${edgeLabel(edge) || '未命名'}”。`
  error.value = ''
}

function handleEdgeKeydown(event, edge) {
  if (!['Enter', ' ', 'Delete', 'Backspace'].includes(event.key)) return
  event.preventDefault()
  if (event.key === 'Enter' || event.key === ' ') selectEdge(edge)
  else removeEdge(edge.id)
}

function handleCanvasKeydown(event) {
  const target = event.target
  if (target?.closest?.('input, textarea, select, [contenteditable="true"]')) return
  if (event.key === 'Escape') {
    pendingSourceId.value = null
    selectedEdgeId.value = null
    notice.value = '已取消当前连线操作。'
    error.value = ''
    return
  }
  if ((event.key === 'Delete' || event.key === 'Backspace') && selectedEdgeId.value) {
    event.preventDefault()
    removeEdge(selectedEdgeId.value)
  }
}

function defaultEdgeLabel(from, target) {
  const outgoingCount = edges.value.filter((edge) => edge.source === from.id).length
  if (from.data.kind === 'filter') {
    return outgoingCount === 0 ? '符合条件' : outgoingCount === 1 ? '不符合条件' : `分支 ${outgoingCount + 1}`
  }
  if (from.data.nodeType === 'source' && target.data.nodeType === 'transform') return '输入数据'
  if (target.data.nodeType === 'sink') return target.data.kind === 'csv' ? '异常/留审输出' : '结果输出'
  if (target.data.nodeType === 'end') return '流程结束'
  return ''
}

function addEdge(source, target, meta = {}) {
  const from = nodes.value.find((node) => node.id === source)
  const to = nodes.value.find((node) => node.id === target)
  if (!from || !to) return
  if (!allowed(from.data.nodeType, to.data.nodeType)) {
    return setError(`不允许 ${typeLabel(from.data.nodeType)} 连接到 ${typeLabel(to.data.nodeType)}，请按“开始 → 输入 → 处理/输出 → 结束”编排。`)
  }
  if (edges.value.some((edge) => edge.source === source && edge.target === target)) return
  const label = meta.label ?? defaultEdgeLabel(from, to)
  const condition = meta.condition ?? label
  edges.value.push({ id: meta.id || `edge_${source}_${target}_${Date.now()}`, source, target, label, condition })
  notice.value = label ? `连线已创建：${label}。` : '连线已创建。'
  error.value = ''
}

function autoConnect() {
  const result = repairDagEdges(edges.value)
  edges.value = result.edges
  notice.value = result.added > 0
    ? `已自动补全 ${result.added} 条断开连线，当前共 ${edges.value.length} 条连线。`
    : edges.value.length ? `连线检查完成，当前共 ${edges.value.length} 条连线。` : '请继续拖入输入、处理、输出节点。'
}

function repairDagEdges(sourceEdges = []) {
  const nodeMap = new Map(nodes.value.map((node) => [node.id, node]))
  const ordered = [...nodes.value].sort((a, b) => a.position.x - b.position.x || a.position.y - b.position.y)
  const repaired = []
  const seen = new Set()
  let added = 0

  function pushEdge(source, target, meta = {}, auto = false) {
    const from = nodeMap.get(source)
    const to = nodeMap.get(target)
    if (!from || !to || source === target || !allowed(from.data.nodeType, to.data.nodeType)) return false
    const key = `${source}->${target}`
    if (seen.has(key)) return false
    const label = meta.label || meta.condition || defaultEdgeLabel(from, to)
    repaired.push({
      id: meta.id || `edge_${source}_${target}_${auto ? 'auto' : 'kept'}_${Date.now()}_${repaired.length}`,
      source,
      target,
      label,
      condition: meta.condition ?? label,
    })
    seen.add(key)
    if (auto) added += 1
    return true
  }

  for (const edge of sourceEdges || []) {
    pushEdge(edge.source, edge.target, edge, false)
  }

  function outgoing(nodeId) {
    return repaired.filter((edge) => edge.source === nodeId)
  }

  function incoming(nodeId) {
    return repaired.filter((edge) => edge.target === nodeId)
  }

  function nextAllowed(node) {
    return ordered.find((candidate) =>
      candidate.id !== node.id
      && candidate.position.x >= node.position.x
      && allowed(node.data.nodeType, candidate.data.nodeType)
      && !seen.has(`${node.id}->${candidate.id}`)
    )
  }

  function previousAllowed(node) {
    return [...ordered].reverse().find((candidate) =>
      candidate.id !== node.id
      && candidate.position.x <= node.position.x
      && allowed(candidate.data.nodeType, node.data.nodeType)
      && !seen.has(`${candidate.id}->${node.id}`)
    )
  }

  const start = ordered.find((node) => node.data.nodeType === 'start')
  const end = ordered.find((node) => node.data.nodeType === 'end')
  const firstSource = ordered.find((node) => node.data.nodeType === 'source')

  if (start && firstSource && outgoing(start.id).length === 0) {
    pushEdge(start.id, firstSource.id, { label: '启动读取', condition: '启动读取' }, true)
  }

  for (const node of ordered) {
    if (node.data.nodeType === 'start') continue
    if (incoming(node.id).length === 0) {
      const previous = previousAllowed(node)
      if (previous) pushEdge(previous.id, node.id, { label: defaultEdgeLabel(previous, node) }, true)
    }
  }

  for (const node of ordered) {
    if (node.data.nodeType === 'end') continue
    if (outgoing(node.id).length === 0) {
      if (node.data.nodeType === 'sink' && end) {
        pushEdge(node.id, end.id, { label: '输出完成', condition: '输出完成' }, true)
      } else {
        const next = nextAllowed(node)
        if (next) pushEdge(node.id, next.id, { label: defaultEdgeLabel(node, next) }, true)
      }
    }
  }

  if (end && incoming(end.id).length === 0) {
    const last = [...ordered].reverse().find((node) => node.id !== end.id && allowed(node.data.nodeType, end.data.nodeType))
    if (last) pushEdge(last.id, end.id, { label: '流程结束', condition: '流程结束' }, true)
  }

  return { edges: repaired, added }
}

function buildDagBranchFlow() {
  if (!isEditable.value) return setError('运行中任务不能修改画布，请先停止任务。')
  nodeId = 1
  const start = defaultNode('start', { x: 70, y: 280 })
  const source = nodeFromPalette('csv', { x: 250, y: 270 }, 'source')
  source.data.label = 'CSV 订单输入'
  source.data.desc = '读取订单、客户或交易明细'
  const filter = nodeFromPalette('filter', { x: 455, y: 245 }, 'transform')
  filter.data.label = '条件过滤分流'
  filter.data.config = '{"field":"status","operator":"=","value":"NORMAL"}'
  const map = nodeFromPalette('field-map', { x: 690, y: 155 }, 'transform')
  map.data.label = '正常数据字段映射'
  map.data.config = 'order_id:order_id,customer_name:customer,amount:amount,status:status'
  const mysql = nodeFromPalette('mysql', { x: 930, y: 155 }, 'sink')
  mysql.data.label = '正常数据入库'
  mysql.data.targetTable = 'a22_dag_normal_output'
  mysql.data.config = '{"targetTable":"a22_dag_normal_output"}'
  const csv = nodeFromPalette('csv', { x: 690, y: 380 }, 'sink')
  csv.data.label = '异常数据导出留审'
  csv.data.config = '{"targetTable":"a22_dag_reject_output","fileName":"异常数据留审.csv"}'
  const end = defaultNode('end', { x: 1170, y: 280 })
  nodes.value = [start, source, filter, map, mysql, csv, end]
  edges.value = [
    { id: 'dag_start_source', source: start.id, target: source.id, label: '启动读取', condition: '启动读取' },
    { id: 'dag_source_filter', source: source.id, target: filter.id, label: '输入数据', condition: '输入数据' },
    { id: 'dag_filter_map', source: filter.id, target: map.id, label: '符合条件', condition: 'true' },
    { id: 'dag_map_mysql', source: map.id, target: mysql.id, label: '正常分支', condition: '正常分支' },
    { id: 'dag_filter_csv', source: filter.id, target: csv.id, label: '不符合条件', condition: 'false' },
    { id: 'dag_mysql_end', source: mysql.id, target: end.id, label: '入库完成', condition: '入库完成' },
    { id: 'dag_csv_end', source: csv.id, target: end.id, label: '留审完成', condition: '留审完成' },
  ]
  selectedNodeId.value = filter.id
  aiPlan.value = ['CSV 订单输入', '条件过滤分流', '正常数据字段映射', '正常数据入库', '异常数据导出留审']
  notice.value = '已生成 DAG 并行分支模板：正常数据入库，异常数据导出留审。请按实际字段调整过滤条件后保存。'
  error.value = ''
}

function buildDagTwoStageBranchFlow() {
  if (!isEditable.value) return setError('运行中任务不能修改画布，请先停止任务。')
  nodeId = 1
  const start = defaultNode('start', { x: 60, y: 300 })
  const source = nodeFromPalette('csv', { x: 230, y: 290 }, 'source')
  source.data.label = 'CSV 订单输入'
  source.data.desc = '读取固定订单测试数据'
  const map = nodeFromPalette('field-map', { x: 430, y: 290 }, 'transform')
  map.data.label = '字段标准化'
  map.data.config = '{"mapping":{"order_id":"order_id","customer_name":"customer_name","amount":"amount","status":"status","phone":"phone","id_card":"id_card","created_at":"created_at","remark":"remark"}}'
  const statusFilter = nodeFromPalette('filter', { x: 650, y: 285 }, 'transform')
  statusFilter.data.label = '状态分支过滤'
  statusFilter.data.config = '{"field":"status","operator":"=","value":"NORMAL"}'
  const amountFilter = nodeFromPalette('filter', { x: 890, y: 150 }, 'transform')
  amountFilter.data.label = '正常订单金额二次分支'
  amountFilter.data.config = '{"field":"amount","operator":">=","value":"1000"}'
  const highSink = nodeFromPalette('mysql', { x: 1140, y: 80 }, 'sink')
  highSink.data.label = '高金额正常订单入库'
  highSink.data.targetTable = 'a22_dag_high_value_output'
  highSink.data.config = '{"targetTable":"a22_dag_high_value_output"}'
  const normalSink = nodeFromPalette('mysql', { x: 1140, y: 250 }, 'sink')
  normalSink.data.label = '普通正常订单入库'
  normalSink.data.targetTable = 'a22_dag_regular_output'
  normalSink.data.config = '{"targetTable":"a22_dag_regular_output"}'
  const rejectSink = nodeFromPalette('csv', { x: 890, y: 440 }, 'sink')
  rejectSink.data.label = '异常订单留审导出'
  rejectSink.data.config = '{"targetTable":"a22_dag_reject_output_stage2","fileName":"stage2_reject_orders"}'
  const end = defaultNode('end', { x: 1390, y: 300 })
  nodes.value = [start, source, map, statusFilter, amountFilter, highSink, normalSink, rejectSink, end]
  edges.value = [
    { id: 'stage2_start_source', source: start.id, target: source.id, label: '启动读取', condition: '启动读取' },
    { id: 'stage2_source_map', source: source.id, target: map.id, label: '原始订单', condition: '原始订单' },
    { id: 'stage2_map_status', source: map.id, target: statusFilter.id, label: '标准字段', condition: '标准字段' },
    { id: 'stage2_status_amount', source: statusFilter.id, target: amountFilter.id, label: '正常分支：status=NORMAL', condition: 'true' },
    { id: 'stage2_status_reject', source: statusFilter.id, target: rejectSink.id, label: '异常分支：status!=NORMAL', condition: 'false' },
    { id: 'stage2_amount_high', source: amountFilter.id, target: highSink.id, label: '高金额：amount>=1000', condition: 'true' },
    { id: 'stage2_amount_regular', source: amountFilter.id, target: normalSink.id, label: '普通金额：amount<1000', condition: 'false' },
    { id: 'stage2_high_end', source: highSink.id, target: end.id, label: '高金额输出完成', condition: '高金额输出完成' },
    { id: 'stage2_regular_end', source: normalSink.id, target: end.id, label: '普通订单输出完成', condition: '普通订单输出完成' },
    { id: 'stage2_reject_end', source: rejectSink.id, target: end.id, label: '异常留审完成', condition: '异常留审完成' },
  ]
  selectedNodeId.value = statusFilter.id
  aiPlan.value = ['CSV 订单输入', '字段标准化', '状态分支过滤', '正常订单金额二次分支', '高金额正常订单入库', '普通正常订单入库', '异常订单留审导出']
  notice.value = '已生成 DAG 二级分支模板：先状态分支，再对正常订单按金额二次分支。请保存画布后执行。'
  error.value = ''
}

function buildOfficeDataFlow() {
  if (!isEditable.value) return setError('运行中任务不能修改画布，请先停止任务。')
  nodeId = 1
  const start = defaultNode('start', { x: 50, y: 320 })
  const source = nodeFromPalette('csv', { x: 220, y: 310 }, 'source')
  source.data.label = '办公订单CSV输入'
  source.data.desc = '读取客户、订单、部门混合数据'
  const map = nodeFromPalette('field-map', { x: 420, y: 310 }, 'transform')
  map.data.label = '字段标准化'
  map.data.config = '{"mapping":{"订单号":"order_id","客户名称":"customer_name","部门":"department","金额":"amount","状态":"status","手机号":"phone","邮箱":"email","日期":"created_at","备注":"remark"}}'
  const validate = nodeFromPalette('data-validate', { x: 640, y: 310 }, 'transform')
  validate.data.label = '数据校验'
  validate.data.config = '{"tagRows":true,"issueField":"_issues","validField":"_audit_valid","rules":[{"field":"order_id","required":true},{"field":"phone","required":true,"regex":"^\\\\d{11}$"},{"field":"email","regex":"^[^@\\\\s]+@[^@\\\\s]+\\\\.[^@\\\\s]+$"},{"field":"amount","required":true,"min":0,"max":1000000},{"field":"status","allowed":["NORMAL","VIP","PENDING","ERROR","CANCELLED"]},{"field":"created_at","required":true,"dateFormat":"yyyy-MM-dd"}]}'
  const validFilter = nodeFromPalette('filter', { x: 860, y: 305 }, 'transform')
  validFilter.data.label = '有效数据分支'
  validFilter.data.config = '{"field":"_audit_valid","operator":"=","value":"true"}'
  const highFilter = nodeFromPalette('filter', { x: 1110, y: 160 }, 'transform')
  highFilter.data.label = '高价值客户条件'
  highFilter.data.config = '{"logic":"AND","conditions":[{"field":"status","operator":"in","values":["NORMAL","VIP"]},{"field":"amount","operator":"between","min":1000,"max":1000000},{"field":"created_at","operator":"date_between","start":"2026-08-01","end":"2026-08-31"},{"field":"remark","operator":"not_contains","value":"退款"}]}'
  const highSink = nodeFromPalette('mysql', { x: 1370, y: 90 }, 'sink')
  highSink.data.label = '高价值客户单独输出'
  highSink.data.targetTable = 'a22_office_high_value_output'
  highSink.data.config = '{"targetTable":"a22_office_high_value_output"}'
  const regularSink = nodeFromPalette('mysql', { x: 1370, y: 260 }, 'sink')
  regularSink.data.label = '普通有效数据入库'
  regularSink.data.targetTable = 'a22_office_regular_output'
  regularSink.data.config = '{"targetTable":"a22_office_regular_output"}'
  const rejectSink = nodeFromPalette('csv', { x: 1110, y: 470 }, 'sink')
  rejectSink.data.label = '异常数据留审'
  rejectSink.data.config = '{"targetTable":"a22_office_reject_output","fileName":"office_reject_review"}'
  const end = defaultNode('end', { x: 1600, y: 320 })
  nodes.value = [start, source, map, validate, validFilter, highFilter, highSink, regularSink, rejectSink, end]
  edges.value = [
    { id: 'office_start_source', source: start.id, target: source.id, label: '启动读取', condition: '' },
    { id: 'office_source_map', source: source.id, target: map.id, label: '原始办公数据', condition: '' },
    { id: 'office_map_validate', source: map.id, target: validate.id, label: '字段标准化完成', condition: '' },
    { id: 'office_validate_valid', source: validate.id, target: validFilter.id, label: '带校验标记', condition: '' },
    { id: 'office_valid_high', source: validFilter.id, target: highFilter.id, label: '有效数据', condition: 'true' },
    { id: 'office_valid_reject', source: validFilter.id, target: rejectSink.id, label: '异常/无效数据', condition: 'false' },
    { id: 'office_high_sink', source: highFilter.id, target: highSink.id, label: '高价值：多条件AND', condition: 'true' },
    { id: 'office_regular_sink', source: highFilter.id, target: regularSink.id, label: '普通有效数据', condition: 'false' },
    { id: 'office_high_end', source: highSink.id, target: end.id, label: '高价值输出完成', condition: '' },
    { id: 'office_regular_end', source: regularSink.id, target: end.id, label: '普通入库完成', condition: '' },
    { id: 'office_reject_end', source: rejectSink.id, target: end.id, label: '留审完成', condition: '' },
  ]
  selectedNodeId.value = highFilter.id
  aiPlan.value = ['办公订单CSV输入', '字段标准化', '数据校验', '有效数据分支', '高价值客户条件', '高价值客户单独输出', '普通有效数据入库', '异常数据留审']
  notice.value = '已生成复杂办公数据处理模板：字段标准化、数据校验、多条件分支和多输出。'
  error.value = ''
}

function buildDemoFlow() {
  if (!isEditable.value) return setError('运行中任务不能修改画布，请先停止任务。')
  nodes.value = [
    defaultNode('start', { x: 80, y: 260 }),
    nodeFromPalette('json-file', { x: 280, y: 250 }),
    nodeFromPalette('field-map', { x: 500, y: 250 }),
    nodeFromPalette('mysql', { x: 720, y: 250 }, 'sink'),
    defaultNode('end', { x: 940, y: 260 }),
  ]
  edges.value = []
  autoConnect()
}
const aiExamples = [
  '读取 CSV，过滤金额大于 1000，字段脱敏，写入 MySQL',
  '消费 Kafka 交易消息，字段映射，按 transaction_id 去重，输出 Redis',
  '读取上游 CSV，按 user_id 补全缺失手机号与身份证号，写入 MySQL',
  '读取上游 CSV，先映射 uid->user_id，再按 user_id+org_id 补全缺失手机号与身份证号，写入 MySQL',
  '读取 Excel，金额转为 DECIMAL，时间转为 yyyy-MM-dd HH:mm:ss，写入 MySQL',
  '读取 CSV，拼接姓名和手机号为联系信息，按订单号保留最后一条，导出 CSV',
  '读取上游 CSV，校验手机号与金额，过滤无效数据，写入 MySQL',
]

function setAiExample(example) {
  aiPrompt.value = example
}

function aiAttachmentKind(file) {
  const name = file.name.toLowerCase()
  if (file.type.startsWith('image/')) return 'image'
  if (name.endsWith('.csv')) return 'csv'
  if (name.endsWith('.json')) return 'json-file'
  if (name.endsWith('.xlsx') || name.endsWith('.xls')) return 'excel'
  if (name.endsWith('.xml')) return 'xml'
  if (name.endsWith('.kafka')) return 'kafka'
  if (name.endsWith('.ndjson') || name.endsWith('.jsonl')) return 'json-file'
  return 'file'
}

function aiAttachmentContext() {
  return aiAttachments.value
    .map((item) => `${item.name} ${item.kind} ${item.preview || ''}`)
    .join(' ')
}

function addAiAttachment(file) {
  if (!file) return
  const kind = aiAttachmentKind(file)
  if (!['image', 'csv', 'json-file', 'excel', 'xml', 'kafka'].includes(kind)) {
    setError('AI 编排助手目前支持上传 JSON、CSV、XLSX、XLS、XML、Kafka、NDJSON、JSONL 文件和图片。')
    return
  }
  const attachment = {
    id: `${Date.now()}_${Math.random().toString(16).slice(2)}`,
    name: file.name,
    kind,
    size: file.size,
    preview: '',
    url: '',
  }
  aiAttachments.value.push(attachment)
  if (kind === 'image') {
    const reader = new FileReader()
    reader.onload = () => { attachment.url = String(reader.result || '') }
    reader.readAsDataURL(file)
  } else if (file.size <= 256 * 1024) {
    const reader = new FileReader()
    reader.onload = () => { attachment.preview = String(reader.result || '').slice(0, 400) }
    reader.readAsText(file)
  }
  notice.value = kind === 'image' ? '图片已添加到 AI 编排上下文。' : '文件已添加到 AI 编排上下文。'
  error.value = ''
}

function onAiAttachmentChange(event) {
  Array.from(event.target.files || []).forEach(addAiAttachment)
  event.target.value = ''
}

function removeAiAttachment(id) {
  aiAttachments.value = aiAttachments.value.filter((item) => item.id !== id)
}

function normalizePrompt(value) {
  return String(value || '').trim().toLowerCase()
}

function extractFilterConfig(text) {
  const amount = text.match(/(?:金额|amount|价格|price|数值|value)\s*(?:大于|超过|>|>=|不少于)\s*(\d+(?:\.\d+)?)/)
  if (amount) return { field: 'amount', operator: '>', value: Number(amount[1]) }
  const status = text.match(/(?:状态|status)\s*(?:为|=|等于)\s*([\w\u4e00-\u9fa5-]+)/)
  if (status) return { field: 'status', operator: '=', value: status[1] }

  const keywordMatch = text.match(/(?:包含|含有|关键词|关键字)\s*[:：]?\s*([\u4e00-\u9fa5\w、,，| ]+)/)
  if (keywordMatch) {
    const keywords = keywordMatch[1].split(/、|,|，|\|/).map(item => item.trim()).filter(Boolean)
    const field = text.match(/(?:字段|列|field)\s*[:：]?\s*([\w\u4e00-\u9fa5_ -]+)/)?.[1]?.trim()
    const operator = /不包含|排除/.test(text) ? 'not_contains' : (field ? 'contains_any' : 'contains_any')
    return field ? { field, operator, keywords, ignoreCase: true } : { field: '', operator: 'contains_any', keywords, ignoreCase: true }
  }

  const between = text.match(/(?:日期|时间|date|time)\s*(?:在|介于|区间)\s*([0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2})\s*(?:到|至|~|-)\s*([0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2})/)
  if (between) {
    const field = text.match(/(?:字段|列|field)\s*[:：]?\s*([\w\u4e00-\u9fa5_ -]+)/)?.[1]?.trim() || 'created_at'
    return { field, operator: 'date_between', start: between[1].replaceAll('/', '-'), end: between[2].replaceAll('/', '-'), dateFormat: 'yyyy-MM-dd' }
  }
  const beforeDate = text.match(/(?:早于|在之前|before)\s*([0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2})/)
  if (beforeDate) {
    const field = text.match(/(?:字段|列|field)\s*[:：]?\s*([\w\u4e00-\u9fa5_ -]+)/)?.[1]?.trim() || 'created_at'
    return { field, operator: 'date_before', value: beforeDate[1].replaceAll('/', '-'), dateFormat: 'yyyy-MM-dd' }
  }
  const afterDate = text.match(/(?:晚于|在之后|after)\s*([0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2})/)
  if (afterDate) {
    const field = text.match(/(?:字段|列|field)\s*[:：]?\s*([\w\u4e00-\u9fa5_ -]+)/)?.[1]?.trim() || 'created_at'
    return { field, operator: 'date_after', value: afterDate[1].replaceAll('/', '-'), dateFormat: 'yyyy-MM-dd' }
  }

  return { field: 'status', operator: '!=', value: 'CANCELLED' }
}

function inferAiSinkConfig(text, kind, item) {
  const baseConfig = item ? defaultConfig(item) : '{"targetTable":"ai_generated_output"}'
  try {
    const config = JSON.parse(baseConfig || '{}')
    if (kind === 'mysql') {
      const tableMatch = text.match(/(?:目标表|表名)(?:为|是|[:：])?\s*["']?([\w\u4e00-\u9fa5_.-]+)/)
        || text.match(/(?:写入|保存到|输出到)\s*mysql(?:\s*的)?\s*(?:表\s*)?["']?([\w\u4e00-\u9fa5_.-]+)/)
      if (tableMatch) config.targetTable = tableMatch[1].replace(/表$/, '')
    }
    if (kind === 'kafka') {
      const topicMatch = text.match(/topic(?:为|是|[:：])?\s*["']?([\w.-]+)/)
        || text.match(/kafka(?:\s*的)?\s*([\w.-]+)/)
      if (topicMatch) config.topic = topicMatch[1]
    }
    if (kind === 'csv' || kind === 'excel') {
      const fileMatch = text.match(/([\w\u4e00-\u9fa5_.-]+\.(?:csv|xlsx|xls))/)
      if (fileMatch) config.fileName = fileMatch[1]
    }
    if (kind === 'redis') {
      const streamMatch = text.match(/(?:stream|流)(?:为|是|[:：])?\s*["']?([\w:.-]+)/)
      if (streamMatch) config.stream = streamMatch[1]
    }
    return JSON.stringify(config)
  } catch {
    return baseConfig
  }
}

function buildAiSteps(prompt) {
  const text = normalizePrompt(`${prompt || ''} ${aiAttachmentContext()}`)
  if (!text) return []
  const sourceKind = detectSourceKind(prompt, aiAttachmentContext())
  const sourceItem = palette.find((item) => item.type === 'source' && item.kind === sourceKind)
  const steps = [{ kind: sourceKind, type: 'source', config: sourceItem ? defaultConfig(sourceItem) : '' }]

  if (/映射|改名|重命名|字段整理|字段选择|map/.test(text)) {
    steps.push({ kind: 'field-map', type: 'transform', config: '{"mapping":{"customer_name":"customer","amount":"amount"}}' })
  }
  if (/过滤|筛选|大于|超过|小于|等于|剔除|filter/.test(text)) {
    steps.push({ kind: 'filter', type: 'transform', config: JSON.stringify(extractFilterConfig(text)) })
  }
  if (/脱敏|掩码|mask|手机号|身份证|证件|敏感/.test(text)) {
    steps.push({ kind: 'mask', type: 'transform', config: '{"fields":["phone","id_card","customer_name"],"mask":"partial"}' })
  }
  if (/类型|转换|转成|数字|日期|type/.test(text)) {
    steps.push({ kind: 'type-convert', type: 'transform', config: '{"rules":[{"field":"amount","targetType":"DECIMAL"},{"field":"event_time","targetType":"DATETIME","outputFormat":"yyyy-MM-dd HH:mm:ss"}]}' })
  }
  if (/拼接|合并|concat|组合/.test(text)) {
    steps.push({ kind: 'concat', type: 'transform', config: '{"fields":["first_name","last_name"],"target":"full_name","separator":" ","skipBlank":true}' })
  }
  if (/去重|重复|unique|dedup/.test(text)) {
    steps.push({ kind: 'dedup', type: 'transform', config: '{"fields":["order_id"],"keep":"last","orderFields":["created_at"]}' })
  }
  if (/补全|补充|修正|缺失|补录|repair|映射后补全|多键补全/.test(text)) {
    steps.push({ kind: 'data-repair', type: 'transform', config: '{"lookupTable":"dim_users","keys":[{"recordField":"user_id","lookupField":"user_id"},{"recordField":"org_id","lookupField":"org_id"}],"fields":[{"sourceField":"phone","lookupField":"phone","targetField":"phone"},{"sourceField":"id_card","lookupField":"id_card","targetField":"id_card"}],"preMapping":{"uid":"user_id","org":"org_id"}}' })
  }
  if (/校验|审计|验证|检查|质量|validate|audit/.test(text)) {
    steps.push({ kind: 'data-validate', type: 'transform', config: '{"tagRows":true,"issueField":"_issues","validField":"_audit_valid","rules":[{"field":"phone","required":true,"regex":"^\\\\d{11}$"},{"field":"amount","min":0,"max":1000000},{"field":"status","allowed":["ACTIVE","INACTIVE"]},{"field":"created_at","required":true,"dateFormat":"yyyy-MM-dd"}]}' })
  }
  if (/xml[\s\S]*json|json[\s\S]*xml|xml-json/.test(text)) {
    steps.push({ kind: 'xml-json', type: 'transform', config: '{"field":"payload","target":"payload_json","direction":"xml-to-json"}' })
  }
  if (/redis[\s\S]*(查询|补全)|(查询|补全)[\s\S]*redis/.test(text)) {
    steps.push({ kind: 'redis-lookup', type: 'transform', config: '{"sourceField":"user_id","redisKeyPattern":"user:{value}","redisType":"hash","targetFields":[{"redisField":"phone","targetField":"phone"}]}' })
  }
  const sinkKind = detectSinkKind(text)
  const sinkItem = palette.find((item) => item.type === 'sink' && item.kind === sinkKind)
  steps.push({ kind: sinkKind, type: 'sink', config: inferAiSinkConfig(text, sinkKind, sinkItem) })
  return steps
}
async function buildAiFlow() {
  if (!isEditable.value) return setError('运行中任务不能修改画布，请先停止任务。')
  const prompt = aiPrompt.value?.trim()
  if (!prompt) return setError('请先输入 AI 编排需求。')
  aiGenerating.value = true
  try {
    // 调用AI API获取编排方案
    const context = {
      prompt: prompt,
      attachments: aiAttachments.value.map(a => ({ name: a.name, kind: a.kind, preview: a.preview || '' })),
      availableNodes: palette.map(p => ({ kind: p.kind, type: p.type, label: p.label })),
      configSpecs: CONTROL_CONFIG_SPECS,
    }
    const resp = await aiApi.assist({
      mode: 'canvas-orchestration',
      userPrompt: prompt,
      context: context,
    })

    const flowSpec = extractFlowSpec(resp.rawText)
    const normalizedFlow = flowSpec ? normalizeAiFlowSpec(flowSpec, prompt) : null
    if (normalizedFlow) {
      nodes.value = normalizedFlow.nodes
      edges.value = normalizedFlow.edges
      autoConnect()
      aiPlan.value = normalizedFlow.nodes
        .filter(n => !['start', 'end'].includes(n.data?.nodeType || n.id))
        .map(n => n.data?.label || n.data?.kind || '节点')
      const generatedReport = auditCanvasConfig(nodes.value)
      const completedText = normalizedFlow.completedSteps ? `，补齐 ${normalizedFlow.completedSteps} 个遗漏步骤` : ''
      notice.value = `AI 已按需求生成 ${aiPlan.value.length} 个业务节点和 ${edges.value.length} 条连线${completedText}；配置体检发现 ${generatedReport.errors.length} 个错误、${generatedReport.warnings.length} 个警告，请在右侧面板逐项确认。`
    } else {
      buildAiFlowLocal(prompt)
    }
    error.value = ''
  } catch (e) {
    buildAiFlowLocal(prompt)
  } finally {
    aiGenerating.value = false
  }
}

function normalizeAiFlowSpec(flowSpec, prompt) {
  const expectedSteps = buildAiSteps(prompt)
  const rawNodes = Array.isArray(flowSpec.nodes) ? flowSpec.nodes : []
  const usedIds = new Set(rawNodes.map((node) => String(node?.id || '')).filter(Boolean))
  let sequence = 1
  const createId = () => {
    let id = `node_${sequence++}`
    while (usedIds.has(id)) id = `node_${sequence++}`
    usedIds.add(id)
    return id
  }
  const normalizedNodes = []

  rawNodes.forEach((rawNode, index) => {
    if (!rawNode || typeof rawNode !== 'object') return
    const rawData = rawNode.data && typeof rawNode.data === 'object' ? rawNode.data : {}
    const rawKind = String(rawData.kind || '')
    const paletteItem = palette.find((item) => item.kind === rawKind && (!rawData.nodeType || item.type === rawData.nodeType))
      || palette.find((item) => item.kind === rawKind)
    const inferredType = rawKind === 'start' || String(rawNode.id) === 'start' ? 'start'
      : rawKind === 'end' || String(rawNode.id) === 'end' ? 'end'
      : paletteItem?.type
    const nodeType = ['start', 'end', 'source', 'transform', 'sink'].includes(rawData.nodeType)
      ? rawData.nodeType
      : inferredType
    if (!nodeType) return

    const suppliedId = String(rawNode.id || '')
    if (suppliedId && usedIds.has(suppliedId) && normalizedNodes.some((node) => node.id === suppliedId)) return
    const id = suppliedId || (nodeType === 'start' ? 'start' : nodeType === 'end' ? 'end' : createId())
    usedIds.add(id)
    const position = rawNode.position && Number.isFinite(Number(rawNode.position.x)) && Number.isFinite(Number(rawNode.position.y))
      ? { x: Number(rawNode.position.x), y: Number(rawNode.position.y) }
      : { x: 80 + index * 210, y: 350 }

    if (nodeType === 'start' || nodeType === 'end') {
      normalizedNodes.push({ ...defaultNode(nodeType, position), id })
      return
    }
    if (!paletteItem) return
    const rawConfig = rawData.config
    const config = rawConfig && typeof rawConfig === 'object' ? JSON.stringify(rawConfig) : String(rawConfig || '')
    normalizedNodes.push({
      id,
      type: 'native',
      position,
      data: cleanNodeData({
        ...rawData,
        nodeType,
        kind: paletteItem.kind,
        label: validText(rawData.label) ? rawData.label : paletteItem.label,
        icon: paletteItem.icon,
        desc: paletteItem.desc,
        config: config || defaultConfig(paletteItem),
        targetTable: typeof rawData.targetTable === 'string' ? rawData.targetTable : '',
      }),
    })
  })

  let completedSteps = 0
  const ensureControl = (type) => {
    if (normalizedNodes.some((node) => node.data.nodeType === type)) return
    const position = { x: type === 'start' ? 80 : 980, y: 350 }
    normalizedNodes.push(defaultNode(type, position))
  }
  ensureControl('start')
  ensureControl('end')

  for (const step of expectedSteps) {
    const exists = normalizedNodes.some((node) => node.data.nodeType === step.type && node.data.kind === step.kind)
    if (exists) continue
    const item = palette.find((entry) => entry.type === step.type && entry.kind === step.kind)
    if (!item) continue
    normalizedNodes.push({
      id: createId(),
      type: 'native',
      position: { x: 500, y: 350 },
      data: cleanNodeData({
        nodeType: step.type,
        kind: step.kind,
        label: item.label,
        icon: item.icon,
        desc: item.desc,
        config: step.config || defaultConfig(item),
        targetTable: step.type === 'sink' && step.kind === 'mysql' ? 'ai_generated_output' : '',
      }),
    })
    completedSteps += 1
  }

  enforceAiEndpointKinds(normalizedNodes, prompt)
  const nodeMap = new Map(normalizedNodes.map((node) => [node.id, node]))
  const seenEdges = new Set()
  const normalizedEdges = []
  for (const rawEdge of Array.isArray(flowSpec.edges) ? flowSpec.edges : []) {
    const source = String(rawEdge?.source || '')
    const target = String(rawEdge?.target || '')
    const from = nodeMap.get(source)
    const to = nodeMap.get(target)
    const key = `${source}->${target}`
    if (!from || !to || source === target || seenEdges.has(key) || !canConnectNodeTypes(from.data.nodeType, to.data.nodeType)) continue
    const label = String(rawEdge.label || rawEdge.condition || '')
    normalizedEdges.push({
      id: String(rawEdge.id || `edge_${source}_${target}_${normalizedEdges.length}`),
      source,
      target,
      label,
      condition: String(rawEdge.condition || label),
    })
    seenEdges.add(key)
  }

  layoutAiNodes(normalizedNodes, normalizedEdges, expectedSteps)
  return { nodes: normalizedNodes, edges: normalizedEdges, completedSteps }
}

function enforceAiEndpointKinds(nodes, prompt) {
  const endpointSteps = [
    { type: 'source', kind: detectSourceKind(prompt, aiAttachmentContext()) },
    { type: 'sink', kind: detectSinkKind(`${prompt} ${aiAttachmentContext()}`) },
  ]
  for (const expected of endpointSteps) {
    const node = nodes.find((item) => item.data.nodeType === expected.type)
    const item = palette.find((entry) => entry.type === expected.type && entry.kind === expected.kind)
    if (!node || !item) continue
    const keepConfig = node.data.kind === expected.kind && node.data.config
    node.data = cleanNodeData({
      ...node.data,
      nodeType: expected.type,
      kind: item.kind,
      label: item.label,
      icon: item.icon,
      desc: item.desc,
      config: keepConfig || defaultConfig(item),
      targetTable: expected.type === 'sink' && item.kind === 'mysql'
        ? (node.data.targetTable || 'ai_generated_output')
        : '',
    })
  }
}

function layoutAiNodes(nodes, edges, expectedSteps) {
  const expectedDepth = new Map([['start', 0]])
  expectedSteps.forEach((step, index) => expectedDepth.set(`${step.type}:${step.kind}`, index + 1))
  const endDepth = Math.max(1, expectedSteps.length) + 1
  expectedDepth.set('end:end', endDepth)

  const indegree = new Map(nodes.map((node) => [node.id, 0]))
  const outgoing = new Map(nodes.map((node) => [node.id, []]))
  for (const edge of edges) {
    indegree.set(edge.target, (indegree.get(edge.target) || 0) + 1)
    outgoing.get(edge.source)?.push(edge.target)
  }
  const depth = new Map()
  const roots = nodes.filter((node) => (indegree.get(node.id) || 0) === 0)
    .sort((a, b) => (a.data.nodeType === 'start' ? -1 : b.data.nodeType === 'start' ? 1 : 0))
  const queue = roots.filter((node) => node.data.nodeType === 'start').slice(0, 1)
  if (!queue.length && roots.length) queue.push(roots[0])
  queue.forEach((node) => depth.set(node.id, 0))
  while (queue.length) {
    const node = queue.shift()
    const currentDepth = depth.get(node.id) || 0
    for (const target of outgoing.get(node.id) || []) {
      depth.set(target, Math.max(depth.get(target) || 0, currentDepth + 1))
      indegree.set(target, (indegree.get(target) || 0) - 1)
      if (indegree.get(target) === 0) {
        const targetNode = nodes.find((item) => item.id === target)
        if (targetNode) queue.push(targetNode)
      }
    }
  }

  for (const node of nodes) {
    if (depth.has(node.id)) continue
    const controlDepth = expectedDepth.get(`${node.data.nodeType}:${node.data.kind}`)
    depth.set(node.id, controlDepth ?? (node.data.nodeType === 'end' ? endDepth : 2))
  }
  depth.set('start', 0)
  if (nodes.some((node) => node.data.nodeType === 'end')) depth.set('end', endDepth)

  const groups = new Map()
  const ordered = [...nodes].sort((a, b) => (depth.get(a.id) || 0) - (depth.get(b.id) || 0) || a.position.x - b.position.x || a.position.y - b.position.y)
  for (const node of ordered) {
    const nodeDepth = depth.get(node.id) || 0
    const groupIndex = groups.get(nodeDepth) || 0
    groups.set(nodeDepth, groupIndex + 1)
    node.position = { x: 80 + nodeDepth * 220, y: 180 + groupIndex * 150 }
  }
}

function buildAiFlowLocal(prompt) {
  const steps = buildAiSteps(prompt)
  if (!steps.length) return setError('请先输入 AI 编排需求。')
  const centerY = 350
  const startY = centerY - nodeBox(defaultNode('start', { x: 0, y: 0 })).height / 2
  const endY = centerY - nodeBox(defaultNode('end', { x: 0, y: 0 })).height / 2
  const generated = [
    defaultNode('start', { x: 80, y: startY }),
    ...steps.map((step, index) => {
      const node = nodeFromPalette(step.kind, { x: 280 + index * 210, y: 0 }, step.type)
      node.position.y = centerY - nodeBox(node).height / 2
      node.data.config = step.config || node.data.config
      if (node.data.nodeType === 'sink' && node.data.kind === 'mysql') {
        node.data.targetTable = 'ai_generated_output'
      }
      return node
    }),
    defaultNode('end', { x: 280 + steps.length * 210, y: endY }),
  ]
  nodes.value = generated
  edges.value = []
  autoConnect()
  aiPlan.value = steps.map((step) => {
    const item = palette.find((entry) => entry.type === step.type && entry.kind === step.kind)
    return item?.label || step.kind
  })
  const generatedReport = auditCanvasConfig(nodes.value)
  notice.value = `AI 已生成 ${steps.length} 个业务节点（本地规则）；配置体检发现 ${generatedReport.errors.length} 个错误、${generatedReport.warnings.length} 个警告，请在右侧面板逐项确认。`
}

function nodeFromPalette(kind, position, typeHint = '') {
  const item = palette.find((entry) => entry.kind === kind && (!typeHint || entry.type === typeHint))
  if (!item) throw new Error(`未知节点类型：${kind}`)
  return {
    id: nextId(),
    type: 'native',
    position,
    data: cleanNodeData({
      nodeType: item.type,
      kind: item.kind,
      label: item.label,
      icon: item.icon,
      desc: item.desc,
      config: defaultConfig(item),
      targetTable: item.type === 'sink' && item.kind === 'mysql' ? 'a22_demo_output' : '',
    }),
  }
}

function removeNode(id) {
  const node = nodes.value.find((item) => item.id === id)
  if (!node || ['start', 'end'].includes(node.data.nodeType)) return setError('开始和结束节点不能删除。')
  nodes.value = nodes.value.filter((item) => item.id !== id)
  edges.value = edges.value.filter((edge) => edge.source !== id && edge.target !== id)
  selectedEdgeId.value = null
}

function copyNode(node) {
  if (['start', 'end'].includes(node.data.nodeType)) return
  nodes.value.push({
    ...node,
    id: nextId(),
    position: { x: node.position.x + 36, y: node.position.y + 36 },
    data: { ...node.data, label: `${node.data.label}（副本）` },
  })
}

function parseConfigObject(text) {
  if (!text?.trim()) return {}
  try {
    const parsed = JSON.parse(text)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
  } catch {
    return null
  }
}

function normalizeKeyFieldsText(value) {
  if (Array.isArray(value)) return value.join(',')
  if (typeof value !== 'string') return ''
  const text = value.trim()
  if (!text.startsWith('[')) return text
  try {
    const parsed = JSON.parse(text)
    return Array.isArray(parsed) ? parsed.join(',') : text
  } catch {
    return text
  }
}

function hydrateDbSinkEditForm(config) {
  const parsed = parseConfigObject(config) || {}
  const mapping = parsed.fieldMapping && typeof parsed.fieldMapping === 'object' && !Array.isArray(parsed.fieldMapping)
    ? parsed.fieldMapping
    : {}
  editForm.value.connectionId = parsed.connectionId ? String(parsed.connectionId) : ''
  editForm.value.writeMode = parsed.writeMode === 'upsert' ? 'upsert' : 'append'
  editForm.value.keyFieldsText = normalizeKeyFieldsText(parsed.keyFields)
  editForm.value.fieldMappings = Object.entries(mapping)
    .map(([source, target]) => ({ source, target: String(target || '') }))
  if (!editForm.value.fieldMappings.length) editForm.value.fieldMappings.push({ source: '', target: '' })
  editForm.value.autoCreate = String(parsed.autoCreate).toLowerCase() === 'true'
  editForm.value.batchSize = Number(parsed.batchSize) > 0 ? Number(parsed.batchSize) : 500
}

function hydrateDbSourceEditForm(config) {
  const parsed = parseConfigObject(config) || {}
  editForm.value.connectionId = parsed.connectionId ? String(parsed.connectionId) : ''
  editForm.value.targetTable = String(parsed.table || parsed.sourceTable || '')
  editForm.value.readLimit = Number(parsed.limit) > 0 ? Number(parsed.limit) : 10000
}

function serializeDbSinkConfig() {
  if (!isManagedDbSinkEditor.value) return editForm.value.config
  const original = editForm.value.config
  const base = parseConfigObject(original)
  if (base === null) return original
  const connectionId = String(editForm.value.connectionId || '').trim()
  if (connectionId) {
    for (const key of ['url', 'username', 'password', 'driverClass', 'dialect', 'schemaName', 'dbType']) {
      delete base[key]
    }
  }
  base.connectionId = connectionId
  base.writeMode = editForm.value.writeMode
  const keyFields = editForm.value.keyFieldsText
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
  base.keyFields = JSON.stringify(keyFields)
  const fieldMapping = {}
  for (const row of editForm.value.fieldMappings) {
    const source = row.source.trim()
    const target = row.target.trim()
    if (source && target) fieldMapping[source] = target
  }
  base.fieldMapping = fieldMapping
  base.autoCreate = String(editForm.value.autoCreate)
  base.batchSize = String(Math.max(1, Math.min(Number(editForm.value.batchSize) || 500, 5000)))
  if (editForm.value.targetTable.trim()) {
    base.table = editForm.value.targetTable.trim()
    base.targetTable = editForm.value.targetTable.trim()
  } else {
    delete base.table
    delete base.targetTable
  }
  return JSON.stringify(base, null, 2)
}

function serializeDbSourceConfig() {
  if (!isManagedDbSourceEditor.value) return editForm.value.config
  const original = editForm.value.config
  const base = parseConfigObject(original)
  if (base === null) return original
  const connectionId = String(editForm.value.connectionId || '').trim()
  if (connectionId) {
    for (const key of ['url', 'username', 'password', 'driverClass', 'dialect', 'schemaName', 'dbType']) {
      delete base[key]
    }
  }
  base.connectionId = connectionId
  const table = editForm.value.targetTable.trim()
  if (table) base.table = table
  else delete base.table
  delete base.sourceTable
  base.limit = String(Math.max(1, Math.min(Number(editForm.value.readLimit) || 10000, 100000)))
  return JSON.stringify(base, null, 2)
}

function serializeDbEditConfig() {
  if (isManagedDbSourceEditor.value) return serializeDbSourceConfig()
  if (isManagedDbSinkEditor.value) return serializeDbSinkConfig()
  return editForm.value.config
}

const editConfigDraft = computed(() => serializeDbEditConfig())

function openEdit(node) {
  editingNode.value = node
  editForm.value = {
    label: node.data.label,
    config: node.data.config || '',
    targetTable: node.data.targetTable || '',
    connectionId: '',
    writeMode: 'append',
    keyFieldsText: '',
    fieldMappings: [{ source: '', target: '' }],
    autoCreate: false,
    batchSize: 500,
    readLimit: 10000,
  }
  if (node.data.nodeType === 'sink' && ['mysql', 'jdbc', 'oceanbase', 'postgresql', 'oracle'].includes(node.data.kind)) {
    hydrateDbSinkEditForm(node.data.config || '')
  } else if (node.data.nodeType === 'source' && ['mysql', 'jdbc', 'oceanbase', 'postgresql', 'oracle'].includes(node.data.kind)) {
    hydrateDbSourceEditForm(node.data.config || '')
  }
  error.value = ''
  notice.value = ''
}

async function loadOutputDataSources(preferredId = '') {
  try {
    outputDataSources.value = await outputDataSourceApi.list()
    if (preferredId && !outputDataSources.value.some((item) => String(item.id) === String(preferredId))) {
      editForm.value.connectionId = ''
    }
  } catch (exception) {
    if (dataSourceManagerVisible.value) return
    error.value = `加载数据库数据源失败：${exception.message}`
  }
}

function selectOutputDataSource(id) {
  editForm.value.connectionId = id ? String(id) : ''
}

function addFieldMappingRow() {
  editForm.value.fieldMappings.push({ source: '', target: '' })
}

function removeFieldMappingRow(index) {
  editForm.value.fieldMappings.splice(index, 1)
  if (!editForm.value.fieldMappings.length) editForm.value.fieldMappings.push({ source: '', target: '' })
}

function nodeConfigStatus(node) {
  return configReport.value.nodeReports.find((report) => report.nodeId === node.id)?.level || 'ok'
}

function nodeConfigIssues(node) {
  return configReport.value.nodeReports.find((report) => report.nodeId === node.id)?.issues || []
}

function locateConfigIssue(issue) {
  const node = nodes.value.find((item) => item.id === issue.nodeId)
  if (!node) return
  selectedNodeId.value = node.id
  if (isEditable.value) openEdit(node)
  notice.value = `已定位到“${node.data.label}”。`
}

function statusText(status) {
  return { ok: '配置通过', warning: '需要确认', error: '配置错误' }[status] || '配置通过'
}

function useConfigExample() {
  if (!editingNode.value) return
  const nodeType = editingNode.value.data.nodeType
  const kind = editingNode.value.data.kind
  const item = palette.find((entry) => entry.type === nodeType && entry.kind === kind)
  const example = item ? defaultConfig(item) : editDiagnostics.value.spec?.example || ''
  if (example) editForm.value.config = example
  notice.value = '已填入当前控件的可用示例，请继续改成真实字段和连接信息。'
  error.value = ''
}

function formatEditConfig() {
  if (!editingNode.value || !editForm.value.config.trim()) return
  try {
    editForm.value.config = JSON.stringify(JSON.parse(editForm.value.config), null, 2)
    notice.value = 'JSON 已格式化。'
  } catch {
    setError('当前内容不是合法 JSON，无法格式化。')
  }
}

function saveNodeEdit() {
  if (!editingNode.value) return
  if (!editForm.value.label.trim()) return setError('显示名称不能为空。')
  if (editHasError.value) {
    const firstError = editDiagnostics.value.issues.find((issue) => issue.level === 'error')
    return setError(firstError?.message || '当前控件配置存在错误。')
  }
  editingNode.value.data.config = serializeDbEditConfig()
  editingNode.value.data.targetTable = editForm.value.targetTable
  editingNode.value.data.label = editForm.value.label.trim()
  editingNode.value = null
  notice.value = '配置已通过实时体检并保存到画布。'
}

function nextHorizontalPosition(count) {
  const rightMost = nodes.value.length ? Math.max(...nodes.value.map((node) => node.position.x)) : 80
  return { x: Math.max(280, rightMost + 210), y: 250 }
}

function nodeBox(node) {
    const el = document.querySelector(`[data-node-id="${node.id}"]`)
    if (el) {
      return { width: el.offsetWidth, height: el.offsetHeight, offsetX: el.offsetLeft, offsetY: el.offsetTop }
    }
    const shape = shapeClass({ ...node.data, type: node.data.nodeType })
    if (shape === 'shape-diamond') return { width: 120, height: 120 }
    if (shape === 'shape-pill') return { width: 124, height: 66 }
    return { width: 120, height: 82 }
  }

function edgePath(edge) {
  const from = nodes.value.find((node) => node.id === edge.source)
  const to = nodes.value.find((node) => node.id === edge.target)
  if (!from || !to) return ''
  const fromBox = nodeBox(from)
  const toBox = nodeBox(to)
  const useMeasure = fromBox.offsetX != null && toBox.offsetX != null
  const dotOff = 13
  if (useMeasure) {
    const x1 = fromBox.offsetX + fromBox.width + dotOff
    const y1 = fromBox.offsetY + fromBox.height / 2
    const x2 = toBox.offsetX - dotOff
    const y2 = toBox.offsetY + toBox.height / 2
    return `M ${x1} ${y1} L ${x2} ${y2}`
  }
  const horizontal = Math.abs(to.position.x - from.position.x) >= Math.abs(to.position.y - from.position.y)
  if (horizontal) {
    const forward = to.position.x >= from.position.x
    const x1 = forward ? from.position.x + fromBox.width + dotOff : from.position.x - dotOff
    const y1 = from.position.y + fromBox.height / 2
    const x2 = forward ? to.position.x - dotOff : to.position.x + toBox.width + dotOff
    const y2 = to.position.y + toBox.height / 2
    return `M ${x1} ${y1} L ${x2} ${y2}`
  }
  const downward = to.position.y >= from.position.y
  const x1 = from.position.x + fromBox.width / 2
  const y1 = downward ? from.position.y + fromBox.height + dotOff : from.position.y - dotOff
  const x2 = to.position.x + toBox.width / 2
  const y2 = downward ? to.position.y - dotOff : to.position.y + toBox.height + dotOff
  return `M ${x1} ${y1} L ${x2} ${y2}`
}

function edgeLabel(edge) {
  return edge.label || edge.condition || ''
}

function edgeTone(edge) {
  const label = edgeLabel(edge)
  if (/不符合|异常|失败|false|reject|error/i.test(label)) return 'edge-reject'
  if (/符合|正常|成功|true|pass|success/i.test(label)) return 'edge-pass'
  return ''
}

function edgeLabelPosition(edge) {
  const from = nodes.value.find((node) => node.id === edge.source)
  const to = nodes.value.find((node) => node.id === edge.target)
  if (!from || !to) return { left: '0px', top: '0px' }
  const fromBox = nodeBox(from)
  const toBox = nodeBox(to)
  const useMeasure = fromBox.offsetX != null && toBox.offsetX != null
  const fromX = useMeasure ? fromBox.offsetX + fromBox.width / 2 : from.position.x + fromBox.width / 2
  const fromY = useMeasure ? fromBox.offsetY + fromBox.height / 2 : from.position.y + fromBox.height / 2
  const toX = useMeasure ? toBox.offsetX + toBox.width / 2 : to.position.x + toBox.width / 2
  const toY = useMeasure ? toBox.offsetY + toBox.height / 2 : to.position.y + toBox.height / 2
  return { left: `${Math.round((fromX + toX) / 2)}px`, top: `${Math.round((fromY + toY) / 2 - 13)}px` }
}

function graphSnapshot() {
  return {
    nodes: nodes.value.map((node) => ({
      id: node.id,
      type: 'native',
      position: { x: Math.round(node.position.x), y: Math.round(node.position.y) },
      data: cleanNodeData(node.data),
    })),
    edges: edges.value.map((edge) => ({ id: edge.id, source: edge.source, target: edge.target, label: edge.label || '', condition: edge.condition || edge.label || '' })),
  }
}

function graphIssue() {
  const types = nodes.value.map((node) => node.data.nodeType)
  if (!types.includes('source')) return '流程至少需要一个输入节点。'
  if (!types.includes('sink')) return '流程至少需要一个输出节点。'
  if (!edges.value.length) return '请先连接完整流程。'

  const outgoing = new Map(nodes.value.map((node) => [node.id, []]))
  const incoming = new Map(nodes.value.map((node) => [node.id, 0]))
  for (const edge of edges.value) {
    const from = nodes.value.find((node) => node.id === edge.source)
    const to = nodes.value.find((node) => node.id === edge.target)
    if (!from || !to) return '存在指向已删除节点的连线。'
    if (!allowed(from.data.nodeType, to.data.nodeType)) return `连线不合法：${from.data.label} → ${to.data.label}`
    outgoing.get(edge.source)?.push(edge.target)
    incoming.set(edge.target, (incoming.get(edge.target) || 0) + 1)
  }

  for (const node of nodes.value) {
    if (node.data.nodeType !== 'start' && (incoming.get(node.id) || 0) === 0) return `节点“${node.data.label}”缺少上游连线。`
    if (node.data.nodeType !== 'end' && !(outgoing.get(node.id) || []).length) return `节点“${node.data.label}”缺少下游连线。`
  }

  const levelIssue = checkLevelAndCycle(outgoing)
  if (levelIssue) return levelIssue
  return ''
}

function checkLevelAndCycle(outgoing) {
  const indegree = new Map(nodes.value.map((node) => [node.id, 0]))
  edges.value.forEach((edge) => indegree.set(edge.target, (indegree.get(edge.target) || 0) + 1))
  const queue = [...indegree.entries()].filter(([, degree]) => degree === 0).map(([id]) => id)
  const sorted = []
  while (queue.length) {
    const id = queue.shift()
    sorted.push(id)
    for (const next of outgoing.get(id) || []) {
      indegree.set(next, indegree.get(next) - 1)
      if (indegree.get(next) === 0) queue.push(next)
    }
  }
  if (sorted.length !== nodes.value.length) return '流程存在循环依赖，不能保存或执行。'

  const levels = new Map([['start', 1]])
  for (const id of sorted) {
    const level = levels.get(id) || 1
    for (const next of outgoing.get(id) || []) levels.set(next, Math.max(levels.get(next) || 1, level + 1))
  }
  return (levels.get('end') || 0) > 20 ? '流程最多支持 20 层，请拆分或简化流程。' : ''
}

async function saveGraph() {
  const issue = graphIssue()
  if (issue) return setError(issue)
  const report = configReport.value
  if (report.errors.length) {
    configReportVisible.value = true
    const firstError = report.errors[0]
    return setError(`“${firstError.label}”配置错误：${firstError.message}`)
  }
  if (report.warnings.length && !warningsAcknowledged.value) {
    configReportVisible.value = true
    notice.value = `发现 ${report.warnings.length} 个待确认警告；请检查配置体检面板并确认后再保存。`
    error.value = ''
    return
  }
  saving.value = true
  try {
    await taskApi.saveGraph(props.taskId, JSON.stringify(graphSnapshot()))
    graphSaved.value = true
    const warningText = report.warnings.length ? `，已确认 ${report.warnings.length} 个警告` : ''
    notice.value = `画布已保存，${report.passed}/${report.total} 个节点配置通过${warningText}。`
    error.value = ''
  } catch (exception) {
    setError(`保存失败：${exception.message}`)
  } finally {
    saving.value = false
  }
}

function confirmConfigWarnings() {
  if (!configReport.value.warnings.length) return
  warningsAcknowledged.value = true
  saveGraph()
}

function setError(message) {
  error.value = message
  notice.value = ''
}

watch(error, (message) => {
  if (errorTimer) clearTimeout(errorTimer)
  if (!message) return
  const current = message
  errorTimer = setTimeout(() => {
    if (error.value === current) error.value = ''
  }, 5000)
})

watch(notice, (message) => {
  if (noticeTimer) clearTimeout(noticeTimer)
  if (!message) return
  const current = message
  noticeTimer = setTimeout(() => {
    if (notice.value === current) notice.value = ''
  }, 5000)
})

watch(
  () => JSON.stringify(nodes.value.map((node) => ({
    id: node.id,
    label: node.data?.label || '',
    config: node.data?.config || '',
    targetTable: node.data?.targetTable || '',
  }))),
  () => { warningsAcknowledged.value = false },
)

watch([nodes, edges], () => {
  graphSaved.value = false
}, { deep: true })

async function loadGraph() {
  try {
    task.value = await taskApi.get(props.taskId)
    const graph = task.value.graphJson ? JSON.parse(task.value.graphJson) : null
    if (!graph?.nodes?.length) return resetGraph()
    nodes.value = graph.nodes.map((node) => ({ ...node, type: 'native', data: cleanNodeData(node.data) }))
    edges.value = Array.isArray(graph.edges) ? graph.edges.map((edge) => ({ ...edge, label: edge.label || edge.condition || '', condition: edge.condition || edge.label || '' })) : []
    if (!nodes.value.some((node) => node.data.nodeType === 'start')) nodes.value.unshift(defaultNode('start', { x: 360, y: 80 }))
    if (!nodes.value.some((node) => node.data.nodeType === 'end')) nodes.value.push(defaultNode('end', { x: 360, y: 560 }))
    nodeId = Math.max(0, ...nodes.value.map((node) => Number(String(node.id).replace('node_', ''))).filter(Number.isFinite)) + 1
    highlightNodeFromRun(props.highlightNodeId)
    applyNodeReplay(props.nodeReplay)
  } catch (exception) {
    setError(`加载画布失败：${exception.message}`)
  }
}

onMounted(async () => {
  await loadGraph()
  loadOutputDataSources()
  window.addEventListener('keydown', handleCanvasKeydown)
})

watch(() => props.highlightNodeId, (nodeId) => highlightNodeFromRun(nodeId))
watch(() => props.nodeReplay, (replay) => applyNodeReplay(replay), { deep: true })
watch(dataSourceManagerVisible, (visible) => {
  if (!visible) loadOutputDataSources(editForm.value.connectionId)
})

watch(() => props.highlightNodeId, (nodeId) => highlightNodeFromRun(nodeId))
watch(() => props.nodeReplay, (replay) => applyNodeReplay(replay), { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleCanvasKeydown)
  if (errorTimer) clearTimeout(errorTimer)
  if (noticeTimer) clearTimeout(noticeTimer)
  stopNodeTestPanelDrag()
})
</script>

<template>
  <div class="flow-editor">
    <div class="flow-header">
      <button class="link" @click="emit('back')">← 返回任务列表</button>
      <div>
        <h2 v-if="task">{{ task.name }} <small class="tag">{{ task.sourceType }} → {{ task.sinkType }}</small></h2>
        <small v-if="isRunning" class="flow-readonly">运行中任务为只读状态；停止后才能编辑。</small>
      </div>
      <div class="flow-actions">
        <span v-if="notice" class="notice-inline">{{ notice }}</span>
        <span v-if="error" class="error-inline">{{ error }}</span>
        <button class="btn btn-ghost datasource-toggle" @click="dataSourceManagerVisible = true">
          数据库数据源
        </button>
        <button class="btn btn-ghost onboarding-toggle" @click="onboardingVisible ? dismissOnboarding() : openOnboarding()">
          {{ onboardingVisible ? '收起指导' : '使用指导' }}
        </button>
        <button class="btn btn-ghost" :disabled="!isEditable" @click="buildDemoFlow">生成演示流程</button>
        <button class="btn btn-ai" :disabled="!isEditable" @click="buildDagBranchFlow">并行分支模板</button>
        <button class="btn btn-ai" :disabled="!isEditable" @click="buildDagTwoStageBranchFlow">二级分支模板</button>
        <button class="btn btn-ai" :disabled="!isEditable" @click="buildOfficeDataFlow">复杂办公模板</button>
        <button class="btn btn-ghost" :disabled="!isEditable" @click="autoConnect">重建连线</button>
        <button v-if="selectedEdgeId" class="btn btn-ghost" type="button" @click="clearEdgeSelection">取消选择连线</button>
        <button v-if="selectedEdgeId" class="btn btn-danger" type="button" :disabled="!isEditable" @click="removeEdge(selectedEdgeId)">删除选中连线</button>
        <button class="btn btn-danger" :disabled="!isEditable || saving" @click="resetCanvasConfirmVisible = true">重置画布</button>
        <button class="btn btn-primary" :disabled="saving || !isEditable" @click="saveGraph">{{ saving ? '保存中...' : '保存画布' }}</button>
      </div>
    </div>

    <section class="ai-orchestrator" :class="{ 'palette-disabled': !isEditable }">
      <div class="ai-orchestrator-main">
        <div class="ai-badge">AI 编排助手</div>
        <div>
          <h3>用一句话生成工作流</h3>
          <p>接入阿里云百炼大模型，输入需求即可自动生成画布编排；生成后仍可手动拖拽、编辑、复制和删除节点。</p>
        </div>
      </div>
      <div class="ai-prompt-box">
        <textarea v-model="aiPrompt" rows="2" placeholder="例如：读取 CSV，过滤金额大于 1000，字段脱敏，写入 MySQL"></textarea>
        <button class="btn btn-primary" :disabled="aiGenerating || !isEditable" @click="buildAiFlow">
          {{ aiGenerating ? '生成中...' : '生成编排' }}
        </button>
      </div>
      <div class="ai-upload-row">
        <input ref="aiFileInput" type="file" accept=".json,.csv,.xlsx,.xls,.xml,.kafka,.ndjson,.jsonl" multiple hidden @change="onAiAttachmentChange" />
        <input ref="aiImageInput" type="file" accept="image/*" multiple hidden @change="onAiAttachmentChange" />
        <button type="button" class="ai-upload-btn" :disabled="!isEditable" @click="aiFileInput?.click()">上传文件</button>
        <button type="button" class="ai-upload-btn" :disabled="!isEditable" @click="aiImageInput?.click()">上传图片</button>
        <span>附件会作为 AI 生成画布的上下文。</span>
      </div>
      <div v-if="aiAttachments.length" class="ai-attachment-row">
        <article v-for="item in aiAttachments" :key="item.id" class="ai-attachment">
          <img v-if="item.url" :src="item.url" alt="" />
          <span v-else>{{ item.kind === 'json-file' ? 'JSON' : item.kind.toUpperCase() }}</span>
          <b>{{ item.name }}</b>
          <button type="button" @click="removeAiAttachment(item.id)">移除</button>
        </article>
      </div>
      <div class="ai-example-row">
        <button v-for="example in aiExamples" :key="example" type="button" class="ai-example" @click="setAiExample(example)">
          {{ example }}
        </button>
      </div>
      <div v-if="aiPlan?.length" class="ai-plan">
        <span>生成链路</span>
        <b v-for="step in aiPlan" :key="step">{{ step }}</b>
      </div>
    </section>

    <div class="flow-body" :class="{ 'onboarding-open': onboardingVisible }">
      <aside class="palette" :class="{ 'palette-disabled': !isEditable }">
        <details v-for="group in paletteGroups" :key="group.key" class="palette-group folder-palette">
          <summary class="palette-title">
            <span class="folder-visual" aria-hidden="true">
              <i class="folder-paper paper-one"></i>
              <i class="folder-paper paper-two"></i>
              <i class="folder-paper paper-three"></i>
              <i class="folder-back"></i>
              <i class="folder-front"></i>
            </span>
            <span>{{ group.title }}</span>
            <small>{{ group.desc }}</small>
          </summary>
          <div class="palette-grid">
            <button
              v-for="item in group.items"
              :key="`${item.type}-${item.kind}`"
              type="button"
              class="palette-item"
              :class="[`palette-${group.key}`, shapeClass(item), { 'palette-fixed': item.type === 'control' }]"
              :draggable="item.type !== 'control' && isEditable"
              :data-label="item.label"
              :title="`${item.label}：${item.desc}`"
              @dragstart="onPaletteDragStart($event, item)"
              @click="addNode(item)"
            >
              <span class="shape-preview"><span>{{ item.icon }}</span></span>
              <b>{{ item.label }}</b>
            </button>
          </div>
        </details>
      </aside>

      <div class="native-canvas" @dragover.prevent @drop.prevent="onDrop">
        <div class="native-canvas-board" :class="{ 'onboarding-open': onboardingVisible }">
          <aside v-if="onboardingVisible" class="onboarding-guide" aria-label="画布使用指导">
            <div class="onboarding-head">
              <div>
                <span class="onboarding-kicker">初次使用</span>
                <h3>画布使用指导</h3>
              </div>
              <button class="onboarding-close" type="button" aria-label="关闭使用指导" @click="dismissOnboarding">×</button>
            </div>
            <div class="onboarding-progress" aria-hidden="true">
              <span :style="{ width: `${onboardingProgress}%` }"></span>
            </div>
            <div class="onboarding-summary">
              <b>{{ onboardingDoneCount }}/{{ onboardingTasks.length }} 已完成</b>
              <span v-if="onboardingNextTask">下一步：{{ onboardingNextTask.label }}</span>
              <span v-else>引导完成，可以保存并运行流程。</span>
            </div>
            <p class="onboarding-intro">按顺序完成下面六步即可搭好第一条流程；可随时关闭，之后从页头重新打开。</p>
            <ol class="onboarding-list">
              <li
                v-for="(task, index) in onboardingTasks"
                :key="task.key"
                :class="{ done: task.done, current: !task.done && onboardingNextTask?.key === task.key }"
              >
                <span class="onboarding-step">{{ task.done ? '✓' : index + 1 }}</span>
                <div>
                  <b>{{ task.label }}</b>
                  <small>{{ task.description }}</small>
                </div>
              </li>
            </ol>
            <div class="onboarding-foot">
              <span>{{ onboardingProgress }}%</span>
              <button type="button" @click="dismissOnboarding">知道了，开始编排</button>
            </div>
          </aside>
          <div v-if="!onboardingVisible" class="connection-tip">操作提示：AI 生成后请先查看右上角“配置体检”，按提示修改错误；再拖拽节点、双击编辑配置并保存画布。</div>
          <div class="dag-insight-card">
            <b>DAG 多链路</b>
            <span>{{ dagStats.sourceCount }} 输入 · {{ dagStats.transformCount }} 处理 · {{ dagStats.sinkCount }} 输出 · {{ dagStats.branchCount }} 个分支点</span>
            <small>一个输入可分流到多个处理/输出节点，条件过滤支持“符合/不符合”双分支。</small>
          </div>
          <div
            v-if="canTestSelectedNode"
            ref="nodeTestPanel"
            class="node-test-panel"
            :class="{ dragging: nodeTestPanelDrag }"
            :style="{ left: `${nodeTestPanelPosition.x}px`, top: `${nodeTestPanelPosition.y}px` }"
          >
            <div class="node-test-header" title="按住标题栏可拖动" @pointerdown.stop="startNodeTestPanelDrag">
              <span class="node-test-badge"><i aria-hidden="true">⋮⋮</i> 节点级单测</span>
              <div class="node-test-heading">
                <b>{{ selectedNode.data.label }}</b>
                <span>{{ selectedNode.data.kind }} 控件 · 仅在内存中执行，不写入结果表</span>
                <small v-if="nodeReplay?.nodeId === selectedNode.id">回放来源：运行记录 #{{ nodeReplay.runId }} · {{ nodeReplay.sourceName }}</small>
              </div>
              <button class="node-test-close" type="button" title="关闭节点级单测" @click="closeNodeTest">
                <span aria-hidden="true">×</span> 关闭
              </button>
            </div>
            <div class="node-test-toolbar">
              <span>输入 JSON 数组，测试结果仅用于当前控件验证。</span>
              <div class="node-test-actions">
                <button class="btn btn-ghost btn-sm" type="button" @click="resetNodeTestInput">恢复样例</button>
                <button class="btn btn-primary btn-sm" type="button" :disabled="nodeTesting" @click="testSelectedNode">{{ nodeTesting ? '测试中...' : '测试当前节点' }}</button>
              </div>
            </div>
            <textarea
              v-model="nodeTestInput"
              rows="6"
              spellcheck="false"
              aria-label="节点级单测输入数据"
              placeholder='[{"field":"value"}]'
            ></textarea>
            <div v-if="nodeTestResult" class="node-test-result">
              <p><b>测试完成</b><span>输入 {{ nodeTestResult.inputCount }} 条 · 输出 {{ nodeTestResult.outputCount }} 条</span></p>
              <div v-for="log in nodeTestResult.logs" :key="log" class="run-log">[TEST] {{ log }}</div>
              <pre>{{ JSON.stringify(nodeTestResult.preview, null, 2) }}</pre>
            </div>
          </div>
          <svg class="native-edge-layer">
            <defs>
              <marker id="a22-flow-arrow" viewBox="0 0 16 12" refX="15" refY="6" markerWidth="12" markerHeight="9" orient="auto" markerUnits="userSpaceOnUse">
                <path d="M 1 1 L 14 6 L 1 11 Q 4 6 1 1 Z" fill="#3b82f6" stroke="#2563eb" stroke-width="0.5" stroke-linejoin="round" />
              </marker>
            </defs>
            <path
              v-for="edge in edges"
              :key="edge.id"
              class="native-edge"
              :class="[edgeTone(edge), { selected: selectedEdgeId === edge.id }]"
              :d="edgePath(edge)"
              :aria-label="`连线：${edgeLabel(edge) || '未命名'}，从 ${edge.source} 到 ${edge.target}`"
              role="button"
              tabindex="0"
              marker-end="url(#a22-flow-arrow)"
              @click.stop="selectEdge(edge)"
              @dblclick.stop="removeEdge(edge.id)"
              @keydown="handleEdgeKeydown($event, edge)"
            />
          </svg>
          <span
            v-for="edge in edges.filter(edgeLabel)"
            :key="`${edge.id}-label`"
            class="native-edge-label"
            :class="[edgeTone(edge), { selected: selectedEdgeId === edge.id }]"
            :style="edgeLabelPosition(edge)"
            role="button"
            tabindex="0"
            @click.stop="selectEdge(edge)"
            @dblclick.stop="removeEdge(edge.id)"
            @keydown="handleEdgeKeydown($event, edge)"
          >{{ edgeLabel(edge) }}<template v-if="selectedEdgeId === edge.id"> · 删除</template></span>

          <div
            v-for="node in nodes"
            :key="node.id"
            class="native-node"
            :data-node-id="node.id"
            :class="[`node-${node.data.nodeType}`, nodeShapeClass(node), { selected: selectedNodeId === node.id, pending: pendingSourceId === node.id, 'failure-highlight': highlightNodeId === node.id }]"
            :style="{ left: `${node.position.x}px`, top: `${node.position.y}px`, borderColor: typeColor(node.data.nodeType), background: typeBg(node.data.nodeType) }"
            @pointerdown="startDrag($event, node)"
            @click.stop="clickNode(node)"
            @dblclick.stop="openEdit(node)"
          >
            <button v-if="node.data.nodeType !== 'start'" class="connection-dot target-dot" title="连接到此节点" @click.stop="clickNode(node)" />
            <div class="node-header" :style="{ background: typeColor(node.data.nodeType), color: '#f8fbff', textShadow: '0 1px 2px rgba(2,12,20,.45)' }">
              <span>{{ node.data.icon }}</span>
              <b>{{ typeLabel(node.data.nodeType) }}</b>
              <em
                class="node-health"
                :class="`health-${nodeConfigStatus(node)}`"
                :title="`${statusText(nodeConfigStatus(node))}：${nodeConfigIssues(node).map(issue => issue.message).join('；') || '配置符合当前规则'}`"
              >{{ nodeConfigStatus(node) === 'ok' ? '✓' : (nodeConfigStatus(node) === 'warning' ? '!' : '×') }}</em>
            </div>
            <div class="node-body">{{ node.data.label }}</div>
            <div v-if="node.data.config" class="node-config">{{ node.data.config }}</div>
            <div v-if="isEditable" class="node-tools">
              <button @click.stop="openEdit(node)">编辑</button>
              <button v-if="!['start', 'end'].includes(node.data.nodeType)" @click.stop="copyNode(node)">复制</button>
              <button v-if="!['start', 'end'].includes(node.data.nodeType)" class="node-delete" @click.stop="removeNode(node.id)">删除</button>
            </div>
            <button v-if="node.data.nodeType !== 'end'" class="connection-dot source-dot" title="从此节点开始连线" @click.stop="startConnect($event, node)" />
          </div>
        </div>
      </div>
      <aside class="config-assist" :class="{ collapsed: !configReportVisible }">
        <button class="config-assist-header" type="button" @click="configReportVisible = !configReportVisible">
          <span>
            <b>配置体检</b>
            <small>{{ configReport.passed }}/{{ configReport.total }} 个节点通过</small>
          </span>
          <em :class="`report-${configReport.level}`">
            {{ configReport.errors.length }} 错误 · {{ configReport.warnings.length }} 警告
          </em>
          <i>{{ configReportVisible ? '−' : '+' }}</i>
        </button>
        <div v-if="configReportVisible" class="config-assist-body">
          <div class="config-score" :class="`score-${configReport.level}`">
            <strong>{{ Math.round((configReport.passed / Math.max(configReport.total, 1)) * 100) }}</strong>
            <span>
              <b>{{ statusText(configReport.level) }}</b>
              <small>AI 负责生成，业务参数仍需由你确认。</small>
            </span>
          </div>
          <div v-if="configReport.issues.length" class="config-issue-list">
            <button
              v-for="issue in configReport.issues"
              :key="`${issue.nodeId}-${issue.message}`"
              type="button"
              class="config-issue"
              :class="`issue-${issue.level}`"
              @click="locateConfigIssue(issue)"
            >
              <span>{{ issue.level === 'error' ? '×' : '!' }}</span>
              <div>
                <b>{{ issue.label }}</b>
                <p>{{ issue.message }}</p>
                <small v-if="issue.hint">{{ issue.hint }}</small>
              </div>
              <i>定位</i>
            </button>
          </div>
          <div v-else class="config-all-good">✓ 所有控件均通过当前配置规则。</div>
          <button
            v-if="configReport.warnings.length && !warningsAcknowledged"
            class="btn btn-primary btn-sm config-confirm"
            type="button"
            @click="confirmConfigWarnings"
          >
            确认 {{ configReport.warnings.length }} 个警告并保存
          </button>
          <small class="config-assist-footnote">错误会阻止保存；警告允许保存，但需要明确确认。</small>
        </div>
      </aside>
    </div>

    <div v-if="editingNode" class="modal-mask edit-mask" @click.self="editingNode = null">
      <form class="modal config-modal" @submit.prevent="saveNodeEdit">
        <div class="section-title">
          <div>
            <small class="config-modal-kicker">配置体检向导</small>
            <h2>{{ editingNode.data.label }}</h2>
          </div>
          <button type="button" class="link" @click="editingNode = null">关闭</button>
        </div>

        <div class="config-modal-grid">
          <div class="config-modal-main">
            <label>显示名称 <input v-model="editForm.label" placeholder="用业务含义命名节点"></label>

            <template v-if="!['start', 'end'].includes(editingNode.data.nodeType)">
              <template v-if="isManagedDbSourceEditor">
                <label class="db-output-field">
                  数据库输入数据源
                  <span class="db-source-select-row">
                    <select v-model="editForm.connectionId">
                      <option value="">使用兼容配置中的连接参数</option>
                      <option v-for="source in outputDataSources" :key="source.id" :value="String(source.id)">
                        {{ source.name }}（{{ source.dbType }}）
                      </option>
                    </select>
                    <button type="button" class="btn btn-ghost btn-sm" @click="dataSourceManagerVisible = true">管理数据源</button>
                  </span>
                </label>
                <small v-if="!outputDataSources.length" class="db-source-empty">
                  尚未配置数据库数据源；建议先创建并测试连接，再回到这里选择。
                </small>

                <div class="db-output-grid">
                  <label class="db-output-field">
                    输入表名
                    <input v-model="editForm.targetTable" placeholder="例如：orders">
                  </label>
                  <label class="db-output-field">
                    读取上限
                    <input
                      v-model.number="editForm.readLimit"
                      type="number"
                      min="1"
                      max="100000"
                      placeholder="10000"
                    >
                  </label>
                </div>

                <details class="db-output-advanced">
                  <summary>兼容旧版 JSON 参数</summary>
                  <textarea
                    v-model="editForm.config"
                    rows="5"
                    spellcheck="false"
                    placeholder="{}"
                  ></textarea>
                  <button type="button" class="btn btn-ghost btn-sm" @click="formatEditConfig">格式化 JSON</button>
                </details>
              </template>
              <template v-else-if="isManagedDbSinkEditor">
                <label class="db-output-field">
                  数据库输出数据源
                  <span class="db-source-select-row">
                    <select v-model="editForm.connectionId">
                      <option value="">使用兼容配置中的连接参数</option>
                      <option v-for="source in outputDataSources" :key="source.id" :value="String(source.id)">
                        {{ source.name }}（{{ source.dbType }}）
                      </option>
                    </select>
                    <button type="button" class="btn btn-ghost btn-sm" @click="dataSourceManagerVisible = true">管理数据源</button>
                  </span>
                </label>
                <small v-if="!outputDataSources.length" class="db-source-empty">
                  尚未配置数据库数据源；建议先创建并测试连接，再回到这里选择。
                </small>

                <label class="db-output-field">
                  目标表名
                  <input v-model="editForm.targetTable" placeholder="例如：orders_result">
                </label>

                <div class="db-output-grid">
                  <label class="db-output-field">
                    写入模式
                    <select v-model="editForm.writeMode">
                      <option value="append">追加写入</option>
                      <option value="upsert">按主键更新或插入</option>
                    </select>
                  </label>
                  <label v-if="editForm.writeMode === 'upsert'" class="db-output-field">
                    UPSERT 键
                    <input v-model="editForm.keyFieldsText" placeholder="order_id 或 order_id,customer_id">
                  </label>
                  <label class="db-output-field">
                    批量大小
                    <input v-model.number="editForm.batchSize" type="number" min="1" max="5000">
                  </label>
                </div>

                <div class="field-mapping-editor">
                  <div class="field-mapping-head">
                    <div>
                      <b>字段映射</b>
                      <span>输入字段 → 目标表字段；留空表示同名字段直接写入</span>
                    </div>
                    <button type="button" class="btn btn-ghost btn-sm" @click="addFieldMappingRow">添加映射</button>
                  </div>
                  <div
                    v-for="(row, index) in editForm.fieldMappings"
                    :key="index"
                    class="field-mapping-row"
                  >
                    <input v-model="row.source" placeholder="输入字段，例如 amount">
                    <span aria-hidden="true">→</span>
                    <input v-model="row.target" placeholder="目标字段，例如 total_amount">
                    <button type="button" title="删除此映射" @click="removeFieldMappingRow(index)">×</button>
                  </div>
                </div>

                <label class="db-output-check">
                  <input v-model="editForm.autoCreate" type="checkbox">
                  <span><b>目标表不存在时自动创建</b><small>会根据首批数据推断字段类型；已有表不受影响。</small></span>
                </label>

                <details class="db-output-advanced">
                  <summary>兼容旧版 JSON 参数</summary>
                  <textarea
                    v-model="editForm.config"
                    rows="5"
                    spellcheck="false"
                    placeholder="{}"
                  ></textarea>
                  <button type="button" class="btn btn-ghost btn-sm" @click="formatEditConfig">格式化 JSON</button>
                </details>
              </template>

              <template v-else>
                <label
                  v-if="editingNode.data.nodeType === 'sink' && ['mysql', 'jdbc', 'oceanbase'].includes(editingNode.data.kind)"
                >
                  目标表名
                  <input v-model="editForm.targetTable" placeholder="例如：a22_demo_output">
                </label>

                <div class="config-field-title">
                  <label for="control-config">配置参数</label>
                  <span>{{ editDiagnostics.spec?.format || '无需配置' }}</span>
                </div>
                <textarea
                  id="control-config"
                  v-model="editForm.config"
                  rows="11"
                  spellcheck="false"
                  :placeholder="editDiagnostics.spec?.example || '开始和结束节点无需配置'"
                ></textarea>

                <div class="config-editor-tools">
                  <span :class="`live-${editDiagnostics.level}`">
                    {{ editDiagnostics.level === 'error' ? '× 存在错误' : (editDiagnostics.level === 'warning' ? '! 需要确认' : '✓ 实时校验通过') }}
                  </span>
                  <div>
                    <button type="button" class="btn btn-ghost btn-sm" :disabled="!editDiagnostics.spec" @click="useConfigExample">使用示例</button>
                    <button type="button" class="btn btn-ghost btn-sm" :disabled="!editForm.config.trim()" @click="formatEditConfig">格式化 JSON</button>
                  </div>
                </div>
              </template>

              <div class="edit-diagnostics" :class="`diagnostics-${editDiagnostics.level}`">
                <div v-if="!editDiagnostics.issues.length" class="diagnostic-ok">
                  ✓ 语法和必填参数均符合当前控件规则。
                </div>
                <div v-for="issue in editDiagnostics.issues" :key="issue.message" class="diagnostic-item" :class="`diagnostic-${issue.level}`">
                  <b>{{ issue.level === 'error' ? '错误' : '警告' }}</b>
                  <span>{{ issue.message }}</span>
                  <small v-if="issue.hint">{{ issue.hint }}</small>
                </div>
              </div>
            </template>

            <p v-else class="config-hint">开始和结束节点是固定节点，无需设置控件参数，建议只调整位置。</p>
          </div>

          <aside class="config-guide">
            <template v-if="isManagedDbSourceEditor">
              <div class="config-guide-head">
                <b>数据库输入配置</b>
                <span>可视化配置</span>
              </div>
              <p>选择已测试的数据源后，运行时会从服务端安全读取连接信息，不会把明文密码保存到画布。</p>
              <div class="config-guide-title">配置要点</div>
              <dl>
                <div>
                  <dt>输入数据源 <em class="required">推荐</em></dt>
                  <dd><code>连接 MySQL、PostgreSQL、Oracle 等数据库</code></dd>
                </div>
                <div>
                  <dt>输入表名 <em class="required">必填</em></dt>
                  <dd><code>选择要读取的业务表，可包含 Schema</code></dd>
                </div>
                <div>
                  <dt>读取上限 <em class="required">必填</em></dt>
                  <dd><code>1–100000，默认读取 10000 条</code></dd>
                </div>
                <div>
                  <dt>兼容参数 <em>可选</em></dt>
                  <dd><code>旧画布仍可使用 url、username、password</code></dd>
                </div>
              </dl>
            </template>
            <template v-else-if="isManagedDbSinkEditor">
              <div class="config-guide-head">
                <b>数据库输出配置</b>
                <span>可视化配置</span>
              </div>
              <p>选择已测试的数据源后，运行时会从服务端安全读取连接信息，不会把明文密码保存到画布。</p>
              <div class="config-guide-title">配置要点</div>
              <dl>
                <div>
                  <dt>输出数据源 <em class="required">推荐</em></dt>
                  <dd><code>连接 MySQL、PostgreSQL、Oracle 等数据库</code></dd>
                </div>
                <div>
                  <dt>写入模式 <em class="required">必填</em></dt>
                  <dd><code>append：追加；upsert：按键更新或插入</code></dd>
                </div>
                <div>
                  <dt>字段映射 <em>可选</em></dt>
                  <dd><code>输入字段 → 目标表字段</code></dd>
                </div>
                <div>
                  <dt>兼容参数 <em>可选</em></dt>
                  <dd><code>旧画布仍可使用 url、username、password</code></dd>
                </div>
              </dl>
            </template>
            <template v-else-if="editDiagnostics.spec">
              <div class="config-guide-head">
                <b>{{ editDiagnostics.spec.title }}</b>
                <span>{{ editDiagnostics.spec.format }}</span>
              </div>
              <p>{{ editDiagnostics.spec.summary }}</p>
              <div class="config-guide-title">字段说明</div>
              <dl>
                <div v-for="field in editDiagnostics.spec.fields" :key="`${field.description}-${field.example}`">
                  <dt>
                    {{ field.description }}
                    <em :class="{ required: field.required }">{{ field.required ? '必填' : '可选' }}</em>
                  </dt>
                  <dd v-if="field.example"><code>{{ field.example }}</code></dd>
                </div>
              </dl>
              <div class="config-guide-title">可直接使用的示例</div>
              <pre>{{ editDiagnostics.spec.example }}</pre>
            </template>
            <template v-else>
              <div class="config-guide-head"><b>固定节点</b></div>
              <p>开始和结束节点只负责标识流程边界，没有业务参数。</p>
            </template>
          </aside>
        </div>

        <div class="config-actions">
          <span :class="`modal-status modal-${editDiagnostics.level}`">
            {{ statusText(editDiagnostics.level) }}
          </span>
          <div class="config-modal-buttons">
            <button type="button" class="btn btn-ghost" @click="editingNode = null">取消</button>
            <button class="btn btn-primary" type="submit" :disabled="editHasError">保存配置</button>
          </div>
        </div>
      </form>
    </div>
    <div
      v-if="resetCanvasConfirmVisible"
      class="modal-mask edit-mask"
      @click.self="cancelCanvasReset"
    >
      <section
        class="modal reset-confirm"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="reset-canvas-title"
        aria-describedby="reset-canvas-description"
      >
        <div class="reset-confirm-icon" aria-hidden="true">!</div>
        <h2 id="reset-canvas-title">确认重置画布？</h2>
        <p id="reset-canvas-description">
          将删除当前全部业务节点和连线，并恢复为初始状态：仅保留“开始”和“结束”节点。
        </p>
        <div class="reset-confirm-note">
          重置仅影响当前编辑内容；确认后请重新编排流程，并按需保存画布。
        </div>
        <div class="reset-confirm-actions">
          <button type="button" class="btn btn-ghost" @click="cancelCanvasReset">取消</button>
          <button type="button" class="btn btn-danger" @click="confirmCanvasReset">确认重置</button>
        </div>
      </section>
    </div>
    <OutputDataSourceManager
      :visible="dataSourceManagerVisible"
      :selected-id="editForm.connectionId"
      :readonly="!isEditable"
      @close="dataSourceManagerVisible = false"
      @selected="selectOutputDataSource"
    />
  </div>
</template>








