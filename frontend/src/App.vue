<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  aiApi,
  authApi,
  clearSession,
  collaborationApi,
  exportApi,
  fileApi,
  flinkApi,
  getStoredUser,
  getToken,
  onAuthExpired,
  outputDataSourceApi,
  saveSession,
  tableApi,
  taskApi,
  taskRunApi,
} from './api'
import FlowEditor from './FlowEditor.vue'
import LoginPage from './LoginPage.vue'
import OutputDataSourceManager from './OutputDataSourceManager.vue'
import TaskTable from './TaskTable.vue'
import UserManage from './UserManage.vue'

const ROLE_PAGES = {
  SADMIN: ['概览', '数据工作区', '作业编排', '任务管理', '持续处理', '运行日志', 'Flink 作业', '用户管理'],
  ADMIN: ['概览', '数据工作区', '作业编排', '任务管理', '持续处理', '运行日志', 'Flink 作业', '用户管理'],
  USER: ['概览', '数据工作区', '作业编排', '任务管理', '持续处理', '运行日志'],
  OPS_READONLY: ['概览', '数据工作区', '任务管理', '持续处理', '运行日志', 'Flink 作业'],
}
const MANAGED_STATUSES = ['PUBLISHED', 'RUNNING', 'STOPPED', 'SUCCEEDED', 'FAILED']

const activePage = ref('概览')
const currentUser = ref(getStoredUser())
const booting = ref(Boolean(getToken()))
const tasks = ref([])
const files = ref([])
const taskRuns = ref([])
const flinkHealth = ref(null)
const flinkJob = ref(null)
const flinkSubmitting = ref(false)
const flinkJobHistory = ref([])
const iframeLoaded = ref(false)
const error = ref('')
const errorDetail = ref('')
const showErrorDetail = ref(false)
const notice = ref('')
let errorTimer = null
let noticeTimer = null
const showCreate = ref(false)
const showEditTask = ref(false)
const preview = ref(null)
const tablePreview = ref(null)
const uploading = ref(false)
const uploadProgress = ref(0)
const fileInput = ref(null)
const outputDataSources = ref([])
const outputDataSourceManagerVisible = ref(false)
const selectedOutputDataSourceId = ref('')
const outputDataSourcesLoading = ref(false)
const editingTaskId = ref(null)
const editingTaskReadonly = ref(false)
const selectedRunTaskId = ref(null)
const executingTaskId = ref(null)
const continuousActionId = ref(null)
const executeResult = ref(null)
const selectedRunDetail = ref(null)
const selectedRunAiAnalysis = ref(null)
const runAiLoading = ref(false)
const highlightedEditorNodeId = ref('')
const nodeReplayPayload = ref(null)
const replayLoadingRunId = ref(null)
const pendingExecuteTask = ref(null)
const selectedSourceFileId = ref('')
const showProfile = ref(false)
const avatarInput = ref(null)
const editingUsername = ref(false)
const newUsername = ref('')
const editingDisplayName = ref(false)
const newDisplayName = ref('')
const changePasswordForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })

// 筛选条件
const filterUsername = ref('')
const filterStartDate = ref('')
const filterEndDate = ref('')

const editingFileId = ref(null)
const editingFileName = ref('')

// 运行日志筛选条件
const runFilterName = ref('')
const runFilterStartDate = ref('')
const runFilterEndDate = ref('')
const runFilterStatus = ref('')
const runFilterTrigger = ref('')
const runFilterAlertOnly = ref(false)

// 运行日志分页
const runPage = ref(1)
const runPageSize = 5
// 数据工作区分页
const filePage = ref(1)
const filePageSize = 5
const fileSearchKeyword = ref('')
const overviewTick = ref(0)
const overviewOrbitPaused = ref(false)
let overviewRaf = null
let overviewLastFrame = 0

const taskManagementView = ref('list')
const collaborationPositions = ref(readLocalJson('a22_collaboration_positions', {}))
const collaborationLinks = ref({})
const collaborationSelectedId = ref('')
const collaborationDrag = ref(null)
const collaborationStatusFilter = ref('')
const collaborationRoleFilter = ref('')
const collaborationOnlyMine = ref(false)
const collaborationOnlyAbnormal = ref(false)
const collaborationLayoutMode = ref('owner')
const collaborationFocusUsername = ref('')
const collaborationContextMenu = ref({ visible: false, x: 0, y: 0, nodeId: '' })
const collaborationNewMember = ref('')
const collaborationNewRole = ref('USER')
const collaborationNewRelation = ref('COLLABORATOR')
const collaborationKnownUsers = ref([])
const collaborationMemberFocused = ref(false)


const blankTaskForm = () => ({
  name: '',
  sourceType: 'JSON',
  sinkType: 'MySQL',
  transforms: ['字段映射'],
  startTime: '',
  deadline: '',
  runMode: 'ONCE',
  scheduleType: 'MANUAL',
  scheduleExpression: '',
  continuousRemark: '',
  incrementalMode: 'NONE',
  cursorField: '',
  lastCursorValue: '',
  processedField: 'process_status',
  unprocessedValue: 'UNPROCESSED',
  processedValue: 'DONE',
  dedupKeys: 'order_id'
})
const form = ref(blankTaskForm())
const editForm = ref({ id: null, ...blankTaskForm() })

const statusText = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  RUNNING: '运行中',
  STOPPED: '已停止',
  SUCCEEDED: '成功',
  FAILED: '失败',
}
const roleText = { SADMIN: '高级管理员', ADMIN: '管理员', USER: '普通用户', OPS_READONLY: '只读运维' }
const collaborationRelationText = {
  OWNER: '创建者',
  LEADER: '负责人',
  COLLABORATOR: '协作者',
  VIEWER: '查看者',
  OPS: '运维观察者',
}

const role = computed(() => currentUser.value?.role || '')
const pages = computed(() => ROLE_PAGES[role.value] || [])
const activePageIndex = computed(() => Math.max(0, pages.value.indexOf(activePage.value)))
const canWrite = computed(() => role.value === 'SADMIN' || role.value === 'ADMIN' || role.value === 'USER')
const canExecute = computed(() => role.value === 'SADMIN' || role.value === 'ADMIN' || role.value === 'USER')
const canUseFlink = computed(() => role.value === 'SADMIN' || role.value === 'ADMIN')

function readLocalJson(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(key) || JSON.stringify(fallback))
  } catch {
    return fallback
  }
}

function writeLocalJson(key, value) {
  localStorage.setItem(key, JSON.stringify(value))
}

function userAvatarKey(user = currentUser.value) {
  return user?.id ?? user?.userId ?? user?.uid ?? user?.username ?? ''
}

function avatarInitial(user = currentUser.value) {
  return (user?.displayName || user?.username || '?').trim().charAt(0).toUpperCase()
}

function readAvatarStore() {
  try {
    return JSON.parse(localStorage.getItem('a22_user_avatars') || '{}')
  } catch {
    return {}
  }
}

function applyStoredAvatar(user) {
  if (!user) return user
  const key = userAvatarKey(user)
  const avatarUrl = key ? readAvatarStore()[key] : ''
  return avatarUrl ? { ...user, avatarUrl } : user
}

function persistCurrentUser() {
  if (currentUser.value) localStorage.setItem('a22_auth_user', JSON.stringify(currentUser.value))
}

// 导航图标映射
const navIcons = {
  '概览': '<svg viewBox="0 0 20 20" fill="currentColor"><path d="M3 4a1 1 0 011-1h3a1 1 0 011 1v3a1 1 0 01-1 1H4a1 1 0 01-1-1V4zm9 0a1 1 0 011-1h3a1 1 0 011 1v3a1 1 0 01-1 1h-3a1 1 0 01-1-1V4zM3 13a1 1 0 011-1h3a1 1 0 011 1v3a1 1 0 01-1 1H4a1 1 0 01-1-1v-3zm9 0a1 1 0 011-1h3a1 1 0 011 1v3a1 1 0 01-1 1h-3a1 1 0 01-1-1v-3z"/></svg>',
  '任务管理': '<svg viewBox="0 0 20 20" fill="currentColor"><path d="M9 2a1 1 0 000 2h2a1 1 0 100-2H9z"/><path fill-rule="evenodd" d="M4 5a2 2 0 012-2 3 3 0 003 3h2a3 3 0 003-3 2 2 0 012 2v11a2 2 0 01-2 2H6a2 2 0 01-2-2V5zm3 4a1 1 0 000 2h.01a1 1 0 100-2H7zm3 0a1 1 0 000 2h3a1 1 0 100-2h-3zm-3 4a1 1 0 100 2h.01a1 1 0 100-2H7zm3 0a1 1 0 100 2h3a1 1 0 100-2h-3z" clip-rule="evenodd"/></svg>',
  '作业编排': '<svg viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M3 3a1 1 0 000 2v8a2 2 0 002 2h2.586l-1.293 1.293a1 1 0 101.414 1.414L10 15.414l2.293 2.293a1 1 0 001.414-1.414L12.414 15H15a2 2 0 002-2V5a1 1 0 100-2H3zm11 4a1 1 0 10-2 0v4a1 1 0 102 0V7zm-3 1a1 1 0 10-2 0v3a1 1 0 102 0V8zM8 9a1 1 0 00-2 0v2a1 1 0 102 0V9z" clip-rule="evenodd"/></svg>',
  '持续处理': '<svg viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 2a8 8 0 106.32 12.9 1 1 0 10-1.58-1.22A6 6 0 1116 10h-1.5a1 1 0 000 2H18a1 1 0 001-1V7.5a1 1 0 10-2 0V9a8 8 0 00-7-7zm1 4a1 1 0 10-2 0v4a1 1 0 00.45.83l3 2a1 1 0 101.1-1.66L11 9.46V6z" clip-rule="evenodd"/></svg>',
  '数据工作区': '<svg viewBox="0 0 20 20" fill="currentColor"><path d="M3 12v3c0 1.657 3.134 3 7 3s7-1.343 7-3v-3c0 1.657-3.134 3-7 3s-7-1.343-7-3z"/><path d="M3 7v3c0 1.657 3.134 3 7 3s7-1.343 7-3V7c0 1.657-3.134 3-7 3S3 8.657 3 7z"/><path d="M17 5c0 1.657-3.134 3-7 3S3 6.657 3 5s3.134-3 7-3 7 1.343 7 3z"/></svg>',
  'Flink 作业': '<svg viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M11.3 1.046A1 1 0 0112 2v5h4a1 1 0 01.82 1.573l-7 10A1 1 0 018 18v-5H4a1 1 0 01-.82-1.573l7-10a1 1 0 011.12-.38z" clip-rule="evenodd"/></svg>',
  '运行日志': '<svg viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4zm2 6a1 1 0 011-1h6a1 1 0 110 2H7a1 1 0 01-1-1zm1 3a1 1 0 100 2h6a1 1 0 100-2H7z" clip-rule="evenodd"/></svg>',
  '用户管理': '<svg viewBox="0 0 20 20" fill="currentColor"><path d="M9 6a3 3 0 11-6 0 3 3 0 016 0zm8 0a3 3 0 11-6 0 3 3 0 016 0zm-4.07 11c.046-.327.07-.66.07-1a6.97 6.97 0 00-1.5-4.33A5 5 0 0119 16v1h-6.07zM6 11a5 5 0 015 5v1H1v-1a5 5 0 015-5z"/></svg>'
}

// 计数动画
function useCountUp(targetRef, duration = 900) {
  const display = ref(0)
  let raf = null
  function animate(from, to) {
    const start = performance.now()
    function tick(now) {
      const p = Math.min((now - start) / duration, 1)
      const ease = 1 - Math.pow(1 - p, 3)
      display.value = Math.round(from + (to - from) * ease)
      if (p < 1) raf = requestAnimationFrame(tick)
    }
    if (raf) cancelAnimationFrame(raf)
    raf = requestAnimationFrame(tick)
  }
  watch(targetRef, (to, from) => animate(from ?? 0, to), { immediate: true })
  return display
}
const flinkParsed = computed(() => {
  const raw = flinkHealth.value?.flink
  try {
    if (!raw) return {}
    if (typeof raw === 'string') {
      const parsed = JSON.parse(raw)
      parsed.version = parsed.version || parsed['flink-version'] || parsed.flinkVersion || null
      return parsed
    }
    if (typeof raw === 'object') {
      raw.version = raw.version || raw['flink-version'] || raw.flinkVersion || null
      return raw
    }
    return {}
  } catch {
    return {}
  }
})
const flinkDashboardUrl = computed(() => flinkParsed.value?.jobManagerUrl || flinkHealth.value?.jobManagerUrl || 'http://127.0.0.1:8081')
const flinkLastRefreshText = computed(() => flinkHealth.value?.checkedAt ? formatDate(flinkHealth.value.checkedAt) : '未刷新')
const flinkSlotTotal = computed(() => Number(flinkParsed.value?.['slots-total'] ?? flinkParsed.value?.slotsTotal ?? 0))
const flinkSlotAvailable = computed(() => Number(flinkParsed.value?.['slots-available'] ?? flinkParsed.value?.slotsAvailable ?? 0))
const flinkSlotUsed = computed(() => Math.max(flinkSlotTotal.value - flinkSlotAvailable.value, 0))
const flinkJobRunning = computed(() => Number(flinkParsed.value?.['jobs-running'] ?? flinkParsed.value?.jobsRunning ?? 0))
const flinkJobFinished = computed(() => Number(flinkParsed.value?.['jobs-finished'] ?? flinkParsed.value?.jobsFinished ?? 0))
const flinkMetricCards = computed(() => [
  { label: '集群状态', value: flinkHealth.value?.available ? '可用' : '不可用', tone: flinkHealth.value?.available ? 'success' : 'danger', desc: 'JobManager 连通性' },
  { label: 'JobManager', value: flinkDashboardUrl.value.replace(/^https?:\/\//, ''), tone: 'info', desc: '远程控制台地址' },
  { label: 'Flink 版本', value: flinkParsed.value?.version || '-', tone: 'info', desc: '运行环境版本' },
  { label: 'TaskManager', value: flinkParsed.value?.taskmanagers ?? flinkParsed.value?.taskManagers ?? 0, tone: 'info', desc: '工作节点数量' },
  { label: '总 Slot', value: flinkSlotTotal.value || 0, tone: 'info', desc: '集群并行资源' },
  { label: '可用 Slot', value: flinkSlotAvailable.value || 0, tone: flinkSlotAvailable.value > 0 ? 'success' : 'warn', desc: '可提交作业资源' },
  { label: '运行作业', value: flinkJobRunning.value || 0, tone: flinkJobRunning.value > 0 ? 'running' : 'info', desc: '当前活跃任务' },
  { label: '最近刷新', value: flinkLastRefreshText.value, tone: 'info', desc: '页面状态更新时间' },
])
const flinkPipelineNodes = computed(() => [
  { title: '输入源', sub: 'Kafka、JDBC 或已导入文件', status: '支持多种输入', tone: 'info' },
  { title: 'Kafka', sub: '消息队列 Source / Sink', status: '支持 Kafka 接入', tone: 'info' },
  { title: 'Flink 实时计算', sub: '清洗、映射、过滤、去重', status: flinkHealth.value?.available ? '集群可用' : '集群不可用', tone: flinkHealth.value?.available ? 'running' : 'danger' },
  { title: '外部数据库', sub: 'JDBC 结果写入', status: '支持多种关系型数据库', tone: 'info' },
  { title: '运行记录', sub: '作业状态与任务日志', status: taskRuns.value.length ? '已有运行记录' : '运行后生成', tone: taskRuns.value.length ? 'success' : 'pending' },
])
const flinkCurrentJobRuntime = computed(() => {
  if (!flinkJob.value?.submittedAt) return '-'
  const start = new Date(flinkJob.value.submittedAt).getTime()
  const end = flinkJob.value.finishedAt ? new Date(flinkJob.value.finishedAt).getTime() : Date.now()
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '-'
  const seconds = Math.floor((end - start) / 1000)
  const min = Math.floor(seconds / 60)
  const sec = seconds % 60
  return min ? `${min}分${sec}秒` : `${sec}秒`
})
const runningCount = computed(() => tasks.value.filter((task) => task.status === 'RUNNING').length)
const publishedCount = computed(() => tasks.value.filter((task) => task.status === 'PUBLISHED').length)
const successCount = computed(() => tasks.value.filter((task) => task.status === 'SUCCEEDED').length)
const totalCount = useCountUp(computed(() => tasks.value.length))
const pubCount = useCountUp(publishedCount)
const runCount = useCountUp(runningCount)
const sucCount = useCountUp(successCount)
// 筛选条件变化时重置页码
watch([runFilterName, runFilterStatus, runFilterTrigger, runFilterAlertOnly, runFilterStartDate, runFilterEndDate, selectedRunTaskId], () => { runPage.value = 1 })

const filteredRuns = computed(() => {
  let runs = selectedRunTaskId.value
    ? taskRuns.value.filter((run) => run.taskId === selectedRunTaskId.value)
    : taskRuns.value
  if (runFilterName.value.trim()) {
    const keyword = runFilterName.value.trim().toLowerCase()
    runs = runs.filter((run) => (run.taskName || '').toLowerCase().includes(keyword))
  }
  if (runFilterStatus.value) {
    runs = runs.filter((run) => run.status === runFilterStatus.value)
  }
  if (runFilterTrigger.value) {
    runs = runs.filter((run) => run.triggerType === runFilterTrigger.value)
  }
  if (runFilterAlertOnly.value) {
    runs = runs.filter((run) => run.status === 'FAILED' || run.maxRetriesReached || run.errorMessage)
  }
  if (runFilterStartDate.value) {
    const start = new Date(runFilterStartDate.value)
    runs = runs.filter((run) => run.startedAt && new Date(run.startedAt) >= start)
  }
  if (runFilterEndDate.value) {
    const end = new Date(runFilterEndDate.value)
    end.setDate(end.getDate() + 1)
    runs = runs.filter((run) => run.startedAt && new Date(run.startedAt) < end)
  }
  return runs
})

const runTotalPages = computed(() => Math.max(1, Math.ceil(filteredRuns.value.length / runPageSize)))
const pagedRuns = computed(() => {
  const start = (runPage.value - 1) * runPageSize
  return filteredRuns.value.slice(start, start + runPageSize)
})
const fileTotalPages = computed(() => Math.max(1, Math.ceil(files.value.length / filePageSize)))
const previewRecords = computed(() => {
  const content = preview.value?.content
  return Array.isArray(content) ? content : content == null ? [] : [content]
})
const filteredFiles = computed(() => {
  if (!fileSearchKeyword.value.trim()) return files.value
  const kw = fileSearchKeyword.value.trim().toLowerCase()
  return files.value.filter(f =>
    (f.originalName || '').toLowerCase().includes(kw)
    || (f.uploaderUsername || '').toLowerCase().includes(kw)
  )
})
const totalFileRecords = computed(() => files.value.reduce((sum, file) => sum + Number(file.recordCount || 0), 0))
const fileTypeCount = computed(() => new Set(files.value.map((file) => file.fileType).filter(Boolean)).size)

const pagedFiles = computed(() => {
  const start = (filePage.value - 1) * filePageSize
  return filteredFiles.value.slice(start, start + filePageSize)
})
const selectedRunTask = computed(() => tasks.value.find((task) => task.id === selectedRunTaskId.value))
const selectedRunDetailTask = computed(() => tasks.value.find((task) => task.id === selectedRunDetail.value?.taskId))
const latestRun = computed(() => taskRuns.value[0])
const selectedSourceFile = computed(() => files.value.find((file) => file.id === Number(selectedSourceFileId.value)))
const managementTasks = computed(() => tasks.value.filter((task) => MANAGED_STATUSES.includes(task.status)))
const workflowTasks = computed(() => {
  if (role.value === 'SADMIN' || role.value === 'ADMIN' || role.value === 'OPS_READONLY') return tasks.value
  if (role.value === 'USER') return tasks.value.filter((task) => isTaskOwner(task))
  return []
})
const continuousTasks = computed(() => tasks.value.filter((task) => task.runMode === 'CONTINUOUS'))
const continuousDueTasks = computed(() => {
  const now = Date.now()
  return continuousTasks.value.filter((task) => task.status === 'PUBLISHED' && (!task.nextRunAt || new Date(task.nextRunAt).getTime() <= now))
})
const continuousRunningTasks = computed(() => continuousTasks.value.filter((task) => task.continuousRunning))
const continuousRetryTasks = computed(() => continuousTasks.value.filter((task) => Number(task.retryCount || 0) > 0))
const continuousPausedTasks = computed(() => continuousTasks.value.filter((task) => task.status === 'STOPPED'))
const continuousAlertTasks = computed(() => continuousTasks.value.filter((task) => task.lastError || Number(task.retryCount || 0) >= Number(task.maxRetryCount || 3)))
const continuousAutoRuns = computed(() => taskRuns.value.filter((run) => run.triggerType === 'CONTINUOUS').slice(0, 8))
const continuousCards = computed(() => [
  { label: '持续任务', value: continuousTasks.value.length, hint: '已配置自动扫描' },
  { label: '到点待执行', value: continuousDueTasks.value.length, hint: '等待扫描器触发' },
  { label: '执行中', value: continuousRunningTasks.value.length, hint: '防重复锁保护中' },
  { label: '已暂停', value: continuousPausedTasks.value.length, hint: '不会被扫描器触发' },
  { label: '失败重试', value: continuousRetryTasks.value.length, hint: '需要重点观察' },
  { label: '异常告警', value: continuousAlertTasks.value.length, hint: '失败或达到重试上限' },
])
const collaborationTasks = computed(() => {
  let items = managementTasks.value
  if (collaborationStatusFilter.value) {
    items = items.filter((task) => task.status === collaborationStatusFilter.value)
  }
  if (collaborationOnlyMine.value) {
    items = items.filter((task) => isTaskOwner(task))
  }
  if (collaborationOnlyAbnormal.value) {
    items = items.filter((task) => ['due-soon', 'overdue', 'failed'].includes(taskRiskLevel(task)))
  }
  if (collaborationFocusUsername.value) {
    const username = collaborationFocusUsername.value.toLowerCase()
    items = items.filter((task) => {
      const owner = task.creatorUsername || task.creatorName || task.ownerUsername || task.ownerName
      const links = collaborationLinks.value[String(task.id)] || []
      return String(owner || '').toLowerCase() === username
        || links.some((link) => String(link.username || '').toLowerCase() === username)
    })
  }
  return items
})
const collaborationPeople = computed(() => {
  const map = new Map()
  const addPerson = (person) => {
    const username = String(person?.username || person?.creatorUsername || '').trim()
    if (!username) return
    const key = username.toLowerCase()
    if (!map.has(key)) {
      map.set(key, {
        id: person.id ?? person.userId ?? username,
        username,
        displayName: person.displayName || username,
        role: person.role || 'USER',
        taskIds: new Set(),
      })
    }
    const saved = map.get(key)
    if (person.role && saved.role === 'USER') saved.role = person.role
    if (person.taskId != null) saved.taskIds.add(person.taskId)
  }
  if (currentUser.value) addPerson(currentUser.value)
  managementTasks.value.forEach((task) => {
    addPerson({
      id: task.creatorId,
      username: task.creatorUsername || task.creatorName || task.ownerUsername || task.ownerName,
      role: task.creatorRole || 'USER',
      taskId: task.id,
    })
    const links = collaborationLinks.value[String(task.id)] || []
    links.forEach((link) => addPerson({ ...link, taskId: task.id }))
  })
  let people = Array.from(map.values()).map((person) => ({
    ...person,
    taskIds: Array.from(person.taskIds),
  }))
  if (collaborationRoleFilter.value) {
    people = people.filter((person) => person.role === collaborationRoleFilter.value)
  }
  return people
})
const collaborationAllUserCandidates = computed(() => {
  const map = new Map()
  const addCandidate = (user) => {
    const username = String(user?.username || user?.creatorUsername || user?.name || '').trim()
    if (!username) return
    const key = username.toLowerCase()
    if (!map.has(key)) {
      map.set(key, {
        id: user.id ?? user.userId ?? username,
        username,
        displayName: user.displayName || user.nickname || user.nickName || username,
        role: user.role || 'USER',
      })
    } else {
      const saved = map.get(key)
      saved.displayName = saved.displayName === saved.username
        ? (user.displayName || user.nickname || user.nickName || saved.displayName)
        : saved.displayName
      if (user.role && saved.role === 'USER') saved.role = user.role
    }
  }
  if (currentUser.value) addCandidate(currentUser.value)
  collaborationKnownUsers.value.forEach(addCandidate)
  managementTasks.value.forEach((task) => {
    addCandidate({
      id: task.creatorId,
      username: task.creatorUsername || task.creatorName || task.ownerUsername || task.ownerName,
      displayName: task.creatorDisplayName || task.creatorNickname,
      role: task.creatorRole || 'USER',
    })
    ;(collaborationLinks.value[String(task.id)] || []).forEach(addCandidate)
  })
  return Array.from(map.values())
})
const collaborationUserCandidates = computed(() => {
  const keyword = collaborationNewMember.value.trim().toLowerCase()
  const roleFilter = collaborationNewRole.value
  const currentTask = selectedCollaborationNode.value?.type === 'task' ? selectedCollaborationNode.value.task : null
  const ownerName = String(currentTask?.creatorUsername || currentTask?.creatorName || '').toLowerCase()
  const existingNames = new Set((collaborationLinks.value[String(currentTask?.id)] || [])
    .map((link) => String(link.username || '').toLowerCase()))
  return collaborationAllUserCandidates.value
    .filter((user) => !roleFilter || user.role === roleFilter)
    .filter((user) => String(user.username || '').toLowerCase() !== ownerName)
    .filter((user) => !existingNames.has(String(user.username).toLowerCase()))
    .filter((user) => {
      if (!keyword) return true
      return String(user.username || '').toLowerCase().includes(keyword)
        || String(user.displayName || '').toLowerCase().includes(keyword)
        || String(roleText[user.role] || user.role || '').toLowerCase().includes(keyword)
    })
    .slice(0, 8)
})
const collaborationNodes = computed(() => {
  const taskNodes = collaborationTasks.value.map((task, index) => {
    const saved = collaborationPositions.value[`task-${task.id}`]
    const column = index % 4
    const row = Math.floor(index / 4)
    const fallback = { x: 78 + column * 138, y: 70 + row * 104 }
    return { id: `task-${task.id}`, type: 'task', task, ...fallback, ...saved }
  })
  const people = collaborationPeople.value
  const personNodes = people.map((person, index) => {
    const saved = collaborationPositions.value[`person-${person.username}`]
    const column = index % 3
    const row = Math.floor(index / 3)
    const fallback = {
      x: 650 + column * 112,
      y: 76 + row * 108,
    }
    return { id: `person-${person.username}`, type: 'person', person, ...fallback, ...saved }
  })
  return [...taskNodes, ...personNodes]
})
const collaborationNodeMap = computed(() => {
  const map = new Map()
  collaborationNodes.value.forEach((node) => map.set(node.id, node))
  return map
})
const collaborationEdges = computed(() => {
  const edges = []
  collaborationTasks.value.forEach((task) => {
    const taskNodeId = `task-${task.id}`
    const owner = task.creatorUsername || task.creatorName || task.ownerUsername || task.ownerName
    if (owner) {
      edges.push({ id: `${task.id}-owner-${owner}`, from: taskNodeId, to: `person-${owner}`, relation: 'OWNER' })
    }
    const links = collaborationLinks.value[String(task.id)] || []
    links.forEach((link, index) => {
      if (link.username) {
        edges.push({ id: `${task.id}-${link.relationType}-${link.username}-${index}`, from: taskNodeId, to: `person-${link.username}`, relation: link.relationType || 'COLLABORATOR' })
      }
    })
  })
  return edges.filter((edge) => collaborationNodeMap.value.has(edge.from) && collaborationNodeMap.value.has(edge.to))
})
const selectedCollaborationNode = computed(() => collaborationNodes.value.find((node) => node.id === collaborationSelectedId.value))
const canAssignCollaborator = computed(() => role.value === 'SADMIN' || role.value === 'ADMIN' || role.value === 'USER')
const selectedPersonOwnerTasks = computed(() => {
  const node = selectedCollaborationNode.value
  if (!node || node.type !== 'person') return []
  return collaborationTasks.value.filter((task) => {
    const owner = task.creatorUsername || task.creatorName || task.ownerUsername || task.ownerName
    return owner && String(owner).toLowerCase() === String(node.person.username).toLowerCase()
  })
})
const selectedPersonLinkedTasks = computed(() => {
  const node = selectedCollaborationNode.value
  if (!node || node.type !== 'person') return []
  return collaborationTasks.value.filter((task) => {
    const links = collaborationLinks.value[String(task.id)] || []
    return links.some((link) => String(link.username).toLowerCase() === String(node.person.username).toLowerCase())
  })
})
const activeCollaborationMenuNode = computed(() => (
  collaborationContextMenu.value.visible
    ? collaborationNodeMap.value.get(collaborationContextMenu.value.nodeId)
    : null
))
const activeCollaborationMenuActions = computed(() => (
  activeCollaborationMenuNode.value ? collaborationActionsForNode(activeCollaborationMenuNode.value) : []
))
const selectedOverviewModule = ref('')
const overviewModules = computed(() => {
  const runSuccess = taskRuns.value.filter((run) => ['SUCCESS', 'SUCCEEDED'].includes(run.status)).length
  const runFailed = taskRuns.value.filter((run) => run.status === 'FAILED').length
  const defs = [
    {
      page: '任务管理',
      label: '任务管理',
      summary: `${managementTasks.value.length} 个任务`,
      desc: '查看任务状态、发布链路、启动或停止执行。',
      details: [
        { label: '总任务', value: managementTasks.value.length },
        { label: '已发布', value: publishedCount.value },
        { label: '运行中', value: runningCount.value },
      ],
    },
    {
      page: '作业编排',
      label: '作业编排',
      summary: `${workflowTasks.value.length} 个画布`,
      desc: '通过 AI 或手动拖拽生成数据流节点与连线。',
      details: [
        { label: '可编排', value: workflowTasks.value.length },
        { label: 'AI 生成', value: '就绪' },
        { label: '画布保存', value: canWrite.value ? '可用' : '只读' },
      ],
    },
    {
      page: '持续处理',
      label: '持续处理',
      summary: `${continuousTasks.value.length} 个持续任务`,
      desc: '监控已发布持续任务的定时扫描、立即执行、暂停恢复和失败重试。',
      details: [
        { label: '持续任务', value: continuousTasks.value.length },
        { label: '自动记录', value: continuousAutoRuns.value.length },
        { label: '调度状态', value: continuousTasks.value.length ? '已接入' : '待配置' },
      ],
    },
    {
      page: '数据工作区',
      label: '数据工作区',
      summary: `${files.value.length} 个文件`,
      desc: '统一管理 JSON、CSV、Excel、XML 数据源与解析预览。',
      details: [
        { label: '文件', value: files.value.length },
        { label: '记录', value: totalFileRecords.value },
        { label: '类型', value: fileTypeCount.value },
      ],
    },
    {
      page: 'Flink 作业',
      label: 'Flink 作业',
      summary: flinkHealth.value?.available ? '集群在线' : '等待连接',
      desc: '跟踪实时计算集群、提交状态与作业健康度。',
      details: [
        { label: '集群', value: flinkHealth.value?.available ? '在线' : '离线' },
        { label: '版本', value: flinkParsed.value?.version || '-' },
        { label: '记录', value: flinkJobHistory.value.length },
      ],
    },
    {
      page: '运行日志',
      label: '运行日志',
      summary: `${taskRuns.value.length} 条记录`,
      desc: '追踪任务执行流水、耗时、成功和失败记录。',
      details: [
        { label: '日志', value: taskRuns.value.length },
        { label: '成功', value: runSuccess },
        { label: '失败', value: runFailed },
      ],
    },
    {
      page: '用户管理',
      label: '用户管理',
      summary: roleText[role.value] || '权限视图',
      desc: '管理账号角色和平台功能权限。',
      details: [
        { label: '当前角色', value: roleText[role.value] || '-' },
        { label: '菜单数', value: pages.value.length },
        { label: '权限', value: canWrite.value ? '可编辑' : '只读' },
      ],
    },
  ]
  return defs.filter((item) => pages.value.includes(item.page))
})
const selectedOverview = computed(() => overviewModules.value.find((item) => item.page === selectedOverviewModule.value) || null)
watch(overviewModules, (items) => {
  if (!items.length || !items.some((item) => item.page === selectedOverviewModule.value)) {
    selectedOverviewModule.value = ''
  }
}, { immediate: true })

function currentUserId() { return currentUser.value?.id ?? currentUser.value?.userId ?? currentUser.value?.uid ?? null }
function isTaskOwner(task) {
  const userId = currentUserId()
  const username = currentUser.value?.username
  const ownerName = task.creatorUsername || task.creatorName || task.ownerUsername || task.ownerName
  if (task.creatorId != null && userId != null) return String(task.creatorId) === String(userId)
  if (ownerName && username) return String(ownerName) === String(username)
  return false
}
function canOperateTask(task) { if (role.value === 'SADMIN' || role.value === 'ADMIN') return true; if (role.value === 'OPS_READONLY') return false; if (role.value === 'USER') return isTaskOwner(task); return false }
function ensureTaskOperation(task, action = '操作') { if (!canOperateTask(task)) { deny(`当前账号无权${action}该任务。`); return false } return true }
function taskRiskLevel(task) {
  if (!task) return 'normal'
  if (task.status === 'RUNNING') return 'running'
  if (task.status === 'FAILED') return 'failed'
  if (task.status === 'SUCCEEDED') return 'success'
  const deadline = task.deadline || task.endTime || task.finishTime
  if (!deadline) return 'normal'
  const deadlineTime = new Date(deadline).getTime()
  if (Number.isNaN(deadlineTime)) return 'normal'
  const diff = deadlineTime - Date.now()
  if (diff < 0) return 'overdue'
  if (diff <= 3 * 24 * 60 * 60 * 1000) return 'due-soon'
  return 'normal'
}
function taskRiskText(task) {
  const map = {
    normal: '正常',
    'due-soon': '即将截止',
    overdue: '已逾期',
    failed: '运行失败',
    success: '成功',
    running: '运行中',
  }
  return map[taskRiskLevel(task)] || '正常'
}
function personLoadLevel(person) {
  const count = Number(person?.taskIds?.length || 0)
  if (count >= 5) return 'high'
  if (count >= 3) return 'medium'
  return 'low'
}
function personLoadText(person) {
  const map = { low: '轻负载', medium: '中负载', high: '高负载' }
  return map[personLoadLevel(person)] || '轻负载'
}
function collaborationCanAssign(task) {
  return canAssignCollaborator.value && canOperateTask(task)
}
function canStartTaskFromCollaboration(task) {
  return canExecute.value && canOperateTask(task) && ['PUBLISHED', 'STOPPED', 'FAILED', 'SUCCEEDED'].includes(task.status)
}
function canStopTaskFromCollaboration(task) {
  return canExecute.value && canOperateTask(task) && task.status === 'RUNNING'
}
function canWithdrawTaskFromCollaboration(task) {
  return canWrite.value && canOperateTask(task) && ['PUBLISHED', 'STOPPED', 'FAILED', 'SUCCEEDED'].includes(task.status) && task.status !== 'RUNNING'
}
function canDeleteTaskFromCollaboration(task) {
  return canWrite.value && canOperateTask(task) && task.status !== 'RUNNING'
}
function collaborationActionsForNode(node) {
  if (!node) return []
  if (node.type === 'task') {
    const task = node.task
    const actions = [
      { key: 'view', label: '查看编排', kind: 'view', handler: () => viewEditor(task) },
      { key: 'runs', label: '运行记录', kind: 'view', handler: () => showRuns(task) },
    ]
    if (canStartTaskFromCollaboration(task)) actions.push({ key: 'start', label: '启动任务', kind: 'primary', handler: () => requestStartTask(task) })
    if (canStopTaskFromCollaboration(task)) actions.push({ key: 'stop', label: '停止任务', kind: 'warning', handler: () => stopTask(task) })
    if (canWithdrawTaskFromCollaboration(task)) actions.push({ key: 'withdraw', label: '撤回编辑', kind: 'warning', handler: () => withdrawTask(task) })
    if (canDeleteTaskFromCollaboration(task)) actions.push({ key: 'delete', label: '删除任务', kind: 'danger', handler: () => deleteTask(task) })
    return actions
  }
  const username = node.person.username
  const isCurrent = username === currentUser.value?.username
  const actions = [
    { key: 'focus', label: '查看参与任务', kind: 'view', handler: () => focusCollaborationPerson(username) },
    { key: 'layout-person', label: '按该人员聚合', kind: 'view', handler: () => focusCollaborationPerson(username, true) },
  ]
  if (isCurrent && canWrite.value) {
    actions.unshift({ key: 'create', label: '新建任务', kind: 'primary', handler: () => openCreateFromCollaboration() })
    actions.splice(1, 0, { key: 'mine', label: '只看我的任务', kind: 'view', handler: () => { collaborationOnlyMine.value = true; collaborationFocusUsername.value = '' } })
  }
  return actions
}
function openCollaborationContextMenu(event, node) {
  event.preventDefault()
  event.stopPropagation()
  selectCollaborationNode(node)
  collaborationContextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    nodeId: node.id,
  }
}
function closeCollaborationContextMenu() {
  collaborationContextMenu.value = { visible: false, x: 0, y: 0, nodeId: '' }
}
function runCollaborationMenuAction(action) {
  closeCollaborationContextMenu()
  action?.handler?.()
}
function openCreateFromCollaboration() {
  openCreateWorkflow()
}

function openCreateWorkflow() {
  if (!canWrite.value) return deny('当前账号不能新建任务。')
  clearMessage()
  form.value = blankTaskForm()
  editingTaskId.value = null
  editingTaskReadonly.value = false
  activePage.value = '作业编排'
  showCreate.value = true
}
function focusCollaborationPerson(username, relayout = false) {
  collaborationFocusUsername.value = username || ''
  collaborationOnlyMine.value = false
  if (relayout) autoLayoutCollaboration('owner')
}
function clearCollaborationFocus() {
  collaborationFocusUsername.value = ''
}
function collaborationNodeStyle(node) {
  return {
    transform: `translate(${Math.round(node.x)}px, ${Math.round(node.y)}px)`,
  }
}
function collaborationEdgeStyle(edge) {
  const from = collaborationNodeMap.value.get(edge.from)
  const to = collaborationNodeMap.value.get(edge.to)
  if (!from || !to) return { display: 'none' }
  const fromSize = from.type === 'task' ? 58 : 52
  const toSize = to.type === 'task' ? 58 : 52
  const x1 = from.x + fromSize / 2
  const y1 = from.y + fromSize / 2
  const x2 = to.x + toSize / 2
  const y2 = to.y + toSize / 2
  const dx = x2 - x1
  const dy = y2 - y1
  const length = Math.max(20, Math.sqrt(dx * dx + dy * dy))
  const angle = Math.atan2(dy, dx) * 180 / Math.PI
  return {
    width: `${length}px`,
    transform: `translate(${x1}px, ${y1}px) rotate(${angle}deg)`,
  }
}
function selectCollaborationNode(node) {
  collaborationSelectedId.value = node.id
  if (node.type === 'task') {
    collaborationNewMember.value = ''
    collaborationNewRelation.value = 'COLLABORATOR'
    loadCollaborationUsers()
  }
}
async function loadCollaborationUsers() {
  if (!canAssignCollaborator.value) return
  try {
    const users = await collaborationApi.candidates()
    collaborationKnownUsers.value = Array.isArray(users) ? users : []
  } catch {
    collaborationKnownUsers.value = []
  }
}
function selectCollaborationCandidate(user) {
  collaborationNewMember.value = user.username
  if (user.role) collaborationNewRole.value = user.role
  collaborationMemberFocused.value = false
}
function closeCollaborationUserDropdownSoon() {
  window.setTimeout(() => {
    collaborationMemberFocused.value = false
  }, 120)
}
function startCollaborationDrag(event, node) {
  if (event.button !== 0) return
  collaborationDrag.value = {
    id: node.id,
    startX: event.clientX,
    startY: event.clientY,
    originX: node.x,
    originY: node.y,
  }
  selectCollaborationNode(node)
  window.addEventListener('mousemove', moveCollaborationNode)
  window.addEventListener('mouseup', stopCollaborationDrag)
}
function moveCollaborationNode(event) {
  const drag = collaborationDrag.value
  if (!drag) return
  const next = {
    ...collaborationPositions.value,
    [drag.id]: {
      x: Math.max(16, Math.min(1040, drag.originX + event.clientX - drag.startX)),
      y: Math.max(16, Math.min(590, drag.originY + event.clientY - drag.startY)),
    },
  }
  collaborationPositions.value = next
}
function stopCollaborationDrag() {
  if (collaborationDrag.value) {
    writeLocalJson('a22_collaboration_positions', collaborationPositions.value)
  }
  collaborationDrag.value = null
  window.removeEventListener('mousemove', moveCollaborationNode)
  window.removeEventListener('mouseup', stopCollaborationDrag)
}
function resetCollaborationLayout() {
  collaborationPositions.value = {}
  writeLocalJson('a22_collaboration_positions', {})
}
function autoLayoutCollaboration(mode = collaborationLayoutMode.value) {
  collaborationLayoutMode.value = mode
  const next = {}
  if (mode === 'status') {
    const statusOrder = ['PUBLISHED', 'RUNNING', 'STOPPED', 'SUCCEEDED', 'FAILED']
    collaborationTasks.value.forEach((task, index) => {
      const statusIndex = Math.max(0, statusOrder.indexOf(task.status))
      const sameStatusIndex = collaborationTasks.value
        .filter((item) => item.status === task.status)
        .findIndex((item) => item.id === task.id)
      next[`task-${task.id}`] = { x: 58 + statusIndex * 118, y: 68 + sameStatusIndex * 92 }
    })
  } else {
    const owners = [...new Set(collaborationTasks.value.map((task) => task.creatorUsername || task.creatorName || '未记录'))]
    collaborationTasks.value.forEach((task) => {
      const owner = task.creatorUsername || task.creatorName || '未记录'
      const ownerIndex = Math.max(0, owners.indexOf(owner))
      const sameOwnerIndex = collaborationTasks.value
        .filter((item) => (item.creatorUsername || item.creatorName || '未记录') === owner)
        .findIndex((item) => item.id === task.id)
      next[`task-${task.id}`] = { x: 68 + ownerIndex * 142, y: 70 + sameOwnerIndex * 94 }
    })
  }
  collaborationPeople.value.forEach((person, index) => {
    const column = index % 4
    const row = Math.floor(index / 4)
    next[`person-${person.username}`] = { x: 650 + column * 104, y: 76 + row * 100 }
  })
  collaborationPositions.value = next
  writeLocalJson('a22_collaboration_positions', next)
}
function fitCollaborationView() {
  autoLayoutCollaboration(collaborationLayoutMode.value)
}
async function assignCollaborator(task) {
  if (!task) return
  if (!collaborationCanAssign(task)) return deny('当前账号无权为该任务分配协作者。')
  const username = collaborationNewMember.value.trim()
  if (!username) return deny('请输入协作者用户名。')
  const taskId = String(task.id)
  const currentLinks = collaborationLinks.value[taskId] || []
  if (currentLinks.some((link) => String(link.username).toLowerCase() === username.toLowerCase())) {
    return deny('该协作者已经在任务关系中。')
  }
  clearMessage()
  try {
    await collaborationApi.upsert({
      taskId: task.id,
      username,
      relationType: collaborationNewRelation.value,
    })
    await loadCollaborations()
    collaborationNewMember.value = ''
    collaborationMemberFocused.value = false
    notice.value = `已为任务“${task.name}”添加协作者 ${username}。`
  } catch (e) {
    showError(e, '添加协作者失败')
  }
}
async function removeCollaborator(task, member) {
  if (!task) return
  if (!collaborationCanAssign(task)) return deny('当前账号无权调整该任务协作者。')
  if (!member?.userId) return deny('协作者信息不完整，请刷新后重试。')
  clearMessage()
  try {
    await collaborationApi.remove(task.id, member.userId)
    await loadCollaborations()
    notice.value = `已从任务“${task.name}”移除协作者 ${member.username}。`
  } catch (e) {
    showError(e, '移除协作者失败')
  }
}
function toIsoOrNull(value) { return value ? new Date(value).toISOString() : null }
function toDateTimeLocal(value) { if (!value) return ''; const date = new Date(value); if (Number.isNaN(date.getTime())) return ''; const pad = (number) => String(number).padStart(2, '0'); return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}` }
function taskPayload(source) {
  return {
    name: source.name,
    sourceType: source.sourceType,
    sinkType: source.sinkType,
    transforms: source.transforms.filter(Boolean),
    startTime: toIsoOrNull(source.startTime),
    deadline: toIsoOrNull(source.deadline),
    runMode: source.runMode || 'ONCE',
    scheduleType: source.scheduleType || 'MANUAL',
    scheduleExpression: source.scheduleExpression || '',
    continuousRemark: source.continuousRemark || '',
    incrementalMode: source.incrementalMode || 'NONE',
    cursorField: source.cursorField || '',
    lastCursorValue: source.lastCursorValue || '',
    processedField: source.processedField || '',
    unprocessedValue: source.unprocessedValue || 'UNPROCESSED',
    processedValue: source.processedValue || 'DONE',
    dedupKeys: source.dedupKeys || ''
  }
}

function extractRawMessage(input) {
  if (!input) return ''
  return typeof input === 'string' ? input : input.message || String(input)
}

function simplifyError(input, prefix = '操作失败') {
  const raw = extractRawMessage(input)
  const lower = raw.toLowerCase()
  let summary = raw || prefix
  if (lower.includes('communications link failure') || lower.includes('connection refused') || lower.includes('connect timed out')) {
    summary = '数据库连接失败，请检查数据库地址、账号密码、SSH 隧道或 Flink 集群到数据库的网络连通性。'
  } else if (lower.includes('unsupportedclassversionerror') || lower.includes('class file version')) {
    summary = 'Flink 作业 JAR 的 Java 版本与集群运行环境不一致，请用兼容版本重新编译。'
  } else if (lower.includes('jar file') && (lower.includes('does not exist') || lower.includes('不存在'))) {
    summary = 'Flink 作业 JAR 不存在，请先编译 flink-jobs 模块并检查后端 JAR 路径配置。'
  } else if (lower.includes('could not execute application') || lower.includes('programinvocationexception')) {
    summary = 'Flink 作业启动失败，请检查 JAR、入口类、数据库连接和 Kafka/Flink 集群配置。'
  } else if (lower.includes('failed to fetch') || lower.includes('network') || lower.includes('网络不可达')) {
    summary = '后端服务或网络不可用，请确认后端已启动并且地址配置正确。'
  } else if (raw.length > 180 || raw.includes('\\n') || raw.includes('\n') || raw.includes('Exception') || raw.includes('at ')) {
    summary = `${prefix}，系统已记录技术详情，请展开查看。`
  }
  return { summary: `${prefix}：${summary}`.replace(`${prefix}：${prefix}`, prefix), detail: raw }
}

function buildRunRuleAnalysis(run) {
  const rawText = [
    run?.status,
    run?.taskName,
    run?.errorMessage,
    ...runLogs(run),
  ].filter(Boolean).join('\n')
  const lower = rawText.toLowerCase()
  const status = String(run?.status || '').toUpperCase()
  const recordsRead = Number(run?.recordsRead ?? 0)
  const recordsWritten = Number(run?.recordsWritten ?? 0)
  const result = {
    summary: '暂未识别到明确异常，建议按通用链路顺序排查。',
    causes: ['当前运行记录缺少可直接定位的错误特征，可能需要结合后端日志、Flink Dashboard 和输入文件继续判断。'],
    suggestions: [
      '确认任务已经发布，画布包含输入、处理、输出和完整连线。',
      '检查后端服务、数据库连接、Flink 集群、Kafka Topic 和输入文件是否可用。',
      '如果重复失败，请复制技术详情给负责后端/Flink 的同学进一步定位。',
    ],
    riskLevel: '中',
    riskClass: 'warn',
    needHuman: '建议人工复核',
    matchedRule: '通用排查',
  }
  const setResult = (summary, causes, suggestions, riskLevel = '中', riskClass = 'warn', needHuman = '建议人工处理', matchedRule = '规则命中') => {
    result.summary = summary
    result.causes = causes
    result.suggestions = suggestions
    result.riskLevel = riskLevel
    result.riskClass = riskClass
    result.needHuman = needHuman
    result.matchedRule = matchedRule
  }

  if (lower.includes('communications link failure') || lower.includes('connection refused') || lower.includes('connect timed out') || lower.includes('unable to obtain jdbc connection')) {
    setResult(
      '任务失败点集中在数据库连接链路。',
      ['数据库地址、端口、账号密码、SSH 隧道或 Flink 集群到数据库的网络链路不可用。', '如果 Flink 作业运行在远程集群，不能直接使用本机 127.0.0.1 隧道地址。'],
      ['先确认后端使用的数据库连接和 Flink 作业写入数据库连接是否分开配置。', '用 Test-NetConnection 或数据库客户端验证端口可达，再确认用户名、密码和库名。', '若是 Flink 集群写库失败，应使用集群侧可访问的数据库地址。'],
      '高',
      'danger',
      '需要人工处理',
      '数据库连接失败'
    )
  } else if (lower.includes('jar file') && (lower.includes('does not exist') || lower.includes('不存在'))) {
    setResult(
      'Flink 作业 JAR 文件缺失，任务无法提交。',
      ['后端配置的 flink-jobs JAR 路径不存在，或当前项目移动后仍指向旧目录。'],
      ['在 flink-jobs 模块执行 Maven 打包。', '检查 application.yml 或启动参数中的 JAR 路径是否指向当前项目 target 目录。', '重新启动后端后再次执行任务。'],
      '高',
      'danger',
      '需要人工处理',
      'Flink JAR 不存在'
    )
  } else if (lower.includes('unsupportedclassversionerror') || lower.includes('class file version')) {
    setResult(
      'Flink 作业 Java 版本与集群运行环境不兼容。',
      ['JAR 使用了更高版本 JDK 编译，而 Flink 集群运行的 Java 版本较低。'],
      ['确认 Flink 集群 Java 版本。', '使用兼容的 JDK 重新编译 flink-jobs，例如统一到 Java 8/11/17 中团队约定版本。', '重新提交前清理旧 JAR，避免上传缓存产物。'],
      '高',
      'danger',
      '需要人工处理',
      'Java 版本不兼容'
    )
  } else if (lower.includes('kafka') || lower.includes('broker') || lower.includes('topic')) {
    setResult(
      '任务疑似卡在 Kafka 消息链路。',
      ['Kafka Broker、Topic、消费组或网络配置可能不可用。'],
      ['检查 Kafka 地址和 Topic 是否存在。', '确认 Flink 集群能访问 Kafka Broker。', '如果当前只是演示模式，建议在页面中明确标注 Kafka 待接入。'],
      '中',
      'warn',
      '建议人工复核',
      'Kafka 链路异常'
    )
  } else if (lower.includes('flink') || lower.includes('jobmanager') || lower.includes('programinvocationexception') || lower.includes('could not execute application')) {
    setResult(
      'Flink 作业提交或运行阶段失败。',
      ['Flink 集群、JobManager、入口类、JAR 参数或运行环境可能存在问题。'],
      ['打开 Flink Dashboard 查看失败 Job 的异常摘要。', '确认入口类、并行度、JAR 路径和提交参数是否正确。', '优先排查最近一次改动的 flink-jobs 模块和后端提交参数。'],
      '高',
      'danger',
      '需要人工处理',
      'Flink 作业异常'
    )
  } else if (lower.includes('403') || lower.includes('forbidden') || lower.includes('无权') || lower.includes('权限')) {
    setResult(
      '任务失败与账号权限有关。',
      ['当前登录用户可能不是任务创建者，也不是管理员，或 Token/角色信息已过期。'],
      ['刷新登录状态后重试。', '确认任务 creatorUsername 与当前用户一致。', '普通用户只能操作自己的任务，管理员才能操作全部任务。'],
      '中',
      'warn',
      '建议人工复核',
      '权限不足'
    )
  } else if (lower.includes('未发布') || lower.includes('not published') || lower.includes('draft') || lower.includes('草稿')) {
    setResult(
      '任务尚未进入可执行状态。',
      ['任务仍处于草稿或未发布状态，后端拒绝执行。'],
      ['回到作业编排页检查画布。', '保存画布并发布任务后，再到任务管理页启动。'],
      '低',
      'info',
      '通常无需人工介入',
      '任务未发布'
    )
  } else if (lower.includes('缺少输入') || lower.includes('缺少输出') || lower.includes('画布') || lower.includes('graph') || lower.includes('nodes') || lower.includes('edges')) {
    setResult(
      '任务画布配置不完整。',
      ['画布可能缺少输入节点、输出节点、连线，或存在断开的节点。'],
      ['打开作业编排页使用“生成演示流程”或“重建连线”。', '确认流程满足“开始 → 输入 → 处理 → 输出 → 结束”。', '保存并重新发布后再执行。'],
      '中',
      'warn',
      '建议人工复核',
      '画布配置异常'
    )
  } else if (['SUCCEEDED', 'SUCCESS'].includes(status)) {
    setResult(
      recordsRead === 0 && recordsWritten === 0 ? '任务执行成功，但没有产生有效读写数据。' : '任务执行成功，当前没有发现阻断性风险。',
      recordsRead === 0 && recordsWritten === 0 ? ['输入文件可能为空、过滤条件过严，或演示链路没有真实数据流入。'] : ['任务已完成，读写数量可作为演示结果依据。'],
      recordsRead === 0 && recordsWritten === 0 ? ['检查输入文件记录数。', '检查过滤、去重、字段映射规则是否过严。'] : ['可以在结果表中抽查数据。', '建议保留该运行记录作为演示样例。'],
      recordsRead === 0 && recordsWritten === 0 ? '中' : '低',
      recordsRead === 0 && recordsWritten === 0 ? 'warn' : 'success',
      recordsRead === 0 && recordsWritten === 0 ? '建议人工复核' : '通常无需人工介入',
      '执行结果分析'
    )
  } else if (status === 'RUNNING') {
    setResult(
      '任务正在运行，建议持续观察吞吐和错误日志。',
      ['当前记录未结束，暂不能判断最终结果。'],
      ['刷新运行记录查看读写数量是否增长。', '如果长时间无读写变化，检查 Kafka/Flink/数据库链路。'],
      '低',
      'info',
      '暂不需要人工处理',
      '运行中观察'
    )
  }

  return {
    ...result,
    analysisSource: '本地规则',
    sourceLabel: '本地规则分析，可直接按步骤排查',
    generatedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
  }
}

function isFailedRun(run) {
  return String(run?.status || '').toUpperCase() === 'FAILED'
}

function runDiagnosticEvidence(run) {
  const errorMessage = String(run?.errorMessage || '').trim()
  const logs = runLogs(run)
  return {
    hasEvidence: isFailedRun(run) && Boolean(errorMessage || logs.length),
    text: [errorMessage, ...logs].filter(Boolean).join('\n'),
  }
}

function canUseAiForRunError(run) {
  const evidence = runDiagnosticEvidence(run)
  if (!evidence.hasEvidence) return false
  const ruleAnalysis = buildRunRuleAnalysis(run)
  const exceptionCount = (evidence.text.match(/\b(?:exception|caused by|error|at\s+)/gi) || []).length
  const needsAi = ruleAnalysis.matchedRule === '通用排查'
    || evidence.text.length > 800
    || runLogs(run).length >= 6
    || exceptionCount >= 3
  return needsAi
}

function runAnalysisHint(run) {
  const evidence = runDiagnosticEvidence(run)
  if (!evidence.hasEvidence) return '缺少错误摘要或日志，当前证据不足以进行可靠分析。'
  if (!canUseAiForRunError(run)) return '本地规则已直接识别问题，无需调用 AI；按建议处理即可。'
  return '先由本地规则识别已知错误；复杂堆栈或未知错误可交给 AI 归纳原因和排查步骤，AI 不会执行操作。'
}

function clipDiagnosticText(value, maxLength = 7000) {
  const text = String(value || '')
  if (text.length <= maxLength) return text
  return `日志过长，仅保留末尾 ${maxLength} 个字符：\n${text.slice(-maxLength)}`
}

function redactSensitiveConfig(value) {
  return String(value || '')
    .replace(/(\b(?:password|passwd|pwd|secret|token|api[_-]?key)\b\s*[:=]\s*)("?)[^",\s}]+/gi, '$1$2***')
    .replace(/:\/\/[^:@/\s]+:[^@/\s]+@/g, '://***:***@')
}

function extractRunSectionList(text, startPattern, endPattern) {
  const raw = String(text || '')
  const start = raw.search(startPattern)
  if (start < 0) return []
  const section = raw.slice(start)
  const end = section.search(endPattern)
  return (end > 0 ? section.slice(0, end) : section)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => /^([-*]\s+|\d+[.、]\s+)/.test(line))
    .map((line) => line.replace(/^([-*]\s+|\d+[.、]\s+)/, '').trim())
    .filter(Boolean)
    .slice(0, 5)
}

function parseRunAiResponse(response, run) {
  const rawText = String(response.rawText || '')
  const causes = extractRunSectionList(
    rawText,
    /(?:^|\n)\s*(?:#{0,3}\s*)?(?:可能原因|原因分析|Causes?)\s*[:：]?/i,
    /(?:\n\s*(?:#{0,3}\s*)?(?:排查建议|建议|风险等级|证据判断|Suggestions?|Risk)\b)/i
  )
  const suggestions = extractRunSectionList(
    rawText,
    /(?:^|\n)\s*(?:#{0,3}\s*)?(?:排查建议|建议|Suggestions?)\s*[:：]?/i,
    /(?:\n\s*(?:#{0,3}\s*)?(?:风险等级|证据判断|Risk)\b)/i
  )
  const riskLevel = rawText.match(/风险等级\s*[:：]\s*(高|中|低)/i)?.[1] || '待判断'
  const evidenceNote = rawText.match(/证据判断\s*[:：]\s*(.+)/i)?.[1]?.trim() || ''
  return {
    summary: String(response.summary || 'AI 分析完成').replace(/^结论\s*[:：]\s*/i, ''),
    causes: causes.length ? causes : [evidenceNote || '现有日志不足以确认唯一原因，请结合失败节点和后端日志继续判断。'],
    suggestions: suggestions.length ? suggestions : (response.suggestions || []),
    riskLevel,
    riskClass: riskLevel === '高' ? 'danger' : riskLevel === '低' ? 'success' : 'warn',
    needHuman: response.configured === false ? '需要配置 API Key' : 'AI 仅提供诊断建议',
    analysisSource: `${response.provider} / ${response.model}`,
    sourceLabel: 'AI 深度分析，仅供参考',
    generatedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
    rawText,
    runStatus: run?.status || '',
  }
}


function parseJsonArray(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    let parsed = JSON.parse(raw)
    for (let index = 0; index < 3 && typeof parsed === 'string'; index += 1) {
      parsed = JSON.parse(parsed)
    }
    return Array.isArray(parsed) ? parsed : []
  } catch (e) {
    return []
  }
}

function runLogs(run) {
  if (Array.isArray(run?.logs)) return run.logs
  if (Array.isArray(run?.logList)) return run.logList
  const parsed = parseJsonArray(run?.logsJson || run?.logJson || run?.log)
  if (parsed.length) return parsed
  const raw = run?.logsJson || run?.logJson || run?.log
  return raw ? String(raw).split(/\r?\n/).map((item) => item.trim()).filter(Boolean) : []
}

function runTargetTables(run) {
  return String(run?.targetTable || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function dagBranchResults(run) {
  const branches = parseJsonArray(run?.branchResultsJson || run?.branchResults)
  const nodes = parseJsonArray(run?.nodeResultsJson || run?.nodeResults)
  if (branches.length || nodes.length) {
    const branchItems = branches.map((item, index) => ({
      type: String(item.branchType || '').toLowerCase().includes('reject') ? 'reject' : 'pass',
      title: item.branchName || `分支 ${index + 1}`,
      desc: `${item.sourceNodeLabel || item.sourceNodeId || '上游'} → ${item.targetNodeLabel || item.targetNodeId || '下游'}`,
      conditionSummary: item.conditionSummary || '',
      inputCount: Number(item.inputCount ?? 0),
      outputCount: Number(item.outputCount ?? 0),
      count: Number(item.outputCount ?? 0),
      path: item.path || '',
      raw: JSON.stringify(item),
    }))
    const sinkItems = nodes
      .filter((item) => item.nodeType === 'sink')
      .map((item) => ({
        type: 'sink',
        title: item.nodeLabel || item.nodeId || '输出节点',
        desc: item.targetTable ? `写入 ${item.targetTable}` : item.exportFileName ? `导出 ${item.exportFileName}` : '输出节点',
        inputCount: Number(item.inputCount ?? 0),
        outputCount: Number(item.outputCount ?? 0),
        count: Number(item.outputCount ?? 0),
        ruleSummary: item.ruleSummary || '',
        outputLocation: item.outputLocation || '',
        targetTable: item.targetTable || '',
        exportFileName: item.exportFileName || '',
        path: item.branchName || '',
        raw: JSON.stringify(item),
      }))
    return [...branchItems, ...sinkItems]
  }
  return runLogs(run).map((line) => {
    const text = String(line || '')
    let match = text.match(/条件过滤节点\[(.+?)\]符合\s*(\d+)\s*条，不符合\s*(\d+)\s*条/)
    if (match) return { type: 'filter', title: match[1], desc: '条件过滤分支', pass: Number(match[2]), reject: Number(match[3]), raw: text }
    match = text.match(/分支\[(.+?)\]传递到节点\s*(.+?)：\s*(\d+)\s*条/)
    if (match) return { type: /不符合|异常|失败|reject|false/i.test(match[1]) ? 'reject' : 'pass', title: match[1], desc: `流向 ${match[2]}`, count: Number(match[3]), raw: text }
    match = text.match(/输出节点\[(.+?)\].*?(?:结果表\s*(.+?)\s*)?共\s*(\d+)\s*条/)
    if (match) return { type: 'sink', title: match[1], desc: match[2] ? `写入 ${match[2]}` : '输出节点', count: Number(match[3]), raw: text }
    return null
  }).filter(Boolean)
}

function runExportFiles(run) {
  const named = new Map()
  const addFile = (fileName, contentType = '') => {
    if (!fileName) return
    const name = String(fileName).trim()
    if (!name || named.has(name)) return
    const lower = name.toLowerCase()
    const format = lower.endsWith('.xlsx') ? 'Excel' : lower.endsWith('.csv') ? 'CSV' : '文件'
    named.set(name, { fileName: name, format, contentType })
  }
  if (Array.isArray(run?.exports)) {
    run.exports.forEach((file) => addFile(file.fileName, file.contentType))
  }
  parseJsonArray(run?.nodeResultsJson || run?.nodeResults)
    .filter((item) => item.exportFileName)
    .forEach((item) => addFile(item.exportFileName))
  return Array.from(named.values())
}

async function downloadExportFile(fileName) {
  try {
    await exportApi.download(fileName)
    notice.value = `已开始下载导出文件：${fileName}`
  } catch (e) {
    showError(e, '导出文件下载失败')
  }
}

function hasDagBranchResults(run) {
  return dagBranchResults(run).length > 0
}

function parseGraphObject(raw) {
  if (!raw) return null
  if (typeof raw === 'object') return raw
  try {
    let parsed = JSON.parse(raw)
    for (let index = 0; index < 3 && typeof parsed === 'string'; index += 1) {
      parsed = JSON.parse(parsed)
    }
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch (e) {
    return null
  }
}

function runFailureContext(run) {
  const task = tasks.value.find((item) => item.id === run?.taskId)
  const graph = parseGraphObject(task?.graphJson)
  const failureId = run?.failureNodeId
  if (!graph?.nodes?.length || !failureId) {
    return {
      task,
      node: null,
      upstream: [],
      downstream: [],
      affectedSinks: [],
      config: '',
      canLocate: Boolean(task?.id && failureId),
    }
  }
  const node = graph.nodes.find((item) => item.id === failureId)
  const upstream = graph.edges
    ?.filter((edge) => edge.target === failureId)
    .map((edge) => graph.nodes.find((item) => item.id === edge.source))
    .filter(Boolean) || []
  const downstream = graph.edges
    ?.filter((edge) => edge.source === failureId)
    .map((edge) => graph.nodes.find((item) => item.id === edge.target))
    .filter(Boolean) || []
  const affectedSinks = collectDownstreamSinks(graph, failureId)
  return {
    task,
    node,
    upstream,
    downstream,
    affectedSinks,
    config: node?.data?.config || node?.data?.targetTable || '',
    canLocate: Boolean(task?.id && failureId),
  }
}

function collectDownstreamSinks(graph, startId) {
  const result = []
  const seen = new Set([startId])
  const queue = [startId]
  while (queue.length) {
    const current = queue.shift()
    const nextEdges = graph.edges?.filter((edge) => edge.source === current) || []
    for (const edge of nextEdges) {
      if (seen.has(edge.target)) continue
      seen.add(edge.target)
      const nextNode = graph.nodes?.find((item) => item.id === edge.target)
      if (nextNode?.data?.nodeType === 'sink') result.push(nextNode)
      queue.push(edge.target)
    }
  }
  return result
}

function nodeBrief(node) {
  if (!node) return '-'
  const typeText = { source: '输入', transform: '处理', sink: '输出', control: '控制', start: '开始', end: '结束' }[node.data?.nodeType] || node.data?.nodeType || '节点'
  return `${node.data?.label || node.id}（${typeText} / ${node.data?.kind || '-'}）`
}

async function analyzeRunWithAi(run) {
  if (runAiLoading.value || !canUseAiForRunError(run)) return
  runAiLoading.value = true
  selectedRunAiAnalysis.value = null
  try {
    const failureContext = runFailureContext(run)
    const logs = runLogs(run).slice(-80)
    const runContext = {
      status: run?.status,
      taskName: run?.taskName,
      recordsRead: run?.recordsRead,
      recordsWritten: run?.recordsWritten,
      targetTable: run?.targetTable,
      errorMessage: clipDiagnosticText(run?.errorMessage, 7000),
      logs: logs.map((log) => clipDiagnosticText(log, 1000)),
      failureNode: failureContext.node ? {
        label: failureContext.node.data?.label || failureContext.node.id,
        type: failureContext.node.data?.nodeType || '',
        kind: failureContext.node.data?.kind || '',
        config: redactSensitiveConfig(failureContext.node.data?.config || failureContext.node.data?.targetTable || ''),
        upstream: failureContext.upstream.map(nodeBrief),
        downstream: failureContext.downstream.map(nodeBrief),
        affectedSinks: failureContext.affectedSinks.map(nodeBrief),
      } : null,
    }
    const resp = await aiApi.assist({
      mode: 'run-analysis',
      userPrompt: '仅分析本次失败运行。区分日志中的事实、基于证据的推断和可执行排查步骤；证据不足时必须明确说明，不要编造原因。',
      context: runContext,
    })
    if (resp.configured === false || !String(resp.rawText || '').trim()) {
      const ruleAnalysis = buildRunRuleAnalysis(run)
      selectedRunAiAnalysis.value = {
        ...ruleAnalysis,
        sourceLabel: 'AI 暂不可用，已降级为本地规则',
        analysisSource: resp.configured === false ? '本地规则（AI Key 未配置）' : '本地规则（AI 调用失败）',
      }
      return
    }
    selectedRunAiAnalysis.value = parseRunAiResponse(resp, run)
  } catch (e) {
    selectedRunAiAnalysis.value = buildRunRuleAnalysis(run)
  } finally {
    runAiLoading.value = false
  }
}

function showError(input, prefix = '操作失败') {
  const normalized = simplifyError(input, prefix)
  error.value = normalized.summary
  errorDetail.value = normalized.detail && normalized.detail !== normalized.summary ? normalized.detail : ''
  showErrorDetail.value = false
  notice.value = ''
}

function clearMessage() {
  error.value = ''
  errorDetail.value = ''
  showErrorDetail.value = false
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

function deny(message = '当前账号没有权限访问该功能。') {
  notice.value = ''
  error.value = message
}

function ensureWrite() {
  if (!canWrite.value) {
    deny('只读运维账号只能查看，不能新增、编辑、上传或执行。')
    return false
  }
  return true
}

function ensureExecute() {
  if (!canExecute.value) {
    deny('当前账号没有执行权限。')
    return false
  }
  return true
}

function setPage(page) {
  if (!pages.value.includes(page)) return deny()
  activePage.value = page
  if (page !== '作业编排') editingTaskId.value = null
}
function selectOverviewModule(module) {
  selectedOverviewModule.value = module.page
}
function clearOverviewSelection() {
  selectedOverviewModule.value = ''
}
function overviewModuleStyle(index, total) {
  const tones = [
    { hue: '194deg', accent: '#7dd3fc' },
    { hue: '214deg', accent: '#93c5fd' },
    { hue: '176deg', accent: '#5eead4' },
    { hue: '262deg', accent: '#c4b5fd' },
    { hue: '201deg', accent: '#67e8f9' },
    { hue: '228deg', accent: '#a5b4fc' },
  ]
  const tone = tones[index % tones.length]
  const orbitMs = 46000
  const baseAngle = -90 + index * (360 / Math.max(total, 1))
  const dynamicAngle = (baseAngle + (overviewTick.value % orbitMs) / orbitMs * 360) % 360
  const rad = dynamicAngle * Math.PI / 180
  const xRadius = total > 4 ? 345 : 310
  const yRadius = total > 4 ? 178 : 158
  const x = Math.cos(rad) * xRadius
  const y = Math.sin(rad) * yRadius
  const depth = (Math.sin(rad) + 1) / 2
  const isForeground = depth >= 0.52
  const scale = 0.78 + depth * 0.58
  const opacity = 0.56 + depth * 0.44
  const blur = (1 - depth) * 1.3
  const zIndex = isForeground
    ? Math.round(56 + depth * 22)
    : Math.round(8 + depth * 10)
  return {
    '--orbit-x': `${x}px`,
    '--orbit-y': `${y}px`,
    '--planet-scale': scale.toFixed(3),
    '--planet-opacity': opacity.toFixed(3),
    '--planet-blur': `${blur.toFixed(2)}px`,
    '--planet-depth': depth.toFixed(3),
    '--planet-front': isForeground ? 1 : 0,
    '--planet-hue': tone.hue,
    '--planet-accent': tone.accent,
    zIndex,
  }
}
function overviewDetailStyle(index, total) {
  const span = total <= 1 ? 0 : 112
  const angle = -56 + index * (span / Math.max(total - 1, 1))
  const rad = angle * Math.PI / 180
  const distance = 112
  return {
    '--mini-x': `${Math.cos(rad) * distance}px`,
    '--mini-y': `${Math.sin(rad) * distance}px`,
    '--mini-line-angle': `${angle + 180}deg`,
  }
}

async function refresh() {
  if (!currentUser.value) return
  clearMessage()
  const jobs = [loadTasks(), loadFiles(), loadTaskRuns(), loadCollaborations(), loadOutputDataSources()]
  if (activePage.value === 'Flink 作业') {
    jobs.push(loadFlinkHealth())
    if (flinkJob.value?.jobId) jobs.push(refreshFlinkJob())
  }
  await Promise.all(jobs)
}

async function loadTasks() {
  const filters = {}
  if (filterUsername.value.trim()) filters.creatorUsername = filterUsername.value.trim()
  if (filterStartDate.value) filters.startDate = filterStartDate.value
  if (filterEndDate.value) filters.endDate = filterEndDate.value
  try {
    tasks.value = await taskApi.list(filters)
    ensureDemoCollaborationLinks(tasks.value)
  } catch (e) { showError(e, '任务接口不可用') }
}

// 首次打开演示环境时，给三条核心任务补充可讲解的协作关系。
// 用户后续在星图中手动调整过关系后，不再覆盖其本机保存的布局和连线。
function ensureDemoCollaborationLinks(taskItems) {
  if (Object.keys(collaborationLinks.value || {}).length) return
  const plan = {
    '订单数据清洗与高价值订单筛选': [
      { username: 'xiaozhi', displayName: '小智', role: 'USER', relationType: 'LEADER' },
      { username: 'zhangsan', displayName: '张三', role: 'USER', relationType: 'COLLABORATOR' },
      { username: 'lisi', displayName: '李四', role: 'USER', relationType: 'COLLABORATOR' },
    ],
    '多分支工作': [
      { username: 'xiaoyu', displayName: '小雨', role: 'USER', relationType: 'VIEWER' },
    ],
    '持续工作1': [
      { username: 'xiaoming', displayName: '小明', role: 'USER', relationType: 'OPS' },
    ],
  }
  const next = {}
  taskItems.forEach((task) => {
    const members = plan[task.name]
    if (members) next[String(task.id)] = members.map((member) => ({ ...member, createdAt: new Date().toISOString() }))
  })
  if (!Object.keys(next).length) return
  collaborationLinks.value = next
  writeLocalJson('a22_collaboration_links', next)
}

function resetFilters() {
  filterUsername.value = ''
  filterStartDate.value = ''
  filterEndDate.value = ''
  loadTasks()
}

async function loadFiles() {
  filePage.value = 1
  try { files.value = await fileApi.list() } catch (e) { showError(e, '文件接口不可用') }
}

async function loadTaskRuns() {
  try { taskRuns.value = await taskRunApi.list() } catch (e) { showError(e, '运行记录接口不可用') }
}

async function loadFlinkHealth() {
  const checkedAt = new Date().toISOString()
  try {
    flinkHealth.value = { ...(await flinkApi.health()), checkedAt }
    await loadFlinkJobs()
  } catch {
    flinkHealth.value = { available: false, checkedAt }
  }
}

async function loadFlinkJobs() {
  try {
    const data = await flinkApi.jobs()
    const jobs = data?.overview?.jobs || data?.jobs || []
    const normalized = jobs.map(normalizeFlinkJob).slice(0, 8)
    flinkJobHistory.value = normalized
    const activeJob = normalized.find((job) => ['RUNNING', 'RESTARTING', 'CREATED'].includes(String(job.status).toUpperCase()))
    if (activeJob) flinkJob.value = { ...activeJob, parallelism: 1 }
  } catch {
    // 作业列表读取失败不影响集群健康状态和 Dashboard 展示。
  }
}

async function createTask() {
  if (!ensureWrite()) return
  clearMessage()
  if (!form.value.name.trim()) return deny('请填写任务名称。')
  try {
    const createdTask = await taskApi.create(taskPayload(form.value))
    showCreate.value = false
    form.value = blankTaskForm()
    await loadTasks()
    editingTaskId.value = createdTask?.id
    editingTaskReadonly.value = false
    activePage.value = '作业编排'
    notice.value = '任务已创建，已进入画布，请生成并保存工作流后再发布。'
  } catch (e) {
    showError(e, '创建任务失败')
  }
}

function openTaskEdit(task) {
  if (!ensureWrite()) return
  if (!ensureTaskOperation(task, '编辑')) return
  if (task.status === 'RUNNING') return deny('运行中任务不能编辑，请先停止。')
  clearMessage()
  editForm.value = {
    id: task.id,
    name: task.name,
    sourceType: task.sourceType,
    sinkType: task.sinkType,
    transforms: [...(task.transforms || [])],
    startTime: toDateTimeLocal(task.startTime),
    deadline: toDateTimeLocal(task.deadline),
    runMode: task.runMode || 'ONCE',
    scheduleType: task.scheduleType || 'MANUAL',
    scheduleExpression: task.scheduleExpression || '',
    continuousRemark: task.continuousRemark || '',
    incrementalMode: task.incrementalMode || 'NONE',
    cursorField: task.cursorField || '',
    lastCursorValue: task.lastCursorValue || '',
    processedField: task.processedField || 'process_status',
    unprocessedValue: task.unprocessedValue || 'UNPROCESSED',
    processedValue: task.processedValue || 'DONE',
    dedupKeys: task.dedupKeys || 'order_id',
  }
  showEditTask.value = true
}

async function saveTaskBasicInfo() {
  if (!ensureWrite()) return
  clearMessage()
  if (!editForm.value.name.trim()) return deny('请填写任务名称。')
  try {
    await taskApi.update(editForm.value.id, taskPayload(editForm.value))
    showEditTask.value = false
    notice.value = '任务参数已保存。'
    await loadTasks()
  } catch (e) {
    showError(e, '保存任务失败')
  }
}

function openEditor(taskOrId = editForm.value.id) {
  if (!ensureWrite()) return
  const id = typeof taskOrId === 'object' ? taskOrId.id : taskOrId
  const task = tasks.value.find((item) => item.id === id)
  if (!task) return deny('未找到要编辑的任务。')
  if (!ensureTaskOperation(task, '编辑')) return
  if (task.status === 'RUNNING') return deny('运行中的任务不能编辑画布，请先停止。')
  showEditTask.value = false
  editingTaskId.value = id
  editingTaskReadonly.value = false
  activePage.value = '作业编排'
}

function viewEditor(taskOrId) {
  const id = typeof taskOrId === 'object' ? taskOrId.id : taskOrId
  const task = tasks.value.find((item) => item.id === id)
  if (task && (role.value === 'SADMIN' || role.value === 'ADMIN') && task.status !== 'RUNNING') {
    return openEditor(taskOrId)
  }
  showEditTask.value = false
  editingTaskId.value = id
  editingTaskReadonly.value = true
  activePage.value = '作业编排'
}

async function loadCollaborations() {
  try {
    const links = await collaborationApi.list()
    const grouped = {}
    ;(Array.isArray(links) ? links : []).forEach((link) => {
      const taskId = String(link.taskId)
      if (!grouped[taskId]) grouped[taskId] = []
      grouped[taskId].push(link)
    })
    collaborationLinks.value = grouped
  } catch (e) {
    showError(e, '协作关系接口不可用')
  }
}

function locateRunFailureNode(run) {
  const task = tasks.value.find((item) => item.id === run?.taskId)
  if (!task?.id) return deny('未找到这条运行记录对应的任务。')
  if (!run?.failureNodeId) return deny('这条运行记录没有失败节点信息。')
  highlightedEditorNodeId.value = run.failureNodeId
  closeRunDetail()
  viewEditor(task)
}

function runSourceFileId(run) {
  const raw = run?.sourceFileId || run?.inputFileId || run?.fileId || run?.sourceFile?.id
  const id = Number(raw)
  return Number.isFinite(id) && id > 0 ? id : 0
}

function runNodeResults(run) {
  return parseJsonArray(run?.nodeResultsJson || run?.nodeResults)
}

function nodeInputSnapshot(run, nodeId) {
  const item = runNodeResults(run).find((node) => node.nodeId === nodeId)
  const rows = item?.inputPreview || item?.sampleRows || item?.preview
  return Array.isArray(rows) ? rows : []
}

async function replayRunFailureNode(run) {
  const context = runFailureContext(run)
  const task = context.task
  if (!task?.id) return deny('未找到这条运行记录对应的任务。')
  if (!run?.failureNodeId) return deny('这条运行记录没有失败节点信息，暂时不能自动带入节点单测。')
  if (context.node?.data?.nodeType && context.node.data.nodeType !== 'transform') {
    return deny('当前失败节点不是处理控件，节点级单测主要用于字段映射、过滤、脱敏、类型转换等处理节点。')
  }
  replayLoadingRunId.value = run.id
  try {
    let records = nodeInputSnapshot(run, run.failureNodeId)
    let sourceName = records.length ? `节点输入快照 · ${run.failureNodeLabel || run.failureNodeId}` : '内置复杂样例'
    const fileId = runSourceFileId(run)
    if (!records.length && fileId) {
      const file = await fileApi.get(fileId)
      records = Array.isArray(file?.content) ? file.content.slice(0, 20) : []
      sourceName = file?.originalName || file?.fileName || `文件 #${fileId}`
    }
    nodeReplayPayload.value = {
      nodeId: run.failureNodeId,
      runId: run.id,
      taskId: task.id,
      nodeLabel: run.failureNodeLabel || context.node?.data?.label || run.failureNodeId,
      sourceName,
      records,
      createdAt: Date.now(),
    }
    highlightedEditorNodeId.value = run.failureNodeId
    closeRunDetail()
    viewEditor(task)
    notice.value = records.length
      ? `已把运行记录 #${run.id} 的节点输入样例带入单测，可直接重跑失败节点。`
      : `已定位运行记录 #${run.id} 的失败节点，请在节点单测面板中使用内置样例或粘贴真实样例后重跑。`
  } catch (e) {
    showError(e, '带入节点单测失败')
  } finally {
    replayLoadingRunId.value = null
  }
}

function replayRunNodeSnapshot(run, node) {
  const task = tasks.value.find((item) => item.id === run?.taskId)
  if (!task?.id) return deny('未找到这条运行记录对应的任务。')
  if (node?.nodeType !== 'transform') return deny('节点级单测只支持处理控件。')
  const records = Array.isArray(node.inputPreview) ? node.inputPreview : []
  nodeReplayPayload.value = {
    nodeId: node.nodeId,
    runId: run.id,
    taskId: task.id,
    nodeLabel: node.nodeLabel || node.nodeId,
    sourceName: records.length ? `节点输入快照 · ${node.nodeLabel || node.nodeId}` : '内置复杂样例',
    records,
    createdAt: Date.now(),
  }
  highlightedEditorNodeId.value = node.nodeId
  closeRunDetail()
  viewEditor(task)
  notice.value = records.length
    ? `已把节点“${node.nodeLabel || node.nodeId}”的真实输入快照带入单测。`
    : `节点“${node.nodeLabel || node.nodeId}”暂无快照，已打开内置样例单测。`
}

function backToList() {
  editingTaskId.value = null
  editingTaskReadonly.value = false
  highlightedEditorNodeId.value = ''
  nodeReplayPayload.value = null
  activePage.value = '作业编排'
  refresh()
}

async function publishTask(task) {
  if (!ensureWrite()) return
  if (!ensureTaskOperation(task, '发布')) return
  clearMessage()
  try {
    await taskApi.setStatus(task.id, 'PUBLISHED')
    notice.value = `任务“${task.name}”已发布，可在任务管理页启动。`
    await loadTasks()
  } catch (e) {
    showError(e, '发布失败')
  }
}

async function withdrawTask(task) {
  if (!ensureWrite()) return
  if (!ensureTaskOperation(task, '撤回')) return
  if (task.status === 'RUNNING') return deny('运行中任务不能撤回，请先停止。')
  if (!['PUBLISHED', 'STOPPED', 'FAILED', 'SUCCEEDED'].includes(task.status)) return deny('只有已发布或已执行过的任务可以撤回到作业编排区。')
  if (!window.confirm(`确定撤回任务“${task.name}”吗？撤回后会回到作业编排区，可重新编辑并再次发布。`)) return
  clearMessage()
  try {
    await taskApi.withdraw(task.id)
    notice.value = `任务“${task.name}”已撤回到作业编排区，可重新编辑。`
    await Promise.all([loadTasks(), loadTaskRuns()])
  } catch (e) {
    showError(e, '撤回失败')
  }
}

function isFileSourceTask(task) {
  return ['JSON', 'CSV', 'Excel', 'XML', '文件'].includes(task.sourceType)
}

async function requestStartTask(task) {
  if (!ensureExecute()) return
  if (!ensureTaskOperation(task, '启动')) return
  if (isFileSourceTask(task)) {
    clearMessage()
    await loadFiles()
    pendingExecuteTask.value = task
    selectedSourceFileId.value = files.value[0]?.id ? String(files.value[0].id) : ''
    return
  }
  await startTask(task)
}

async function startTask(task, sourceFileId = null) {
  if (!ensureExecute()) return
  clearMessage()
  executingTaskId.value = task.id
  executeResult.value = null
  try {
    const result = await taskApi.execute(task.id, sourceFileId)
    executeResult.value = { taskName: task.name, ...result }
    notice.value = `执行成功：读取 ${result.recordsRead} 条，写入 ${result.recordsWritten} 条到 ${result.targetTable || '-'}。`
  } catch (e) {
    showError(e, '执行失败')
  } finally {
    executingTaskId.value = null
    pendingExecuteTask.value = null
    selectedSourceFileId.value = ''
    await Promise.all([loadTasks(), loadTaskRuns()])
  }
}

async function confirmStartWithFile() {
  if (!pendingExecuteTask.value) return
  if (!files.value.length) return deny('当前还没有已上传文件，请先到“数据工作区”上传 JSON/CSV/Excel/XML 文件。')
  if (!selectedSourceFileId.value) return deny('请选择本次执行要读取的输入文件。')
  await startTask(pendingExecuteTask.value, Number(selectedSourceFileId.value))
}

function cancelStartWithFile() {
  pendingExecuteTask.value = null
  selectedSourceFileId.value = ''
}

async function stopTask(task) {
  if (!ensureExecute()) return
  if (!ensureTaskOperation(task, '停止')) return
  clearMessage()
  try {
    await taskApi.setStatus(task.id, 'STOPPED')
    notice.value = `任务“${task.name}”已停止，后续可继续编辑。`
    await Promise.all([loadTasks(), loadTaskRuns()])
  } catch (e) {
    showError(e, '停止失败')
  }
}

async function pauseContinuousTask(task) {
  if (!ensureExecute()) return
  if (!ensureTaskOperation(task, '暂停')) return
  clearMessage()
  continuousActionId.value = task.id
  try {
    await taskApi.pauseContinuous(task.id)
    notice.value = `持续任务“${task.name}”已暂停，扫描器不会再自动执行它。`
    await Promise.all([loadTasks(), loadTaskRuns()])
  } catch (e) {
    showError(e, '暂停持续任务失败')
  } finally {
    continuousActionId.value = null
  }
}

async function resumeContinuousTask(task) {
  if (!ensureExecute()) return
  if (!ensureTaskOperation(task, '恢复')) return
  clearMessage()
  continuousActionId.value = task.id
  try {
    await taskApi.resumeContinuous(task.id)
    notice.value = `持续任务“${task.name}”已恢复，并会从当前时间开始等待扫描。`
    await Promise.all([loadTasks(), loadTaskRuns()])
  } catch (e) {
    showError(e, '恢复持续任务失败')
  } finally {
    continuousActionId.value = null
  }
}

async function resetContinuousRetry(task) {
  if (!ensureExecute()) return
  if (!ensureTaskOperation(task, '重置重试')) return
  clearMessage()
  continuousActionId.value = task.id
  try {
    await taskApi.resetContinuousRetry(task.id)
    notice.value = `持续任务“${task.name}”已重置重试次数并恢复扫描。`
    await Promise.all([loadTasks(), loadTaskRuns()])
  } catch (e) {
    showError(e, '重置重试失败')
  } finally {
    continuousActionId.value = null
  }
}

async function runContinuousNow(task) {
  if (!ensureExecute()) return
  if (!ensureTaskOperation(task, '立即执行')) return
  clearMessage()
  continuousActionId.value = task.id
  try {
    await taskApi.runContinuousNow(task.id)
    notice.value = `持续任务“${task.name}”已立即执行一次，运行记录已刷新。`
    await Promise.all([loadTasks(), loadTaskRuns()])
  } catch (e) {
    showError(e, '立即执行持续任务失败')
  } finally {
    continuousActionId.value = null
  }
}

async function deleteTask(task) {
  if (!ensureWrite()) return
  if (!ensureTaskOperation(task, '删除')) return
  if (task.status === 'RUNNING') return deny('运行中任务不能删除，请先停止。')
  if (!window.confirm(`确定删除任务“${task.name}”吗？该操作会同时清理关联运行记录。`)) return
  clearMessage()
  try {
    await taskApi.remove(task.id)
    notice.value = `任务“${task.name}”已删除。`
    if (editingTaskId.value === task.id) { editingTaskId.value = null; editingTaskReadonly.value = false }
    await Promise.all([loadTasks(), loadTaskRuns(), loadCollaborations()])
  } catch (e) {
    showError(e, '删除失败')
  }
}

function showRuns(task) {
  selectedRunTaskId.value = task.id
  activePage.value = '运行日志'
  loadTaskRuns()
}

function deleteFile(file) {
  if (!window.confirm(`确定删除文件「${file.originalName}」吗？`)) return
  clearMessage()
  fileApi.remove(file.id).then(async () => {
    notice.value = `文件「${file.originalName}」已删除`
    await loadFiles()
  }).catch(e => { showError(e, '删除文件失败') })
}

function startRenameFile(file) {
  editingFileId.value = file.id
  editingFileName.value = file.originalName
}

function cancelRenameFile() {
  editingFileId.value = null
  editingFileName.value = ''
}

function confirmRenameFile() {
  if (!editingFileName.value.trim()) return
  clearMessage()
  fileApi.rename(editingFileId.value, editingFileName.value.trim()).then(async () => {
    notice.value = '文件名已修改'
    editingFileId.value = null
    editingFileName.value = ''
    await loadFiles()
  }).catch(e => { showError(e, '重命名文件失败') })
}

function clearRunFilter() {
  selectedRunTaskId.value = null
}

function resetRunFilters() {
  runPage.value = 1
  runFilterName.value = ''
  runFilterStartDate.value = ''
  runFilterEndDate.value = ''
  runFilterStatus.value = ''
  runFilterTrigger.value = ''
  runFilterAlertOnly.value = false
  selectedRunTaskId.value = null
}

function openFilePicker() {
  if (ensureWrite()) fileInput.value?.click()
}

function chooseFile(event) {
  uploadFile(event.target.files?.[0])
  event.target.value = ''
}

function onDrop(event) {
  uploadFile(event.dataTransfer.files?.[0])
}

async function uploadFile(file) {
  if (!file || !ensureWrite()) return
  clearMessage()
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!['json', 'csv', 'xlsx', 'xls', 'xml', 'kafka', 'ndjson', 'jsonl'].includes(extension)) {
    return deny('仅可上传 JSON、CSV、Excel、XML、Kafka、NDJSON 或 JSONL 文件。')
  }
  uploading.value = true
  uploadProgress.value = 12
  const timer = window.setInterval(() => {
    if (uploadProgress.value < 88) uploadProgress.value += 8
  }, 180)
  try {
    const saved = await fileApi.upload(file)
    uploadProgress.value = 100
    files.value.unshift(saved)
    notice.value = `已解析并写入 MySQL：${saved.originalName}（${saved.recordCount} 条记录）。`
  } catch (e) {
    showError(e, '上传失败')
  } finally {
    window.clearInterval(timer)
    window.setTimeout(() => { uploading.value = false; uploadProgress.value = 0 }, 350)
  }
}

async function openPreview(file) {
  clearMessage()
  try {
    preview.value = await fileApi.get(file.id)
  } catch (e) { showError(e, '读取文件内容失败') }
}

async function openTablePreview(tableName) {
  clearMessage()
  const firstTable = String(tableName || '').split(',').map((item) => item.trim()).filter(Boolean)[0]
  if (!firstTable) return showError(new Error('请选择一个具体结果表'), '读取结果表失败')
  try { tablePreview.value = { tableName: firstTable, rows: await tableApi.preview(firstTable) } } catch (e) { showError(e, '读取结果表失败') }
}

function formatSize(size) {
  if (!Number.isFinite(size)) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function shortFlinkJobId(id) {
  if (!id) return '-'
  const text = String(id)
  return text.length > 12 ? `${text.slice(0, 12)}...` : text
}

function normalizeFlinkJob(job) {
  const start = job['start-time'] || job.startTime || job.submittedAt || ''
  const end = job['end-time'] || job.endTime || job.finishedAt || ''
  const rawName = job.name || job.taskName || 'Flink 作业'
  return {
    jobId: job.jid || job.jobId || job.id,
    taskName: rawName === 'a22-stream-job' ? 'A22 订单实时筛选演示' : rawName,
    status: job.state || job.status || 'UNKNOWN',
    submittedAt: start ? new Date(start).toISOString() : '',
    finishedAt: end && end > 0 ? new Date(end).toISOString() : '',
    errorSummary: job.errorSummary || '',
  }
}

function flinkJobStatusText(status) {
  const map = {
    SUBMITTED: '已提交',
    CREATED: '已创建',
    RUNNING: '运行中',
    FINISHED: '已完成',
    FAILED: '失败',
    CANCELED: '已取消',
    CANCELLED: '已取消',
    RESTARTING: '重启中',
  }
  return map[String(status || '').toUpperCase()] || status || '-'
}

function flinkJobStatusClass(status) {
  const value = String(status || '').toUpperCase()
  if (['RUNNING', 'CREATED', 'SUBMITTED', 'RESTARTING'].includes(value)) return 'running'
  if (['FINISHED', 'SUCCEEDED'].includes(value)) return 'success'
  if (['FAILED'].includes(value)) return 'danger'
  if (['CANCELED', 'CANCELLED'].includes(value)) return 'warn'
  return 'pending'
}

function runStatusText(status) {
  return statusText[status] || status
}

function runDuration(run) {
  const start = run?.startedAt ? new Date(run.startedAt).getTime() : 0
  const end = run?.finishedAt ? new Date(run.finishedAt).getTime() : Date.now()
  if (!start || Number.isNaN(start) || Number.isNaN(end) || end < start) return '-'
  const seconds = Math.round((end - start) / 1000)
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return `${minutes} 分 ${rest} 秒`
}

function runInputFile(run) {
  return run?.sourceFileName || run?.inputFileName || run?.fileName || run?.sourceFile?.originalName || run?.sourceFileId || '-'
}

function runExecutor(run) {
  return run?.executorUsername || run?.operatorUsername || run?.createdBy || run?.creatorUsername || '-'
}

function errorSummary(message) {
  return simplifyError(message, '执行失败').summary.replace(/^执行失败：/, '')
}

function openRunDetail(run) {
  selectedRunDetail.value = run
  selectedRunAiAnalysis.value = runDiagnosticEvidence(run).hasEvidence
    ? buildRunRuleAnalysis(run)
    : null
}

async function loadOutputDataSources() {
  outputDataSourcesLoading.value = true
  try {
    outputDataSources.value = await outputDataSourceApi.list()
  } catch (e) {
    showError(e, '数据库数据源接口不可用')
  } finally {
    outputDataSourcesLoading.value = false
  }
}

function openOutputDataSourceManager(id = '') {
  selectedOutputDataSourceId.value = id
  outputDataSourceManagerVisible.value = true
}

function closeRunDetail() {
  selectedRunDetail.value = null
  selectedRunAiAnalysis.value = null
}

async function submitFlinkJob() {
  if (!canUseFlink.value) return deny('只有管理员可以提交或取消 Flink 作业。')
  clearMessage()
  flinkSubmitting.value = true
  try {
    const submittedAt = new Date().toISOString()
    flinkJob.value = {
      ...(await flinkApi.submit(1)),
      taskName: 'A22 Kafka → MySQL 测试作业',
      parallelism: 1,
      submittedAt,
    }
    notice.value = `Flink 作业已提交：${flinkJob.value.jobId}`
    flinkJobHistory.value.unshift({
      jobId: flinkJob.value.jobId,
      taskName: flinkJob.value.taskName,
      status: flinkJob.value.status || 'SUBMITTED',
      submittedAt,
      finishedAt: '',
      errorSummary: '',
    })
    if (flinkJobHistory.value.length > 8) flinkJobHistory.value.pop()
  } catch (e) {
    showError(e, 'Flink 提交失败')
  } finally {
    flinkSubmitting.value = false
  }
}

async function refreshFlinkJob() {
  if (!flinkJob.value?.jobId) return
  clearMessage()
  try {
    const latest = await flinkApi.status(flinkJob.value.jobId)
    const nextStatus = latest.status || latest.state || flinkJob.value.status
    const finishedAt = ['FINISHED', 'FAILED', 'CANCELED', 'CANCELLED'].includes(String(nextStatus || '').toUpperCase()) ? new Date().toISOString() : flinkJob.value.finishedAt
    flinkJob.value = { ...flinkJob.value, ...latest, status: nextStatus, finishedAt, checkedAt: new Date().toISOString() }
    const item = flinkJobHistory.value.find((h) => h.jobId === flinkJob.value.jobId)
    if (item) {
      item.status = nextStatus || item.status
      item.finishedAt = finishedAt || item.finishedAt
      item.errorSummary = simplifyError(latest.error || latest.message || latest.exception || '', '暂无错误信息')
    }
  } catch (e) { showError(e, '读取 Flink 状态失败') }
}

async function cancelFlinkJob() {
  if (!canUseFlink.value) return deny('只有管理员可以取消 Flink 作业。')
  if (!flinkJob.value?.jobId) return
  clearMessage()
  try {
    await flinkApi.cancel(flinkJob.value.jobId)
    notice.value = '已向 Flink 发送取消请求。'
    await refreshFlinkJob()
  } catch (e) {
    showError(e, '取消 Flink 作业失败')
  }
}

async function onLogin(auth) {
  saveSession(auth)
  currentUser.value = applyStoredAvatar(auth.user)
  persistCurrentUser()
  activePage.value = '概览'
  await refresh()
}

async function logout() {
  try { if (getToken()) await authApi.logout() } catch { /* ignore expired token */ }
  clearSession()
  currentUser.value = null
  activePage.value = '概览'
  tasks.value = []
  files.value = []
  taskRuns.value = []
  collaborationLinks.value = {}
  collaborationKnownUsers.value = []
}

async function startEditUsername() {
  newUsername.value = currentUser.value.username
  editingUsername.value = true
}

function cancelEditUsername() {
  editingUsername.value = false
  newUsername.value = ''
}

async function confirmEditUsername() {
  clearMessage()
  if (!newUsername.value.trim()) { error.value = '用户名不能为空'; return }
  if (newUsername.value.trim().length < 2) { error.value = '用户名至少 2 个字符'; return }
  try {
    const result = await authApi.updateUsername(newUsername.value.trim())
    currentUser.value = { ...currentUser.value, username: result.username }
    persistCurrentUser()
    editingUsername.value = false
    newUsername.value = ''
    notice.value = '用户名已修改'
  } catch (e) { showError(e, '修改用户名失败') }
}

async function startEditDisplayName() {
  newDisplayName.value = currentUser.value.displayName || currentUser.value.username
  editingDisplayName.value = true
}

function cancelEditDisplayName() {
  editingDisplayName.value = false
}

async function confirmEditDisplayName() {
  clearMessage()
  if (!newDisplayName.value.trim()) { error.value = '显示名称不能为空'; return }
  try {
    const result = await authApi.updateDisplayName(newDisplayName.value.trim())
    currentUser.value = { ...currentUser.value, displayName: result.displayName }
    persistCurrentUser()
    editingDisplayName.value = false
    notice.value = '显示名称已修改'
  } catch (e) { showError(e, '修改显示名称失败') }
}

async function submitChangePassword() {
  clearMessage()
  if (!changePasswordForm.value.oldPassword || !changePasswordForm.value.newPassword) {
    error.value = '请填写完整'
    return
  }
  if (changePasswordForm.value.newPassword.length < 8) {
    error.value = '新密码长度至少 8 位'
    return
  }
  if (changePasswordForm.value.newPassword !== changePasswordForm.value.confirmPassword) {
    error.value = '两次输入的新密码不一致'
    return
  }
  try {
    await authApi.changePassword({ oldPassword: changePasswordForm.value.oldPassword, newPassword: changePasswordForm.value.newPassword })
    notice.value = '密码修改成功，请重新登录'
    showProfile.value = false
    changePasswordForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
    clearSession()
    currentUser.value = null
  } catch (e) {
    showError(e, '修改密码失败')
  }
}

async function restoreSession() {
  onAuthExpired(() => {
    currentUser.value = null
    error.value = '登录已失效，请重新登录。'
  })
  if (!getToken()) { booting.value = false; return }
  try {
    currentUser.value = await authApi.me()
    currentUser.value = applyStoredAvatar(currentUser.value)
    persistCurrentUser()
    if (!pages.value.includes(activePage.value)) activePage.value = pages.value[0] || '概览'
    await refresh()
  } catch {
    clearSession()
    currentUser.value = null
  } finally {
    booting.value = false
  }
}

function openAvatarPicker() {
  avatarInput.value?.click()
}

function chooseAvatar(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    error.value = '请选择图片文件作为头像。'
    return
  }
  if (file.size > 1024 * 1024) {
    error.value = '头像图片不能超过 1MB。'
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const avatarUrl = String(reader.result || '')
    const key = userAvatarKey()
    const store = readAvatarStore()
    if (key) {
      store[key] = avatarUrl
      localStorage.setItem('a22_user_avatars', JSON.stringify(store))
    }
    currentUser.value = { ...currentUser.value, avatarUrl }
    persistCurrentUser()
    notice.value = '头像已更新'
    error.value = ''
  }
  reader.onerror = () => {
    error.value = '头像读取失败，请重新选择图片。'
  }
  reader.readAsDataURL(file)
}

function removeAvatar() {
  const key = userAvatarKey()
  if (key) {
    const store = readAvatarStore()
    delete store[key]
    localStorage.setItem('a22_user_avatars', JSON.stringify(store))
  }
  const { avatarUrl, ...rest } = currentUser.value
  currentUser.value = rest
  persistCurrentUser()
  notice.value = '头像已移除'
}

function startOverviewOrbit() {
  function frame(now) {
    if (!overviewLastFrame) overviewLastFrame = now
    if (!overviewOrbitPaused.value) {
      overviewTick.value += now - overviewLastFrame
    }
    overviewLastFrame = now
    overviewRaf = requestAnimationFrame(frame)
  }
  overviewRaf = requestAnimationFrame(frame)
}

onMounted(() => {
  restoreSession()
  startOverviewOrbit()
})


// 双视频无缝交叉淡入循环：备用视频可播放后再切换，避免背景短暂消失。
let bgVideoSetup = false
function setupBgVideo() {
  if (bgVideoSetup) return
  const v1 = document.getElementById('bgV1')
  const v2 = document.getElementById('bgV2')
  if (!v1 || !v2) { setTimeout(setupBgVideo, 500); return }
  bgVideoSetup = true

  const videos = [v1, v2]
  videos.forEach((video) => {
    video.muted = true
    video.playsInline = true
    video.preload = 'auto'
    video.style.opacity = '0'
  })

  let active = v1
  let standby = v2
  const AHEAD = 1.2
  const FADE_MS = 900
  let switching = false

  active.style.opacity = '1'
  safePlay(active)

  function safePlay(video) {
    const playPromise = video.play?.()
    if (playPromise?.catch) playPromise.catch(() => {})
  }

  function resetVideo(video) {
    try { video.currentTime = 0 } catch {}
  }

  function finishSwitch() {
    standby.style.opacity = '1'
    active.style.opacity = '0'
    const previous = active
    const next = standby
    active = next
    standby = previous
    window.setTimeout(() => {
      standby.pause()
      resetVideo(standby)
      standby.style.opacity = '0'
      switching = false
    }, FADE_MS)
  }

  function doSwitch() {
    if (switching) return
    switching = true
    standby.pause()
    resetVideo(standby)

    const startStandby = () => {
      standby.removeEventListener('canplay', startStandby)
      const playPromise = standby.play()
      if (playPromise?.then) {
        playPromise.then(finishSwitch).catch(() => {
          switching = false
          active.style.opacity = '1'
          safePlay(active)
        })
      } else {
        finishSwitch()
      }
    }

    if (standby.readyState >= 2) {
      startStandby()
    } else {
      standby.addEventListener('canplay', startStandby, { once: true })
      standby.load()
      window.setTimeout(() => {
        if (switching && standby.readyState < 2) {
          standby.removeEventListener('canplay', startStandby)
          switching = false
          active.style.opacity = '1'
          safePlay(active)
        }
      }, 1800)
    }
  }

  function keepAlive() {
    if (!document.hidden && active.paused && active.readyState >= 2) safePlay(active)
  }

  function checkLoop() {
    if (!document.hidden && active.duration && active.currentTime >= active.duration - AHEAD) doSwitch()
    requestAnimationFrame(checkLoop)
  }

  videos.forEach((video) => {
    video.addEventListener('ended', () => {
      if (video === active) doSwitch()
    })
    video.addEventListener('stalled', keepAlive)
    video.addEventListener('error', keepAlive)
  })
  document.addEventListener('visibilitychange', keepAlive)
  requestAnimationFrame(checkLoop)
}
watch(currentUser, (u) => {
  if (u) nextTick(setupBgVideo)
  else bgVideoSetup = false
}, { immediate: true, flush: "post" })

onUnmounted(() => {
  if (overviewRaf) cancelAnimationFrame(overviewRaf)
})
</script>

<template>
  <LoginPage v-if="!currentUser && !booting" @login="onLogin" />
  <div v-else-if="booting" class="login-page">
    <div class="login-card"><p>正在恢复登录态...</p></div>
  </div>

  <div v-else class="shell">
    <video class="app-bg-video" id="bgV1" src="/img/login-bg.mp4" autoplay muted playsinline preload="auto"></video>
    <video class="app-bg-video" id="bgV2" src="/img/login-bg.mp4" muted playsinline preload="auto" style="opacity:0"></video>
    <aside>
      <div class="brand"><img class="brand-logo" src="/img/brand-mark.png" alt="智流工坊" /><div>智流工坊 <small>FlowHub</small></div></div>
      <nav class="gooey-nav" :style="{ '--active-index': activePageIndex }">
        <span class="gooey-active" aria-hidden="true"></span>
        <button v-for="item in pages" :key="item" :class="{ active: activePage === item }" @click="setPage(item)">
          <span class="nav-icon" v-html="navIcons[item]"></span>
          <span class="nav-label">{{ item }}</span>
          <span v-if="activePage === item" class="gooey-particles" aria-hidden="true">
            <i v-for="particle in 8" :key="particle"></i>
          </span>
        </button>
      </nav>
      <div class="side-note">
        <b>当前角色：{{ roleText[role] }}</b><br>
        {{ role === 'OPS_READONLY' ? '只读查询模式，适合运维展示。' : '任务发布管理 + 画布编排 + 文件入库。' }}
      </div>
      <div class="user-panel">
        <div class="user-panel-top">
          <div class="user-avatar">
            <img v-if="currentUser.avatarUrl" :src="currentUser.avatarUrl" alt="用户头像" />
            <span v-else>{{ avatarInitial() }}</span>
          </div>
          <div class="user-info">
            <b>{{ currentUser.username }}</b>
            <small>{{ roleText[currentUser.role] }}</small>
          </div>
        </div>
        <div class="user-panel-actions">
          <button class="user-change-pwd" @click="showProfile = true">个人信息</button>
          <button class="user-logout" @click="logout">退出登录</button>
        </div>
      </div>
    </aside>

    <main :class="{ 'overview-main': activePage === '概览' }">
      <header>
        <div>
          <p class="eyebrow">实时数据流控制台</p>
          <h1>{{ activePage }}</h1>
        </div>
        <button v-if="activePage !== '用户管理'" class="btn btn-ghost" @click="refresh">刷新数据</button>
      </header>
      <div v-if="error" class="error app-error-card">
        <div class="app-error-main">
          <span>{{ error }}</span>
          <button v-if="errorDetail" type="button" class="link error-detail-toggle" @click="showErrorDetail = !showErrorDetail">
            {{ showErrorDetail ? '收起技术详情' : '查看技术详情' }}
          </button>
        </div>
        <pre v-if="errorDetail && showErrorDetail" class="technical-detail">{{ errorDetail }}</pre>
      </div>
      <p v-if="notice" class="notice">{{ notice }}</p>

      <div class="page-anim" :key="activePage">
      <template v-if="activePage === '概览'">
        <section class="overview-map" :class="{ 'has-selection': selectedOverview }" @click="clearOverviewSelection">
          <div class="overview-stage">
            <span class="overview-ring overview-ring-one"></span>
            <span class="overview-ring overview-ring-two"></span>
            <span class="overview-ring overview-ring-three"></span>
            <div class="overview-core">
              <span class="overview-core-halo"></span>
              <div class="overview-earth">
                <span class="earth-shine"></span>
              </div>
              <b>Smart Workshop</b>
              <small>AI Data Orchestration</small>
            </div>
            <div class="overview-module-orbit">
              <button
                v-for="(module, index) in overviewModules"
                :key="module.page"
                class="overview-module"
                :class="{ active: selectedOverview?.page === module.page }"
                :style="overviewModuleStyle(index, overviewModules.length)"
                :title="`${module.label}：${module.desc}`"
                :aria-label="`${module.label}：${module.desc}`"
                @mouseenter="overviewOrbitPaused = true"
                @mouseleave="overviewOrbitPaused = false"
                @click.stop="selectOverviewModule(module)"
              >
                <span class="overview-module-face">
                  <span class="overview-star">
                    <span class="overview-star-icon" v-html="navIcons[module.page]"></span>
                  </span>
                  <b>{{ module.label }}</b>
                  <small>{{ module.summary }}</small>
                </span>
                <template v-if="selectedOverview?.page === module.page">
                  <span
                    v-for="(detail, detailIndex) in module.details"
                    :key="`${module.page}-${detail.label}`"
                    class="overview-mini"
                    :style="overviewDetailStyle(detailIndex, module.details.length)"
                  >
                    <span>{{ detail.label }}</span>
                    <b>{{ detail.value }}</b>
                  </span>
                </template>
              </button>
            </div>
          </div>
          <aside v-if="selectedOverview" class="overview-side" @click.stop>
            <p class="eyebrow">ROLE BASED OVERVIEW</p>
            <h2>{{ selectedOverview.label }}</h2>
            <p>{{ selectedOverview.desc }}</p>
            <div class="overview-side-grid">
              <article v-for="detail in selectedOverview.details" :key="detail.label">
                <span>{{ detail.label }}</span>
                <b>{{ detail.value }}</b>
              </article>
            </div>
            <div class="overview-permission">
              <span>当前用户</span>
              <b>{{ currentUser.username }}</b>
              <small>{{ roleText[role] }} · 可访问 {{ overviewModules.length }} 个业务模块</small>
            </div>
            <button class="btn btn-primary overview-enter" @click="setPage(selectedOverview.page)">
              进入{{ selectedOverview.label }}
            </button>
          </aside>
        </section>
      </template>

      <template v-else-if="activePage === '任务管理'">
        <section class="toolbar">
          <div><h2>{{ taskManagementView === 'list' ? '任务列表' : '任务协作星图' }}</h2><p>{{ taskManagementView === 'list' ? '完整状态流：草稿 → 已发布 → 运行中 → 已停止/成功/失败。' : '以任务和人员节点展示协作关系，节点可拖拽，协作关系由后端持久化。' }}</p></div>
          <div class="button-row">
            <div class="view-switch">
              <button :class="['btn btn-sm', taskManagementView === 'list' ? 'btn-primary' : 'btn-ghost']" @click="taskManagementView = 'list'">列表视图</button>
              <button :class="['btn btn-sm', taskManagementView === 'collaboration' ? 'btn-primary' : 'btn-ghost']" @click="taskManagementView = 'collaboration'">协作视图</button>
            </div>
          </div>
        </section>
        <section v-if="taskManagementView === 'list'" class="filter-bar">
          <div class="filter-fields">
            <label v-if="role === 'SADMIN' || role === 'ADMIN'" class="filter-label">
              <span>创建者</span>
              <input v-model="filterUsername" type="text" placeholder="输入用户名搜索" @keyup.enter="loadTasks" />
            </label>
            <label class="filter-label">
              <span>开始日期</span>
              <input v-model="filterStartDate" type="date" />
            </label>
            <label class="filter-label">
              <span>结束日期</span>
              <input v-model="filterEndDate" type="date" />
            </label>
          </div>
          <div class="filter-actions">
            <button class="btn btn-primary btn-sm" @click="loadTasks">筛选</button>
            <button class="btn btn-ghost btn-sm" @click="resetFilters">重置</button>
          </div>
        </section>
        <TaskTable v-if="taskManagementView === 'list'" :tasks="managementTasks" mode="management" :current-user="currentUser" :status-text="statusText" :format-date="formatDate" :can-write="canWrite" :can-execute="canExecute" :can-operate-task="canOperateTask" @view="viewEditor" @edit="openTaskEdit" @publish="publishTask" @withdraw="withdrawTask" @start="requestStartTask" @stop="stopTask" @delete="deleteTask" @runs="showRuns" />
        <section v-else class="panel collaboration-panel">
          <div class="collaboration-toolbar">
            <div>
              <h2>任务协作驾驶舱</h2>
              <p>以任务风险、人员负载和协作关系构成点阵星图；节点可拖拽，协作关系由后端持久化。</p>
            </div>
            <div class="collaboration-tools">
              <label class="toggle-pill"><input v-model="collaborationOnlyMine" type="checkbox" @change="clearCollaborationFocus" />只看我的任务</label>
              <label class="toggle-pill"><input v-model="collaborationOnlyAbnormal" type="checkbox" />只看异常任务</label>
              <button v-if="collaborationFocusUsername" class="btn btn-ghost btn-sm" @click="clearCollaborationFocus">取消聚焦：{{ collaborationFocusUsername }}</button>
              <select v-model="collaborationStatusFilter">
                <option value="">全部状态</option>
                <option v-for="(text, key) in statusText" :key="key" :value="key">{{ text }}</option>
              </select>
              <select v-model="collaborationRoleFilter">
                <option value="">全部角色</option>
                <option value="SADMIN">高级管理员</option>
                <option value="ADMIN">管理员</option>
                <option value="USER">普通用户</option>
                <option value="OPS_READONLY">只读运维</option>
              </select>
              <button class="btn btn-ghost btn-sm" @click="autoLayoutCollaboration('owner')">按负责人聚合</button>
              <button class="btn btn-ghost btn-sm" @click="autoLayoutCollaboration('status')">按状态聚合</button>
              <button class="btn btn-ghost btn-sm" @click="fitCollaborationView">适应视图</button>
              <button class="btn btn-ghost btn-sm" @click="resetCollaborationLayout">重置布局</button>
            </div>
          </div>
          <div class="collaboration-layout">
            <div class="collaboration-canvas" @click="closeCollaborationContextMenu" @contextmenu.prevent="closeCollaborationContextMenu">
              <div class="collaboration-map-space"></div>
              <div v-if="!collaborationNodes.length" class="empty-state collaboration-empty">
                <b>暂无可视化节点</b>
                <span>发布任务后，这里会展示任务与人员协作关系。</span>
              </div>
              <div
                v-for="edge in collaborationEdges"
                :key="edge.id"
                class="collaboration-edge"
                :class="`relation-${edge.relation}`"
                :style="collaborationEdgeStyle(edge)"
                :title="collaborationRelationText[edge.relation] || edge.relation"
              >
                <span>{{ collaborationRelationText[edge.relation] || edge.relation }}</span>
              </div>
              <button
                v-for="node in collaborationNodes"
                :key="node.id"
                type="button"
                class="collaboration-node"
                :class="[
                  node.type === 'task' ? 'task-node' : 'person-node',
                  node.type === 'person' ? `role-${node.person.role}` : `status-${String(node.task.status).toLowerCase()}`,
                  node.type === 'task' ? `risk-${taskRiskLevel(node.task)}` : `load-${personLoadLevel(node.person)}`,
                  { active: collaborationSelectedId === node.id, current: node.type === 'person' && node.person.username === currentUser.username }
                ]"
                :style="collaborationNodeStyle(node)"
                :title="node.type === 'task' ? `${node.task.name}｜${taskRiskText(node.task)}` : `${node.person.username}｜${personLoadText(node.person)}`"
                @mousedown.prevent="startCollaborationDrag($event, node)"
                @click.stop="selectCollaborationNode(node)"
                @contextmenu="openCollaborationContextMenu($event, node)"
              >
                <template v-if="node.type === 'task'">
                  <span class="node-orb"><span class="node-orb-icon">T</span></span>
                  <b>{{ node.task.name }}</b>
                  <small>{{ taskRiskText(node.task) }}</small>
                </template>
                <template v-else>
                  <span class="node-orb person-avatar">{{ avatarInitial(node.person) }}</span>
                  <b>{{ node.person.displayName || node.person.username }}</b>
                  <small>{{ personLoadText(node.person) }}</small>
                </template>
              </button>
              <div
                v-if="collaborationContextMenu.visible"
                class="collaboration-context-menu"
                :style="{ left: `${collaborationContextMenu.x}px`, top: `${collaborationContextMenu.y}px` }"
                @click.stop
              >
                <div class="context-title">
                  {{ activeCollaborationMenuNode?.type === 'task' ? '任务操作' : '人员操作' }}
                  <small>{{ activeCollaborationMenuNode?.type === 'task' ? activeCollaborationMenuNode.task.name : activeCollaborationMenuNode?.person.username }}</small>
                </div>
                <button
                  v-for="action in activeCollaborationMenuActions"
                  :key="action.key"
                  type="button"
                  :class="['context-action', `context-${action.kind}`]"
                  @click="runCollaborationMenuAction(action)"
                >
                  {{ action.label }}
                </button>
                <span v-if="!activeCollaborationMenuActions.length" class="context-empty">暂无可用操作</span>
              </div>
            </div>
            <aside class="collaboration-detail">
              <template v-if="selectedCollaborationNode?.type === 'task'">
                <p class="eyebrow">TASK NODE</p>
                <h3>{{ selectedCollaborationNode.task.name }}</h3>
                <div class="detail-grid compact">
                  <article><span>状态</span><b>{{ statusText[selectedCollaborationNode.task.status] || selectedCollaborationNode.task.status }}</b></article>
                  <article><span>风险</span><b class="risk-value">{{ taskRiskText(selectedCollaborationNode.task) }}</b></article>
                  <article><span>创建者</span><b>{{ selectedCollaborationNode.task.creatorUsername || '未记录' }}</b></article>
                  <article><span>开始时间</span><b>{{ formatDate(selectedCollaborationNode.task.startTime) }}</b></article>
                  <article><span>截止时间</span><b>{{ formatDate(selectedCollaborationNode.task.deadline || selectedCollaborationNode.task.endTime) }}</b></article>
                  <article><span>链路</span><b>{{ selectedCollaborationNode.task.sourceType }} → {{ selectedCollaborationNode.task.sinkType }}</b></article>
                  <article><span>更新时间</span><b>{{ formatDate(selectedCollaborationNode.task.updatedAt) }}</b></article>
                </div>
                <div class="collaboration-actions">
                  <button
                    v-for="action in collaborationActionsForNode(selectedCollaborationNode)"
                    :key="`detail-${action.key}`"
                    :class="['btn btn-sm', action.kind === 'primary' ? 'btn-primary' : 'btn-ghost', action.kind === 'danger' ? 'btn-danger-soft' : '']"
                    @click="action.handler()"
                  >
                    {{ action.label }}
                  </button>
                </div>
                <div class="collaboration-assign">
                  <h4>分配协作者</h4>
                  <p>{{ collaborationCanAssign(selectedCollaborationNode.task) ? '协作关系会保存到后端，其他登录用户刷新后即可看到。' : '当前账号只能查看协作关系。' }}</p>
                  <div v-if="collaborationCanAssign(selectedCollaborationNode.task)" class="assign-row">
                    <div class="collab-user-picker">
                      <input
                        v-model="collaborationNewMember"
                        placeholder="输入用户名或昵称"
                        autocomplete="off"
                        @focus="collaborationMemberFocused = true; loadCollaborationUsers()"
                        @input="collaborationMemberFocused = true"
                        @blur="closeCollaborationUserDropdownSoon"
                      />
                      <div v-if="collaborationMemberFocused" class="collab-user-dropdown">
                        <button
                          v-for="candidate in collaborationUserCandidates"
                          :key="candidate.username"
                          type="button"
                          class="collab-user-option"
                          @mousedown.prevent="selectCollaborationCandidate(candidate)"
                        >
                          <span>
                            <b>{{ candidate.username }}</b>
                            <small>{{ candidate.displayName || candidate.username }}</small>
                          </span>
                          <em>{{ roleText[candidate.role] || candidate.role }}</em>
                        </button>
                        <div v-if="!collaborationUserCandidates.length" class="collab-user-empty">
                          暂无匹配用户，可手动输入用户名
                        </div>
                      </div>
                    </div>
                    <select v-model="collaborationNewRole">
                      <option value="SADMIN">高级管理员</option>
                      <option value="USER">普通用户</option>
                      <option value="ADMIN">管理员</option>
                      <option value="OPS_READONLY">只读运维</option>
                    </select>
                    <select v-model="collaborationNewRelation">
                      <option value="LEADER">负责人</option>
                      <option value="COLLABORATOR">协作者</option>
                      <option value="VIEWER">查看者</option>
                      <option value="OPS">运维观察者</option>
                    </select>
                    <button class="btn btn-primary btn-sm" @click="assignCollaborator(selectedCollaborationNode.task)">添加</button>
                  </div>
                  <div class="member-list">
                    <span class="member-pill">创建者：{{ selectedCollaborationNode.task.creatorUsername || '未记录' }}</span>
                    <span
                      v-for="member in collaborationLinks[String(selectedCollaborationNode.task.id)] || []"
                      :key="`${selectedCollaborationNode.task.id}-${member.username}`"
                      class="member-pill"
                    >
                      {{ collaborationRelationText[member.relationType] || member.relationType }}：{{ member.username }}
                      <button v-if="collaborationCanAssign(selectedCollaborationNode.task)" @click="removeCollaborator(selectedCollaborationNode.task, member)">×</button>
                    </span>
                  </div>
                </div>
              </template>
              <template v-else-if="selectedCollaborationNode?.type === 'person'">
                <p class="eyebrow">PERSON NODE</p>
                <h3>{{ selectedCollaborationNode.person.displayName || selectedCollaborationNode.person.username }}</h3>
                <div class="detail-grid compact">
                  <article><span>账号</span><b>{{ selectedCollaborationNode.person.username }}</b></article>
                  <article><span>角色</span><b>{{ roleText[selectedCollaborationNode.person.role] || selectedCollaborationNode.person.role }}</b></article>
                  <article><span>关联任务</span><b>{{ selectedCollaborationNode.person.taskIds.length }} 个</b></article>
                  <article><span>负载</span><b>{{ personLoadText(selectedCollaborationNode.person) }}</b></article>
                </div>
                <div class="collaboration-assign person-summary">
                  <h4>负责 / 创建的任务</h4>
                  <p v-if="!selectedPersonOwnerTasks.length">暂无负责或创建任务。</p>
                  <div class="person-task-list">
                    <button v-for="task in selectedPersonOwnerTasks" :key="`owner-${task.id}`" class="btn btn-ghost btn-sm" @click="collaborationSelectedId = `task-${task.id}`">
                      #{{ task.id }} {{ task.name }}
                    </button>
                  </div>
                  <h4>协作 / 观察的任务</h4>
                  <p v-if="!selectedPersonLinkedTasks.length">暂无额外协作关系。</p>
                  <div class="person-task-list">
                    <button v-for="task in selectedPersonLinkedTasks" :key="`link-${task.id}`" class="btn btn-ghost btn-sm" @click="collaborationSelectedId = `task-${task.id}`">
                      #{{ task.id }} {{ task.name }}
                    </button>
                  </div>
                </div>
                <div class="person-task-list">
                  <button v-for="taskId in selectedCollaborationNode.person.taskIds" :key="taskId" class="btn btn-ghost btn-sm" @click="collaborationSelectedId = `task-${taskId}`">
                    #{{ taskId }} {{ tasks.find(t => t.id === taskId)?.name || '任务' }}
                  </button>
                </div>
              </template>
              <template v-else>
                <p class="eyebrow">COLLABORATION</p>
                <h3>点击节点查看详情</h3>
                <p>你可以拖动任务节点和人员节点，形成适合汇报展示的协作拓扑。</p>
              </template>
            </aside>
          </div>
        </section>
        <section v-if="executingTaskId" class="panel feedback-card loading-card">任务正在执行，请稍候，完成后会自动刷新运行记录。</section>
        <section v-if="executeResult" class="panel feedback-card">
          <h2>最近执行结果</h2>
          <p>{{ executeResult.taskName }}：读取 {{ executeResult.recordsRead }} 条，写入 {{ executeResult.recordsWritten }} 条，结果表 {{ executeResult.targetTable || '-' }}。</p>
        </section>
      </template>

      <template v-else-if="activePage === '作业编排'">
        <FlowEditor v-if="editingTaskId" :task-id="editingTaskId" :readonly="editingTaskReadonly" :highlight-node-id="highlightedEditorNodeId" :node-replay="nodeReplayPayload" @back="backToList" />
        <section v-else class="workflow-hub">
          <div class="workspace-metrics workflow-metrics">
            <article><span>可编排任务</span><strong>{{ workflowTasks.length }}</strong><small>草稿、停止、成功或失败状态可继续调整</small></article>
            <article><span>已发布任务</span><strong>{{ publishedCount }}</strong><small>发布后进入任务管理启动执行</small></article>
            <article><span>运行中</span><strong>{{ runningCount }}</strong><small>运行中画布只读，停止后可编辑</small></article>
          </div>
          <section class="panel">
          <div class="section-title">
            <div><h2>创建或选择任务进行编排</h2><p>先新建草稿任务，再配置参数、打开画布并发布到任务管理。</p></div>
            <button v-if="canWrite" class="btn btn-primary" @click="openCreateWorkflow">+ 新建任务</button>
          </div>
          <section class="filter-bar">
            <div class="filter-fields">
              <label v-if="role === 'SADMIN' || role === 'ADMIN'" class="filter-label">
                <span>创建者</span>
                <input v-model="filterUsername" type="text" placeholder="输入用户名搜索" @keyup.enter="loadTasks" />
              </label>
              <label class="filter-label">
                <span>开始日期</span>
                <input v-model="filterStartDate" type="date" />
              </label>
              <label class="filter-label">
                <span>结束日期</span>
                <input v-model="filterEndDate" type="date" />
              </label>
            </div>
            <div class="filter-actions">
              <button class="btn btn-primary btn-sm" @click="loadTasks">筛选</button>
              <button class="btn btn-ghost btn-sm" @click="resetFilters">重置</button>
            </div>
          </section>
          <TaskTable :tasks="workflowTasks" mode="workflow" :current-user="currentUser" :status-text="statusText" :format-date="formatDate" :can-write="canWrite" :can-execute="canExecute" :can-operate-task="canOperateTask" @view="viewEditor" @edit="openTaskEdit" @publish="publishTask" @withdraw="withdrawTask" @start="requestStartTask" @stop="stopTask" @delete="deleteTask" @runs="showRuns" />
          </section>
        </section>
      </template>

      <template v-else-if="activePage === '持续处理'">
        <section class="toolbar">
          <div>
            <h2>持续处理监控</h2>
            <p>展示自动扫描任务、下次运行、执行锁、失败重试和自动运行记录。</p>
          </div>
        </section>
        <section class="workspace-metrics continuous-metrics">
          <article v-for="card in continuousCards" :key="card.label">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.hint }}</small>
          </article>
        </section>
        <section class="panel">
          <div class="section-title">
            <div>
              <h2>持续任务状态</h2>
              <p>只有“持续处理 + 已发布 + 到达 nextRunAt + 未锁定 + 未超过重试上限”的任务会被自动扫描执行。</p>
            </div>
          </div>
          <div v-if="!continuousTasks.length" class="empty-state empty">
            暂无持续处理任务
            <span>在新建或编辑任务时选择“持续处理”，发布后即可进入扫描范围。</span>
          </div>
          <div v-else class="continuous-task-list">
            <article v-for="task in continuousTasks" :key="task.id" class="continuous-task-card">
              <div>
                <b>{{ task.name }}</b>
                <small>#{{ task.id }} · {{ task.sourceType }} → {{ task.sinkType }}</small>
              </div>
              <span :class="['status', String(task.status).toLowerCase()]">{{ statusText[task.status] || task.status }}</span>
              <span class="chip chip-orange">{{ task.continuousRunning ? '执行中' : '等待扫描' }}</span>
              <span>周期：{{ task.scheduleType || 'MANUAL' }} {{ task.scheduleExpression || '' }}</span>
              <span>下次：{{ formatDate(task.nextRunAt) }}</span>
              <span>最近：{{ formatDate(task.lastRunAt) }}</span>
              <span>重试：{{ task.retryCount || 0 }}/{{ task.maxRetryCount || 3 }}</span>
              <span v-if="task.incrementalMode && task.incrementalMode !== 'NONE'">增量：{{ task.incrementalMode }}</span>
              <span v-if="task.incrementalMode === 'STATUS'">状态：{{ task.processedField }}={{ task.unprocessedValue }}</span>
              <span v-if="task.incrementalMode === 'CURSOR'">游标：{{ task.cursorField }}>{{ task.lastCursorValue || '起点' }}</span>
              <span v-if="task.dedupKeys">去重：{{ task.dedupKeys }}</span>
              <span v-if="task.lastError" class="task-error-hint">错误：{{ task.lastError }}</span>
              <div class="continuous-task-actions">
                <button v-if="task.status === 'PUBLISHED'" class="btn btn-ghost btn-sm" :disabled="continuousActionId === task.id || task.continuousRunning" @click="pauseContinuousTask(task)">
                  {{ continuousActionId === task.id ? '处理中...' : '暂停' }}
                </button>
                <button v-if="task.status === 'STOPPED' || task.retryCount >= task.maxRetryCount" class="btn btn-primary btn-sm" :disabled="continuousActionId === task.id" @click="resumeContinuousTask(task)">
                  {{ continuousActionId === task.id ? '处理中...' : '恢复' }}
                </button>
                <button v-if="task.lastError || task.retryCount > 0" class="btn btn-danger-soft btn-sm" :disabled="continuousActionId === task.id || task.continuousRunning" @click="resetContinuousRetry(task)">
                  重置重试并恢复
                </button>
                <button class="btn btn-primary btn-sm" :disabled="continuousActionId === task.id || task.continuousRunning" @click="runContinuousNow(task)">
                  立即执行一次
                </button>
                <button class="btn btn-ghost btn-sm" @click="showRuns(task)">运行记录</button>
              </div>
            </article>
          </div>
        </section>
        <section class="panel">
          <div class="section-title">
            <div>
              <h2>最近自动运行记录</h2>
              <p>持续处理扫描器触发的任务会在这里留下记录。</p>
            </div>
          </div>
          <div v-if="!continuousAutoRuns.length" class="empty-state empty">暂无自动运行记录<span>发布到点的持续任务后，扫描器会自动生成记录。</span></div>
          <div v-else class="continuous-run-list">
            <article v-for="run in continuousAutoRuns" :key="run.id" class="run-item">
              <div class="run-summary">
                <div><b>{{ run.taskName }}</b><small>#{{ run.id }} · {{ formatDate(run.startedAt) }}</small></div>
                <span :class="['status', String(run.status).toLowerCase()]">{{ runStatusText(run.status) }}</span>
              </div>
              <p>读取 {{ run.recordsRead || 0 }} 条，写入 {{ run.recordsWritten || 0 }} 条 · 重试 {{ run.retryAttempt || 1 }} 次 · 下次 {{ formatDate(run.nextRunAt) }}</p>
              <p v-if="run.incrementalMode && run.incrementalMode !== 'NONE'">
                增量：扫描 {{ run.incrementalInputCount || 0 }} 条，写入 {{ run.incrementalWrittenCount || 0 }} 条，跳过重复 {{ run.duplicateSkippedCount || 0 }} 条，标记完成 {{ run.processedMarkedCount || 0 }} 条
              </p>
              <p v-if="run.errorMessage" class="run-error">{{ errorSummary(run.errorMessage) }}</p>
              <button class="btn btn-ghost btn-sm" @click="openRunDetail(run)">查看详情</button>
            </article>
          </div>
        </section>
      </template>

      <template v-else-if="activePage === '数据工作区'">
        <section class="workspace-metrics">
          <article><span>文件总数</span><strong>{{ files.length }}</strong><small>已导入数据资产</small></article>
          <article><span>筛选命中</span><strong>{{ filteredFiles.length }}</strong><small>当前检索结果</small></article>
          <article><span>总记录数</span><strong>{{ totalFileRecords.toLocaleString() }}</strong><small>可用于任务执行</small></article>
          <article><span>数据库数据源</span><strong>{{ outputDataSources.length }}</strong><small>可作为画布输入或输出</small></article>
        </section>
        <section class="drop-zone data-orbit-upload" :class="{ uploading, 'palette-disabled': !canWrite }" @dragover.prevent @drop.prevent="onDrop">
          <input ref="fileInput" type="file" accept=".json,.csv,.xlsx,.xls,.xml,.kafka,.ndjson,.jsonl" @change="chooseFile">
          <div class="upload-copy">
            <p class="eyebrow">DATA INGESTION</p>
            <h2>{{ canWrite ? uploading ? '正在解析并写入数据库...' : '拖入文件，生成可编排数据资产' : '只读账号不能上传文件' }}</h2>
            <p>{{ canWrite ? '支持 JSON / CSV / XLSX / XLS / XML / Kafka、NDJSON / JSONL。上传后会自动解析、入库、生成预览，并可作为任务执行输入。' : '请使用管理员或普通用户账号上传文件。' }}</p>
            <div v-if="uploading" class="progress"><span :style="{ width: `${uploadProgress}%` }"></span></div>
            <div class="upload-actions">
              <button v-if="canWrite" type="button" class="btn btn-primary" :disabled="uploading" @click.stop="openFilePicker">选择文件</button>
              <span>也可以直接拖拽到此区域</span>
            </div>
          </div>
          <div class="upload-orbit" @click.stop="openFilePicker">
            <div class="orbit-ring orbit-ring-1"></div>
            <div class="orbit-ring orbit-ring-2"></div>
            <div class="orbit-ring orbit-ring-3"></div>
            <div class="orbit-core">
              <strong>{{ files.length }}</strong>
              <span>已导入数据</span>
            </div>
            <span class="orbit-dot dot-json">JSON</span>
            <span class="orbit-dot dot-csv">CSV</span>
            <span class="orbit-dot dot-xlsx">XLSX</span>
            <span class="orbit-dot dot-xls">XLS</span>
            <span class="orbit-dot dot-xml">XML</span>
            <span class="orbit-dot dot-ndjson">NDJSON</span>
          </div>
        </section>
        <section class="panel workspace-database-source">
          <div class="workspace-database-copy">
            <p class="eyebrow">DATABASE INPUT</p>
            <h2>连接你自己的数据库</h2>
            <p>数据源连接由服务端加密保存，可在画布输入、输出节点中复用；密码不会写入画布 JSON。</p>
          </div>
          <div class="workspace-database-actions">
            <span class="workspace-database-count">{{ outputDataSources.length }} 条连接</span>
            <span class="workspace-database-support">MySQL · PostgreSQL · Oracle · SQL Server · OceanBase</span>
            <button type="button" class="btn btn-primary" :disabled="!canWrite" @click="openOutputDataSourceManager()">
              {{ canWrite ? '＋ 新建连接' : '只读账号不能新建' }}
            </button>
          </div>
          <div class="workspace-database-list">
            <div v-if="outputDataSourcesLoading" class="workspace-database-empty">正在加载数据源...</div>
            <div v-else-if="!outputDataSources.length" class="workspace-database-empty">
              <b>还没有数据库连接</b>
              <span>创建并测试连接后，可直接拖入画布作为输入或输出。</span>
            </div>
            <div v-else class="workspace-database-items">
              <button
                v-for="source in outputDataSources"
                :key="source.id"
                type="button"
                class="workspace-database-card"
                @click="openOutputDataSourceManager(String(source.id))"
              >
                <span class="workspace-database-mark">{{ source.dbType.slice(0, 2).toUpperCase() }}</span>
                <span>
                  <b>{{ source.name }}</b>
                  <small>{{ source.dbType }} · {{ source.url }}</small>
                  <em :class="`workspace-source-${source.testStatus || 'untested'}`">
                    {{ source.testStatus === 'success' ? '连接正常' : (source.testStatus === 'failure' ? '连接失败' : '未测试') }}
                  </em>
                </span>
              </button>
            </div>
          </div>
        </section>
        <section class="panel">
          <div class="section-title">
            <div><h2>已导入文件</h2><p>展示文件类型、记录数、大小与 JSON 预览。</p></div>
            <span class="tag">{{ filteredFiles.length }}/{{ files.length }} 个文件</span>
          </div>
          <div class="filter-bar" style="margin-top:12px;">
            <input v-model="fileSearchKeyword" type="text" placeholder="搜索文件名或上传者..." class="filter-input" />
          </div>
          <div class="table-wrap">
            <table class="data-table">
              <thead><tr><th>文件名</th><th>类型</th><th>上传者</th><th>记录数</th><th>大小</th><th>导入时间</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-if="!files.length"><td colspan="7" class="empty"><div class="empty-state"><b>尚未导入文件</b><span>上传后这里会显示文件元数据。</span></div></td></tr>
                <tr v-for="file in pagedFiles" :key="file.id">
                  <td><b>{{ file.originalName }}</b><small>数据库 ID: {{ file.id }}</small></td>
                  <td><span class="chip chip-blue">{{ file.fileType }}</span></td>
                  <td>{{ file.uploaderUsername || '-' }}</td>
                  <td>{{ file.recordCount?.toLocaleString?.() || 0 }} 条</td>
                  <td>{{ formatSize(file.fileSize) }}</td>
                  <td>{{ formatDate(file.createdAt) }}</td>
                  <td>
                    <template v-if="editingFileId === file.id">
                      <input v-model="editingFileName" class="file-rename-input" @keyup.enter="confirmRenameFile" @keyup.escape="cancelRenameFile" />
                      <button class="btn btn-primary btn-sm" @click="confirmRenameFile">保存</button>
                      <button class="btn btn-ghost btn-sm" @click="cancelRenameFile">取消</button>
                    </template>
                    <template v-else>
                      <button class="btn btn-ghost" @click="openPreview(file)">预览</button>
                      <button v-if="canWrite" class="btn btn-ghost" @click="startRenameFile(file)">重命名</button>
                      <button v-if="canWrite" class="btn btn-danger btn-sm" @click="deleteFile(file)">删除</button>
                    </template>
                  </td>
                </tr>
                <tr v-for="n in (filePageSize - pagedFiles.length)" :key="'empty-' + n" class="empty-row">                   <td colspan="7">&nbsp;</td>                 </tr>
              </tbody>
            </table>
            <div v-if="fileTotalPages > 1" class="pagination">
              <button class="btn btn-ghost btn-sm" :disabled="filePage <= 1" @click="filePage--">&laquo; 上一页</button>
              <span class="page-info">{{ filePage }} / {{ fileTotalPages }}</span>
              <button class="btn btn-ghost btn-sm" :disabled="filePage >= fileTotalPages" @click="filePage++">下一页 &raquo;</button>
            </div>
          </div>
        </section>
        <OutputDataSourceManager
          :visible="outputDataSourceManagerVisible"
          :selected-id="selectedOutputDataSourceId"
          :readonly="!canWrite"
          @close="outputDataSourceManagerVisible = false; loadOutputDataSources()"
          @selected="selectedOutputDataSourceId = String($event)"
        />
      </template>

      <template v-else-if="activePage === 'Flink 作业'">
        <div class="flink-ops-page">
          <section class="panel flink-ops-hero">
            <div>
              <p class="section-kicker">STREAM PROCESSING CENTER</p>
              <h2>实时流处理作业监控与调度</h2>
              <p>围绕 A22 通用流处理任务，展示“输入源 → Kafka / JDBC → Flink 实时计算 → 外部数据库 → 运行记录”的运行链路。</p>
            </div>
            <div class="flink-hero-actions">
              <span :class="['flink-pill', flinkHealth?.available ? 'success' : 'danger']">{{ flinkHealth?.available ? '集群可用' : '集群不可用' }}</span>
            </div>
          </section>

          <section class="flink-overview-grid">
            <article v-for="card in flinkMetricCards" :key="card.label" :class="['flink-metric-card', card.tone]">
              <span>{{ card.label }}</span>
              <strong>{{ card.value }}</strong>
              <small>{{ card.desc }}</small>
            </article>
          </section>

          <section class="panel flink-pipeline-panel">
            <div class="section-head">
              <div>
                <h2>实时处理链路</h2>
                <p>各节点按当前实现标注；输入支持 Kafka、JDBC 和已导入文件，输出支持 Kafka 与外部 JDBC 数据库。</p>
              </div>
            </div>
            <div class="flink-pipeline">
              <template v-for="(node, index) in flinkPipelineNodes" :key="node.title">
                <div :class="['flink-pipeline-node', node.tone]">
                  <span class="flink-pipeline-icon">{{ index + 1 }}</span>
                  <strong>{{ node.title }}</strong>
                  <small>{{ node.sub }}</small>
                  <em>{{ node.status }}</em>
                </div>
              </template>
            </div>
          </section>

          <section class="flink-main-grid">
            <div class="panel flink-current-panel">
              <div class="section-head compact">
                <div>
                  <h2>当前提交作业</h2>
                  <p>支持作业提交、状态监测与运行结果追溯的闭环管理。</p>
                </div>
                <div class="btn-row">
                  <button class="btn btn-primary btn-sm" :disabled="!canUseFlink || flinkSubmitting" @click="submitFlinkJob">{{ flinkSubmitting ? '提交中...' : '提交测试作业' }}</button>
                  <button class="btn btn-danger-soft btn-sm" :disabled="!flinkJob?.jobId || !canUseFlink" @click="cancelFlinkJob">取消作业</button>
                </div>
              </div>
              <div v-if="flinkJob" class="flink-job-detail-grid">
                <div><span>当前任务</span><strong>{{ flinkJob.taskName || 'A22 Kafka → MySQL 测试作业' }}</strong></div>
                <div><span>Flink Job ID</span><code>{{ shortFlinkJobId(flinkJob.jobId) }}</code></div>
                <div><span>作业状态</span><strong :class="['flink-job-state-text', flinkJobStatusClass(flinkJob.status)]">{{ flinkJobStatusText(flinkJob.status) }}</strong></div>
                <div><span>并行度</span><strong>{{ flinkJob.parallelism || 1 }}</strong></div>
                <div><span>提交时间</span><strong>{{ formatDate(flinkJob.submittedAt) }}</strong></div>
                <div><span>运行时长</span><strong>{{ flinkCurrentJobRuntime }}</strong></div>
                <div class="wide"><span>失败原因摘要</span><strong>{{ flinkJob.errorSummary || flinkJob.error || flinkJob.message ? simplifyError(flinkJob.errorSummary || flinkJob.error || flinkJob.message, '暂无错误信息') : '暂无错误信息' }}</strong></div>
              </div>
              <div v-else class="flink-empty-state">
                <strong>暂无当前 Flink 作业</strong>
                <p>可从任务管理页启动已发布任务，或在此提交作业并查看实时状态。</p>
              </div>
            </div>

            <div class="panel flink-history-panel">
              <div class="section-head compact">
                <div>
                  <h2>作业历史</h2>
                  <p>记录近期提交的 Flink 作业，支持状态追踪与运行结果回溯。</p>
                </div>
              </div>
              <div v-if="flinkJobHistory.length" class="flink-history-table">
                <div class="flink-history-row head">
                  <span>任务名</span><span>Job ID</span><span>状态</span><span>提交时间</span><span>结束时间</span>
                </div>
                <div v-for="(h, i) in flinkJobHistory" :key="h.jobId || i" class="flink-history-row">
                  <span>{{ h.taskName || 'Flink 测试作业' }}</span>
                  <code>{{ shortFlinkJobId(h.jobId) }}</code>
                  <em :class="['flink-pill', flinkJobStatusClass(h.status)]">{{ flinkJobStatusText(h.status) }}</em>
                  <span>{{ formatDate(h.submittedAt) }}</span>
                  <span>{{ h.finishedAt ? formatDate(h.finishedAt) : '-' }}</span>
                </div>
              </div>
              <div v-else class="flink-empty-state">
                <strong>暂无提交历史</strong>
                <p>提交或启动任务后，这里会展示本次会话的 Flink 作业记录。</p>
              </div>
            </div>
          </section>

          <section class="panel flink-dashboard-panel flink-dashboard-compact">
            <div class="flink-dashboard-header">
              <div>
                <h2>Flink Dashboard 小窗口</h2>
                <p>实时 Flink 集群控制台，可在此查看作业状态与集群信息。</p>
              </div>
              <a :href="flinkDashboardUrl" target="_blank" class="btn btn-ghost">在新窗口打开 ↗</a>
            </div>
            <div class="flink-dashboard-body">
              <div v-if="!iframeLoaded" class="flink-skeleton"><span>Flink Dashboard 加载中...</span></div>
              <iframe :src="flinkDashboardUrl" style="border: none;" loading="lazy" :class="{ 'flink-skeleton-loaded': iframeLoaded }" @load="iframeLoaded = true"></iframe>
            </div>
          </section>
        </div>
      </template>
<template v-else-if="activePage === '运行日志'">
        <section class="toolbar">
          <div><h2>运行记录</h2><p>{{ selectedRunTask ? `当前筛选：${selectedRunTask.name}` : '展示读写数量、日志、结果表和导出文件。' }}</p></div>
          <div class="button-row">
            <button v-if="selectedRunTaskId" class="btn btn-ghost" @click="clearRunFilter">查看全部</button>
          </div>
        </section>
        <section class="filter-bar">
          <div class="filter-fields">
            <label class="filter-label">
              <span>任务名称</span>
              <input v-model="runFilterName" type="text" placeholder="输入任务名搜索" />
            </label>
            <label class="filter-label">
              <span>状态</span>
              <select v-model="runFilterStatus" class="filter-select">
                <option value="">全部状态</option>
                <option value="RUNNING">运行中</option>
                <option value="SUCCEEDED">成功</option>
                <option value="FAILED">失败</option>
              </select>
            </label>
            <label class="filter-label">
              <span>触发方式</span>
              <select v-model="runFilterTrigger" class="filter-select">
                <option value="">全部触发</option>
                <option value="MANUAL">手动执行</option>
                <option value="CONTINUOUS">持续处理</option>
              </select>
            </label>
            <label class="filter-label">
              <span>开始日期</span>
              <input v-model="runFilterStartDate" type="date" />
            </label>
            <label class="filter-label">
              <span>结束日期</span>
              <input v-model="runFilterEndDate" type="date" />
            </label>
          </div>
          <div class="filter-actions">
            <label class="filter-check"><input v-model="runFilterAlertOnly" type="checkbox" /> 只看异常</label>
            <button class="btn btn-ghost btn-sm" @click="resetRunFilters">重置</button>
          </div>
        </section>
        <section class="panel run-history">
          <div v-if="!filteredRuns.length" class="empty-state empty">暂无运行记录<span>启动任务后，这里会显示读写数量、日志和导出文件。</span></div>
          <article v-for="run in pagedRuns" :key="run.id" class="run-item">
            <div class="run-summary">
              <div><b>{{ run.taskName }}</b><small>#{{ run.id }} · {{ formatDate(run.startedAt) }}</small></div>
              <span :class="['status', String(run.status).toLowerCase()]">{{ runStatusText(run.status) }}</span>
              <span v-if="run.triggerType === 'CONTINUOUS'" class="chip chip-orange">持续处理</span>
              <span v-if="run.maxRetriesReached" class="chip chip-red">重试上限</span>
            </div>
            <p v-if="run.status === 'SUCCEEDED'">
              读取 {{ run.recordsRead }} 条，写入 {{ run.recordsWritten }} 条
              <span v-if="runTargetTables(run).length" class="run-table-actions">
                · 结果表：
                <button
                  v-for="table in runTargetTables(run)"
                  :key="`${run.id}-${table}`"
                  class="btn btn-ghost btn-sm"
                  type="button"
                  @click="openTablePreview(table)"
                >预览 {{ table }}</button>
              </span>
            </p>
            <p v-else-if="run.errorMessage" class="run-error">{{ errorSummary(run.errorMessage) }}</p>
            <div v-for="log in runLogs(run)" :key="log" class="run-log">[INFO] {{ log }}</div>
            <p v-if="runExportFiles(run).length" class="run-exports">
              <button
                v-for="file in runExportFiles(run)"
                :key="file.fileName"
                type="button"
                class="action export-link"
                @click="downloadExportFile(file.fileName)"
              >下载 {{ file.format }}：{{ file.fileName }}</button>
            </p>
            <div class="run-actions">
              <button class="btn btn-ghost btn-sm" @click="openRunDetail(run)">查看详情</button>
              <button
                v-for="table in runTargetTables(run)"
                :key="`${run.id}-preview-${table}`"
                class="btn btn-ghost btn-sm"
                type="button"
                @click="openTablePreview(table)"
              >预览 {{ table }}</button>
            </div>
          </article>
        

        <div v-if="runTotalPages > 1" class="pagination">
          <button class="btn btn-ghost btn-sm" :disabled="runPage <= 1" @click="runPage--">&laquo; 上一页</button>
          <span class="page-info">{{ runPage }} / {{ runTotalPages }}</span>
          <button class="btn btn-ghost btn-sm" :disabled="runPage >= runTotalPages" @click="runPage++">下一页 &raquo;</button>
        </div>
</section>
      </template>

      <template v-else-if="activePage === '用户管理'">
        <UserManage />
      </template>
      </div>
    </main>

    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <form class="modal" @submit.prevent="createTask">
        <div class="section-title"><h2>新建任务</h2><button type="button" class="link" @click="showCreate = false">关闭</button></div>
        <label>任务名称<input v-model="form.name" placeholder="例如：交易流水人工验证" autofocus></label>
        <label>数据源<select v-model="form.sourceType"><option>JSON</option><option>CSV</option><option>Excel</option><option value="JDBC">数据库输入</option><option>Kafka</option></select></label>
        <label>输出目标<select v-model="form.sinkType"><option>MySQL</option><option>Kafka</option><option>Redis</option><option>CSV</option><option>Excel</option></select></label>
        <label>处理算子（逗号分隔）<input :value="form.transforms.join(',')" @input="form.transforms = $event.target.value.split(',').map(item => item.trim())"></label>
        <label>运行模式<select v-model="form.runMode"><option value="ONCE">一次性执行</option><option value="CONTINUOUS">持续处理</option></select></label>
        <label v-if="form.runMode === 'CONTINUOUS'">处理周期<select v-model="form.scheduleType"><option value="FIXED_RATE">固定间隔</option><option value="DAILY">每日定时</option><option value="CRON">Cron 表达式</option></select></label>
        <label v-if="form.runMode === 'CONTINUOUS'">周期表达式<input v-model="form.scheduleExpression" placeholder="例如：30m、08:30 或 0 0/30 * * * ?"></label>
        <label v-if="form.runMode === 'CONTINUOUS'">持续处理说明<input v-model="form.continuousRemark" placeholder="例如：每 30 分钟扫描未处理订单"></label>
        <label v-if="form.runMode === 'CONTINUOUS'">增量模式<select v-model="form.incrementalMode"><option value="NONE">不启用增量</option><option value="STATUS">按状态字段</option><option value="CURSOR">按游标字段</option></select></label>
        <label v-if="form.runMode === 'CONTINUOUS' && form.incrementalMode === 'STATUS'">状态字段<input v-model="form.processedField" placeholder="process_status"></label>
        <label v-if="form.runMode === 'CONTINUOUS' && form.incrementalMode === 'STATUS'">未处理 / 已处理<input v-model="form.unprocessedValue" placeholder="UNPROCESSED"><input v-model="form.processedValue" placeholder="DONE"></label>
        <label v-if="form.runMode === 'CONTINUOUS' && form.incrementalMode === 'CURSOR'">游标字段<input v-model="form.cursorField" placeholder="id / created_at / updated_at"></label>
        <label v-if="form.runMode === 'CONTINUOUS' && form.incrementalMode !== 'NONE'">输出去重字段<input v-model="form.dedupKeys" placeholder="例如：order_id,customer_id"></label>
        <button class="btn btn-primary submit">创建草稿任务</button>
      </form>
    </div>

    <div v-if="showEditTask" class="modal-mask" @click.self="showEditTask = false">
      <form class="modal" @submit.prevent="saveTaskBasicInfo">
        <div class="section-title"><h2>编辑任务</h2><button type="button" class="link" @click="showEditTask = false">关闭</button></div>
        <label>任务名称<input v-model="editForm.name"></label>
        <label>数据源<select v-model="editForm.sourceType"><option>JSON</option><option>CSV</option><option>Excel</option><option value="JDBC">数据库输入</option><option>Kafka</option></select></label>
        <label>输出目标<select v-model="editForm.sinkType"><option>MySQL</option><option>Kafka</option><option>Redis</option><option>CSV</option><option>Excel</option></select></label>
        <label>处理算子（逗号分隔）<input :value="editForm.transforms.join(',')" @input="editForm.transforms = $event.target.value.split(',').map(item => item.trim())"></label>
        <label>运行模式<select v-model="editForm.runMode"><option value="ONCE">一次性执行</option><option value="CONTINUOUS">持续处理</option></select></label>
        <label v-if="editForm.runMode === 'CONTINUOUS'">处理周期<select v-model="editForm.scheduleType"><option value="FIXED_RATE">固定间隔</option><option value="DAILY">每日定时</option><option value="CRON">Cron 表达式</option></select></label>
        <label v-if="editForm.runMode === 'CONTINUOUS'">周期表达式<input v-model="editForm.scheduleExpression" placeholder="例如：30m、08:30 或 0 0/30 * * * ?"></label>
        <label v-if="editForm.runMode === 'CONTINUOUS'">持续处理说明<input v-model="editForm.continuousRemark" placeholder="例如：每 30 分钟扫描未处理订单"></label>
        <label v-if="editForm.runMode === 'CONTINUOUS'">增量模式<select v-model="editForm.incrementalMode"><option value="NONE">不启用增量</option><option value="STATUS">按状态字段</option><option value="CURSOR">按游标字段</option></select></label>
        <label v-if="editForm.runMode === 'CONTINUOUS' && editForm.incrementalMode === 'STATUS'">状态字段<input v-model="editForm.processedField" placeholder="process_status"></label>
        <label v-if="editForm.runMode === 'CONTINUOUS' && editForm.incrementalMode === 'STATUS'">未处理 / 已处理<input v-model="editForm.unprocessedValue" placeholder="UNPROCESSED"><input v-model="editForm.processedValue" placeholder="DONE"></label>
        <label v-if="editForm.runMode === 'CONTINUOUS' && editForm.incrementalMode === 'CURSOR'">游标字段<input v-model="editForm.cursorField" placeholder="id / created_at / updated_at"></label>
        <label v-if="editForm.runMode === 'CONTINUOUS' && editForm.incrementalMode !== 'NONE'">输出去重字段<input v-model="editForm.dedupKeys" placeholder="例如：order_id,customer_id"></label>
        <div class="config-actions">
          <button type="button" class="btn btn-ghost" @click="openEditor()">打开画布</button>
          <button type="submit" class="btn btn-primary">保存参数</button>
        </div>
      </form>
    </div>

    <div v-if="pendingExecuteTask" class="modal-mask" @click.self="cancelStartWithFile">
      <section class="modal">
        <div class="section-title">
          <div>
            <h2>选择本次执行输入文件</h2>
            <p>任务：{{ pendingExecuteTask.name }}。请选择一个已上传文件，系统会调用 execute?sourceFileId=文件ID。</p>
          </div>
          <button type="button" class="link" @click="cancelStartWithFile">关闭</button>
        </div>

        <div v-if="!files.length" class="empty-state empty">
          <b>暂无可用文件</b>
          <span>请先到“数据工作区”上传 JSON、CSV、Excel 或 XML 文件。</span>
        </div>

        <label v-else>输入文件
          <select v-model="selectedSourceFileId" class="filter-select" style="width:100%;font-size:13px;">
            <option v-for="file in files" :key="file.id" :value="String(file.id)">
              #{{ file.id }} · {{ file.originalName }} · {{ file.fileType }} · {{ file.recordCount }} 条
            </option>
          </select>
        </label>

        <div v-if="selectedSourceFile" class="selected-file-card">
          <b>{{ selectedSourceFile.originalName }}</b>
          <span>{{ selectedSourceFile.fileType }} · {{ selectedSourceFile.recordCount }} 条 · {{ formatSize(selectedSourceFile.fileSize) }}</span>
          <small>导入时间：{{ formatDate(selectedSourceFile.createdAt) }}</small>
        </div>

        <div class="config-actions">
          <button type="button" class="btn btn-ghost" @click="cancelStartWithFile">取消</button>
          <button type="button" class="btn btn-primary" :disabled="!files.length || executingTaskId === pendingExecuteTask.id" @click="confirmStartWithFile">
            {{ executingTaskId === pendingExecuteTask.id ? '执行中...' : '确认启动' }}
          </button>
        </div>
      </section>
    </div>

    <div v-if="selectedRunDetail" class="modal-mask" @click.self="closeRunDetail">
      <section class="modal run-detail-modal">
        <div class="section-title">
          <div>
            <h2>运行记录详情</h2>
            <p>{{ selectedRunDetail.taskName || '未命名任务' }} · #{{ selectedRunDetail.id }}</p>
          </div>
          <button class="link" @click="closeRunDetail">关闭</button>
        </div>
        <div class="run-detail-grid">
          <article><span>任务名称</span><b>{{ selectedRunDetail.taskName || '-' }}</b></article>
          <article><span>执行人</span><b>{{ runExecutor(selectedRunDetail) }}</b></article>
          <article><span>触发方式</span><b>{{ selectedRunDetail.triggerType === 'CONTINUOUS' ? '持续处理自动触发' : '手动触发' }}</b></article>
          <article v-if="selectedRunDetail.triggerType === 'CONTINUOUS'"><span>重试次数</span><b>{{ selectedRunDetail.retryAttempt || 1 }} 次</b></article>
          <article v-if="selectedRunDetail.triggerType === 'CONTINUOUS'"><span>下次运行</span><b>{{ formatDate(selectedRunDetail.nextRunAt) }}</b></article>
          <article v-if="selectedRunDetail.maxRetriesReached"><span>重试状态</span><b>已达到最大重试次数</b></article>
          <article v-if="selectedRunDetail.incrementalMode && selectedRunDetail.incrementalMode !== 'NONE'"><span>增量模式</span><b>{{ selectedRunDetail.incrementalMode }}</b></article>
          <article v-if="selectedRunDetail.incrementalMode && selectedRunDetail.incrementalMode !== 'NONE'"><span>本次扫描</span><b>{{ selectedRunDetail.incrementalInputCount || 0 }} 条</b></article>
          <article v-if="selectedRunDetail.incrementalMode && selectedRunDetail.incrementalMode !== 'NONE'"><span>新增写入</span><b>{{ selectedRunDetail.incrementalWrittenCount || 0 }} 条</b></article>
          <article v-if="selectedRunDetail.incrementalMode && selectedRunDetail.incrementalMode !== 'NONE'"><span>跳过重复</span><b>{{ selectedRunDetail.duplicateSkippedCount || 0 }} 条</b></article>
          <article v-if="selectedRunDetail.incrementalMode && selectedRunDetail.incrementalMode !== 'NONE'"><span>标记完成</span><b>{{ selectedRunDetail.processedMarkedCount || 0 }} 条</b></article>
          <article v-if="selectedRunDetail.cursorField"><span>游标变化</span><b>{{ selectedRunDetail.cursorField }}：{{ selectedRunDetail.lastCursorBefore || '起点' }} → {{ selectedRunDetail.lastCursorAfter || '-' }}</b></article>
          <article><span>输入文件</span><b>{{ runInputFile(selectedRunDetail) }}</b></article>
          <article><span>状态</span><b :class="['status', String(selectedRunDetail.status).toLowerCase()]">{{ runStatusText(selectedRunDetail.status) }}</b></article>
          <article><span>开始时间</span><b>{{ formatDate(selectedRunDetail.startedAt) }}</b></article>
          <article><span>结束时间</span><b>{{ formatDate(selectedRunDetail.finishedAt) }}</b></article>
          <article><span>耗时</span><b>{{ runDuration(selectedRunDetail) }}</b></article>
          <article><span>结果表</span><b>{{ runTargetTables(selectedRunDetail).join(', ') || '-' }}</b></article>
          <article><span>读取数量</span><b>{{ selectedRunDetail.recordsRead ?? 0 }} 条</b></article>
          <article><span>写入数量</span><b>{{ selectedRunDetail.recordsWritten ?? 0 }} 条</b></article>
        </div>
        <div v-if="hasDagBranchResults(selectedRunDetail)" class="run-detail-block dag-run-block">
          <div class="run-detail-block-title">
            <b>DAG 分支执行结果</b>
            <span>结构化展示每条分支和输出节点的输入/输出数量。</span>
          </div>
          <div class="dag-result-grid">
            <article v-for="item in dagBranchResults(selectedRunDetail)" :key="item.raw" :class="`dag-result-${item.type}`">
              <span>{{ item.desc }}</span>
              <b>{{ item.title }}</b>
              <small v-if="item.type === 'filter'">符合 {{ item.pass }} 条 / 不符合 {{ item.reject }} 条</small>
              <small v-else-if="item.inputCount !== undefined">输入 {{ item.inputCount }} 条 / 输出 {{ item.outputCount }} 条</small>
              <small v-else>{{ item.count }} 条</small>
              <em v-if="item.conditionSummary">条件：{{ item.conditionSummary }}</em>
              <em v-if="item.ruleSummary">规则：{{ item.ruleSummary }}</em>
              <em v-if="item.outputLocation">位置：{{ item.outputLocation }}</em>
              <em v-if="item.targetTable">结果表：{{ item.targetTable }}</em>
              <em v-if="item.exportFileName">
                导出文件：{{ item.exportFileName }}
                <button type="button" class="btn btn-ghost btn-sm" @click.stop="downloadExportFile(item.exportFileName)">下载</button>
              </em>
              <em v-if="item.path">链路：{{ item.path }}</em>
            </article>
          </div>
        </div>
        <div v-if="runNodeResults(selectedRunDetail).length" class="run-detail-block dag-run-block">
          <div class="run-detail-block-title">
            <b>节点输入快照</b>
            <span>保存每个节点实际收到的前 5 条样例，用于单节点回放和问题定位。</span>
          </div>
          <div class="dag-result-grid">
            <article v-for="node in runNodeResults(selectedRunDetail)" :key="`${selectedRunDetail.id}-${node.nodeId}`" class="dag-result-filter">
              <span>{{ node.nodeType }} · {{ node.kind || '-' }}</span>
              <b>{{ node.nodeLabel || node.nodeId }}</b>
              <small>输入 {{ node.inputCount || 0 }} 条 / 输出 {{ node.outputCount || 0 }} 条 / 快照 {{ node.inputPreview?.length || 0 }} 条</small>
              <em v-if="node.ruleSummary">规则：{{ node.ruleSummary }}</em>
              <em v-if="node.branchName">链路：{{ node.branchName }}</em>
              <button v-if="node.nodeType === 'transform'" type="button" class="btn btn-ghost btn-sm" @click="replayRunNodeSnapshot(selectedRunDetail, node)">带入单测</button>
            </article>
          </div>
        </div>
        <div v-if="runDiagnosticEvidence(selectedRunDetail).hasEvidence" class="run-ai-toolbar">
          <div>
            <b>错误原因分析</b>
            <span>{{ runAnalysisHint(selectedRunDetail) }}</span>
          </div>
          <button
            v-if="canUseAiForRunError(selectedRunDetail)"
            type="button"
            class="btn btn-ai"
            :disabled="runAiLoading"
            @click="analyzeRunWithAi(selectedRunDetail)"
          >{{ runAiLoading ? 'AI 分析中...' : 'AI 深入分析复杂日志' }}</button>
        </div>
        <div v-if="selectedRunAiAnalysis" class="ai-advice-card">
          <div class="ai-advice-header">
            <div>
              <span>{{ selectedRunAiAnalysis.sourceLabel }}</span>
              <h3>{{ selectedRunAiAnalysis.summary }}</h3>
            </div>
            <em :class="['ai-risk', selectedRunAiAnalysis.riskClass]">
              {{ selectedRunAiAnalysis.riskLevel === '待判断' ? '证据不足' : `${selectedRunAiAnalysis.riskLevel}风险` }}
            </em>
          </div>
          <div class="ai-advice-meta">
            <span>分析来源：{{ selectedRunAiAnalysis.analysisSource }}</span>
            <span>操作权限：仅建议，不执行</span>
            <span>处理提示：{{ selectedRunAiAnalysis.needHuman }}</span>
            <span>生成时间：{{ selectedRunAiAnalysis.generatedAt }}</span>
          </div>
          <div class="ai-advice-columns">
            <section>
              <b>可能原因</b>
              <ul>
                <li v-for="cause in selectedRunAiAnalysis.causes" :key="cause">{{ cause }}</li>
              </ul>
            </section>
            <section>
              <b>处理建议</b>
              <ol>
                <li v-for="suggestion in selectedRunAiAnalysis.suggestions" :key="suggestion">{{ suggestion }}</li>
              </ol>
            </section>
          </div>
          <div v-if="selectedRunAiAnalysis.rawText" class="ai-advice-note"><details><summary>查看 AI 完整回复</summary><pre style="white-space:pre-wrap;font-size:12px;max-height:300px;overflow:auto;margin-top:8px;">{{ selectedRunAiAnalysis.rawText }}</pre></details></div>
          <p v-else class="ai-advice-note">由 {{ selectedRunAiAnalysis.analysisSource }} 生成，仅供参考。</p>
        </div>
        <div v-if="selectedRunDetail.errorMessage" class="run-detail-block">
          <div class="run-detail-block-title">
            <b>错误摘要</b>
            <span>{{ errorSummary(selectedRunDetail.errorMessage) }}</span>
          </div>
          <div v-if="selectedRunDetail.failureNodeLabel || selectedRunDetail.failureNodeId" class="run-failure-node">
            失败节点：{{ selectedRunDetail.failureNodeLabel || selectedRunDetail.failureNodeId }}
            <span v-if="selectedRunDetail.failureNodeType">（{{ selectedRunDetail.failureNodeType }}）</span>
          </div>
          <div v-if="runFailureContext(selectedRunDetail).canLocate" class="failure-context-card">
            <div>
              <b>节点定位</b>
              <span>{{ nodeBrief(runFailureContext(selectedRunDetail).node) }}</span>
            </div>
            <p v-if="runFailureContext(selectedRunDetail).config">节点配置：{{ runFailureContext(selectedRunDetail).config }}</p>
            <p>上游节点：{{ runFailureContext(selectedRunDetail).upstream.map(nodeBrief).join('、') || '无' }}</p>
            <p>直接下游：{{ runFailureContext(selectedRunDetail).downstream.map(nodeBrief).join('、') || '无' }}</p>
            <p>影响输出：{{ runFailureContext(selectedRunDetail).affectedSinks.map(nodeBrief).join('、') || '暂未影响到输出节点' }}</p>
            <button type="button" class="btn btn-primary btn-sm" @click="locateRunFailureNode(selectedRunDetail)">定位到画布节点</button>
            <button type="button" class="btn btn-ai btn-sm" :disabled="replayLoadingRunId === selectedRunDetail.id" @click="replayRunFailureNode(selectedRunDetail)">
              {{ replayLoadingRunId === selectedRunDetail.id ? '带入中...' : '带入节点单测' }}
            </button>
          </div>
          <details class="technical-details">
            <summary>查看技术详情</summary>
            <pre>{{ selectedRunDetail.errorMessage }}</pre>
          </details>
        </div>
        <div v-if="runLogs(selectedRunDetail).length" class="run-detail-block">
          <b>运行日志</b>
          <div v-for="log in runLogs(selectedRunDetail)" :key="log" class="run-log">[INFO] {{ log }}</div>
        </div>
        <div class="config-actions">
          <button
            v-for="table in runTargetTables(selectedRunDetail)"
            :key="`detail-preview-${table}`"
            type="button"
            class="btn btn-primary"
            @click="openTablePreview(table)"
          >预览 {{ table }}</button>
          <button type="button" class="btn btn-ghost" @click="closeRunDetail">关闭</button>
        </div>
      </section>
    </div>

    <div v-if="preview" class="modal-mask" @click.self="preview = null">
      <section class="modal preview">
        <div class="section-title">
          <div><h2>{{ preview.originalName }}</h2><p>{{ preview.recordCount }} 条记录 · 已从 MySQL 读取</p></div>
          <button class="link" @click="preview = null">关闭</button>
        </div>
        <p class="preview-hint preview-summary">共 {{ previewRecords.length }} 条记录，使用鼠标滚轮或拖动滚动条上下查看全部内容。</p>
        <pre>{{ JSON.stringify(previewRecords, null, 2) }}</pre>
      </section>
    </div>

    <div v-if="tablePreview" class="modal-mask" @click.self="tablePreview = null">
      <section class="modal preview">
        <div class="section-title">
          <div><h2>结果表：{{ tablePreview.tableName }}</h2><p>从 MySQL 读取前 {{ tablePreview.rows.length }} 条记录</p></div>
          <button class="link" @click="tablePreview = null">关闭</button>
        </div>
        <pre>{{ JSON.stringify(tablePreview.rows, null, 2) }}</pre>
      </section>
    </div>

    <!-- 个人信息弹窗 -->
    <div v-if="showProfile" class="modal-mask" @click.self="showProfile = false">
      <section class="modal profile-modal">
        <div class="section-title">
          <h2>个人信息</h2>
          <button type="button" class="link" @click="showProfile = false">关闭</button>
        </div>
        <div class="profile-header">
          <div class="profile-avatar">
            <img v-if="currentUser.avatarUrl" :src="currentUser.avatarUrl" alt="用户头像" />
            <span v-else>{{ avatarInitial() }}</span>
          </div>
          <div class="profile-info">
            <b>{{ currentUser.displayName || currentUser.username }}</b>
            <span class="chip">{{ roleText[currentUser.role] }}</span>
            <input ref="avatarInput" class="avatar-input" type="file" accept="image/*" @change="chooseAvatar" />
            <div class="avatar-actions">
              <button type="button" class="btn btn-primary btn-sm" @click="openAvatarPicker">上传头像</button>
              <button v-if="currentUser.avatarUrl" type="button" class="btn btn-ghost btn-sm" @click="removeAvatar">移除头像</button>
            </div>
          </div>
        </div>
        <div class="profile-details">
          <div class="profile-row"><span>登录账号</span>
            <template v-if="editingUsername">
              <input v-model="newUsername" class="file-rename-input" @keyup.enter="confirmEditUsername" @keyup.escape="cancelEditUsername" />
              <button class="btn btn-primary btn-sm" @click="confirmEditUsername">保存</button>
              <button class="btn btn-ghost btn-sm" @click="cancelEditUsername">取消</button>
            </template>
            <template v-else>
              <b>{{ currentUser.username }}</b>
              <button class="btn btn-ghost btn-sm" @click="startEditUsername">修改</button>
            </template>
          </div>
          <div class="profile-row"><span>显示名称（昵称）</span>
            <template v-if="editingDisplayName">
              <input v-model="newDisplayName" class="file-rename-input" @keyup.enter="confirmEditDisplayName" @keyup.escape="cancelEditDisplayName" />
              <button class="btn btn-primary btn-sm" @click="confirmEditDisplayName">保存</button>
              <button class="btn btn-ghost btn-sm" @click="cancelEditDisplayName">取消</button>
            </template>
            <template v-else>
              <b>{{ currentUser.displayName || currentUser.username }}</b>
              <button class="btn btn-ghost btn-sm" @click="startEditDisplayName">修改</button>
            </template>
          </div>
          <div class="profile-row"><span>角色</span><b>{{ roleText[currentUser.role] }}</b></div>
        </div>
        <hr class="profile-divider" />
        <h3 class="profile-section-title">修改密码</h3>
        <form @submit.prevent="submitChangePassword">
          <label><span>原密码</span><input v-model="changePasswordForm.oldPassword" type="password" placeholder="请输入当前密码" autocomplete="current-password" /></label>
          <label><span>新密码</span><input v-model="changePasswordForm.newPassword" type="password" placeholder="至少 8 位" autocomplete="new-password" /></label>
          <label><span>确认新密码</span><input v-model="changePasswordForm.confirmPassword" type="password" placeholder="请再次输入新密码" autocomplete="new-password" /></label>
          <div class="config-actions" style="margin-top: 12px">
            <span></span>
            <button type="submit" class="btn btn-primary">确认修改</button>
          </div>
        </form>
      </section>
    </div>
  </div>
</template>







