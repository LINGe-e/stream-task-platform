const TOKEN_KEY = 'a22_auth_token'
const USER_KEY = 'a22_auth_user'

let authExpiredHandler = null

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getStoredUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export function saveSession(auth) {
  localStorage.setItem(TOKEN_KEY, auth.token)
  localStorage.setItem(USER_KEY, JSON.stringify(auth.user))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem('a22_current_user')
  localStorage.removeItem('a22_users')
}

export function onAuthExpired(handler) {
  authExpiredHandler = handler
}

const buildMessage = (url, status, bodyMessage, fallback) => {
  const base = bodyMessage || fallback || 'Request failed'
  if (!status) {
    return `${base} (${url}). Please use http://localhost:5173 and make sure backend http://localhost:8080 is running.`
  }
  return `${base} (${status})`
}

const request = async (url, options = {}) => {
  const headers = new Headers(options.headers || {})
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response
  try {
    response = await fetch(url, { ...options, headers })
  } catch (networkError) {
    throw new Error(buildMessage(url, 0, networkError?.message, 'Network unavailable'))
  }

  if (response.status === 401) {
    clearSession()
    authExpiredHandler?.()
    throw new Error('Login expired, please log in again')
  }

  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    throw new Error(buildMessage(url, response.status, data?.message, 'Request failed'))
  }

  if (response.status === 204 || !response.headers.get('content-type')?.includes('application/json')) {
    return null
  }
  return response.json()
}

const jsonOptions = (body, method = 'POST') => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

export const authApi = {
  register: (body) => request('/api/auth/register', jsonOptions(body)),
  login: (body) => request('/api/auth/login', jsonOptions(body)),
  me: () => request('/api/auth/me'),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  changePassword: (body) => request('/api/auth/change-password', jsonOptions(body)),
  updateUsername: (username) => request('/api/auth/username', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username }) }),
  updateDisplayName: (displayName) => request('/api/auth/display-name', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ displayName }) }),
}

export const adminApi = {
  listUsers: () => request('/api/admin/users'),
  changeRole: (id, role) => request(`/api/admin/users/${id}/role`, jsonOptions({ role }, 'PATCH')),
  resetPassword: (id) => request(`/api/admin/users/${id}/reset-password`, { method: 'POST' }),
  deleteUser: (id) => request(`/api/admin/users/${id}`, { method: 'DELETE' }),
}

export const collaborationApi = {
  list: () => request('/api/task-collaborations'),
  candidates: () => request('/api/task-collaborations/candidates'),
  upsert: (body) => request('/api/task-collaborations', jsonOptions(body)),
  remove: (taskId, userId) => request(`/api/task-collaborations/${taskId}/${userId}`, { method: 'DELETE' }),
}

export const taskApi = {
  list: (filters = {}) => {
    const params = new URLSearchParams()
    if (filters.creatorUsername) params.set('creatorUsername', filters.creatorUsername)
    if (filters.startDate) params.set('startDate', filters.startDate)
    if (filters.endDate) params.set('endDate', filters.endDate)
    const qs = params.toString()
    return request(`/api/tasks${qs ? '?' + qs : ''}`)
  },
  get: (id) => request(`/api/tasks/${id}`),
  create: (body) => request('/api/tasks', jsonOptions(body)),
  update: (id, body) => request(`/api/tasks/${id}`, jsonOptions(body, 'PATCH')),
  setStatus: (id, value) => request(`/api/tasks/${id}/status?value=${value}`, { method: 'PATCH' }),
  saveGraph: (id, graphJson) => request(`/api/tasks/${id}/graph`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: graphJson,
  }),
  execute: (id, sourceFileId) => {
    const qs = sourceFileId ? `?sourceFileId=${sourceFileId}` : ''
    return request(`/api/tasks/${id}/execute${qs}`, { method: 'POST' })
  },
  pauseContinuous: (id) => request(`/api/tasks/${id}/continuous/pause`, { method: 'POST' }),
  resumeContinuous: (id) => request(`/api/tasks/${id}/continuous/resume`, { method: 'POST' }),
  resetContinuousRetry: (id) => request(`/api/tasks/${id}/continuous/reset-retry`, { method: 'POST' }),
  runContinuousNow: (id) => request(`/api/tasks/${id}/continuous/run-now`, { method: 'POST' }),
  submitFlink: (id, parallelism = 1) => request(`/api/tasks/${id}/flink`, jsonOptions({ parallelism })),
  withdraw: (id) => request(`/api/tasks/${id}/status?value=DRAFT`, { method: 'PATCH' }),
  remove: (id) => request(`/api/tasks/${id}`, { method: 'DELETE' }),
}

export const taskRunApi = {
  list: () => request('/api/task-runs'),
}

export const exportApi = {
  downloadUrl: (fileName) => `/api/exports/${encodeURIComponent(fileName)}`,
  download: async (fileName) => {
    const headers = new Headers()
    const token = getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const url = `/api/exports/${encodeURIComponent(fileName)}`
    const response = await fetch(url, { headers })
    if (response.status === 401) {
      clearSession()
      authExpiredHandler?.()
      throw new Error('Login expired, please log in again')
    }
    if (!response.ok) {
      const data = await response.json().catch(() => ({}))
      throw new Error(buildMessage(url, response.status, data?.message, '下载导出文件失败'))
    }
    const blob = await response.blob()
    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(objectUrl)
  },
}

export const tableApi = {
  preview: (tableName, limit = 30) => request(`/api/tables/${encodeURIComponent(tableName)}?limit=${limit}`),
}

export const engineApi = {
  testNode: (body) => request('/api/engine/node-test', jsonOptions(body)),
}

export const outputDataSourceApi = {
  list: () => request('/api/output-data-sources'),
  drivers: () => request('/api/output-data-sources/drivers'),
  create: (body) => request('/api/output-data-sources', jsonOptions(body)),
  update: (id, body) => request(`/api/output-data-sources/${id}`, jsonOptions(body, 'PATCH')),
  remove: (id) => request(`/api/output-data-sources/${id}`, { method: 'DELETE' }),
  test: (body) => request('/api/output-data-sources/test', jsonOptions(body)),
  tables: (id) => request(`/api/output-data-sources/${id}/tables`),
  columns: (id, table) => request(`/api/output-data-sources/${id}/tables/${encodeURIComponent(table)}/columns`),
}

export const flinkApi = {
  health: () => request('/api/flink/health'),
  jobs: () => request('/api/flink/jobs'),
  submit: (parallelism = 1) => request(`/api/flink/jobs`, jsonOptions({ parallelism })),
  status: (jobId) => request(`/api/flink/jobs/${jobId}`),
  cancel: (jobId) => request(`/api/flink/jobs/${jobId}`, { method: 'DELETE' }),
}

export const aiApi = {
  assist: (body) => request('/api/ai/assist', jsonOptions(body)),
}

export const fileApi = {
  list: () => request('/api/files'),
  get: (id) => request(`/api/files/${id}`),
  rename: (id, originalName) => request(`/api/files/${id}/name`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ originalName }) }),
  remove: (id) => request(`/api/files/${id}`, { method: 'DELETE' }),
  upload: (file) => {
    const data = new FormData()
    data.append('file', file)
    return request('/api/files', { method: 'POST', body: data })
  },
}
