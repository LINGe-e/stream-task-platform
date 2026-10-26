<script setup>
import { computed, ref, onMounted } from 'vue'
import { adminApi, authApi } from './api'

const users = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const searchKeyword = ref('')

const filteredUsers = computed(() => {
  if (!searchKeyword.value.trim()) return users.value
  const keyword = searchKeyword.value.trim().toLowerCase()
  return users.value.filter(u =>
    (u.username || '').toLowerCase().includes(keyword)
    || (u.displayName || '').toLowerCase().includes(keyword)
  )
})

// 修改角色确认弹窗
const showConfirmRole = ref(false)
const pendingRoleChange = ref(null) // { user, newRole }

// 重置密码确认弹窗
const showConfirmReset = ref(false)
const pendingResetUser = ref(null)

// 重置密码结果弹窗
const showResetResult = ref(false)
const resetResult = ref(null)

// 修改我的密码弹窗
const showChangePassword = ref(false)
const changePasswordForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })

const roleText = { SADMIN: '高级管理员', ADMIN: '管理员', USER: '普通用户', OPS_READONLY: '只读运维' }
const roleOptions = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'USER', label: '普通用户' },
  { value: 'OPS_READONLY', label: '只读运维' },
]

function clearMessage() {
  error.value = ''
  notice.value = ''
}

async function loadUsers() {
  clearMessage()
  loading.value = true
  try {
    users.value = await adminApi.listUsers()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// ---- 修改角色：先弹确认框 ----
function onRoleSelect(user, newRole) {
  clearMessage()
  if (user.role === newRole) return
  pendingRoleChange.value = { user, newRole }
  showConfirmRole.value = true
}

async function confirmChangeRole() {
  if (!pendingRoleChange.value) return
  const { user, newRole } = pendingRoleChange.value
  showConfirmRole.value = false
  try {
    await adminApi.changeRole(user.id, newRole)
    notice.value = `已将「${user.displayName}」的角色修改为「${roleText[newRole]}」`
    pendingRoleChange.value = null
    await loadUsers()
  } catch (e) {
    error.value = e.message
    await loadUsers() // 刷新以还原 select 显示
  }
}

function cancelChangeRole() {
  showConfirmRole.value = false
  pendingRoleChange.value = null
}

// ---- 重置密码：先弹确认框 ----
function onResetPassword(user) {
  clearMessage()
  pendingResetUser.value = user
  showConfirmReset.value = true
}

async function confirmResetPassword() {
  if (!pendingResetUser.value) return
  const user = pendingResetUser.value
  showConfirmReset.value = false
  try {
    const result = await adminApi.resetPassword(user.id)
    resetResult.value = result
    showResetResult.value = true
    notice.value = `已为「${user.displayName}」重置密码`
    pendingResetUser.value = null
  } catch (e) {
    error.value = e.message
  }
}

function cancelResetPassword() {
  showConfirmReset.value = false
  pendingResetUser.value = null
}

// ---- 删除用户 ----
async function deleteUser(user) {
  clearMessage()
  if (!confirm(`确定要删除用户「${user.displayName}」吗？\n\n该用户创建的任务仍会保留，但无法撤销此操作。`)) {
    return
  }
  try {
    await adminApi.deleteUser(user.id)
    notice.value = `用户「${user.displayName}」已删除`
    await loadUsers()
  } catch (e) {
    error.value = e.message
  }
}

// ---- 修改我的密码 ----
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
    await authApi.changePassword({
      oldPassword: changePasswordForm.value.oldPassword,
      newPassword: changePasswordForm.value.newPassword,
    })
    notice.value = '密码修改成功，请重新登录'
    showChangePassword.value = false
    changePasswordForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
  } catch (e) {
    error.value = e.message
  }
}

function closeResetResult() {
  showResetResult.value = false
  resetResult.value = null
}

function copyTempPassword() {
  if (resetResult.value?.tempPassword) {
    navigator.clipboard.writeText(resetResult.value.tempPassword)
    notice.value = '临时密码已复制到剪贴板'
  }
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

onMounted(loadUsers)
</script>

<template>
  <div>
    <div class="section-title" style="margin-bottom: 20px">
      <div>
        <h2>用户管理</h2>
        <p style="margin: 4px 0 0">管理平台用户账号、角色和密码</p>
      </div>
      <div style="display: flex; gap: 10px; align-items: center">
        <input v-model="searchKeyword" type="text" placeholder="搜索用户名或昵称..." class="user-search-input" />
        <button class="btn btn-ghost" @click="showChangePassword = true">修改我的密码</button>
        <button class="btn btn-ghost" @click="loadUsers" :disabled="loading">刷新</button>
      </div>
    </div>

    <p v-if="error" class="login-error">{{ error }}</p>
    <p v-if="notice" class="notice">{{ notice }}</p>

    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>用户名</th>
            <th>显示名称</th>
            <th>角色</th>
            <th>创建时间</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!filteredUsers.length">
            <td colspan="6" class="empty">
              <div class="empty-state">
                <b>暂无用户</b>
              </div>
            </td>
          </tr>
          <tr v-for="user in filteredUsers" :key="user.id">
            <td>
              <b>{{ user.username }}</b>
              <small>#{{ user.id }}</small>
            </td>
            <td>{{ user.displayName }}</td>
            <td>
              <span v-if="user.role === 'SADMIN'" style="color: #e0e7f5; font-size: 13px;">高级管理员</span>
              <select
                v-else
                :value="user.role"
                @change="onRoleSelect(user, $event.target.value)"
                class="role-select"
              >
                <option v-for="opt in roleOptions" :key="opt.value" :value="opt.value">
                  {{ opt.label }}
                </option>
              </select>
            </td>
            <td>{{ formatDate(user.createdAt) }}</td>
            <td>{{ formatDate(user.updatedAt) }}</td>
            <td class="action-cell">
              <button class="btn btn-ghost" @click="onResetPassword(user)">重置密码</button>
              <button class="btn btn-danger btn-sm" @click="deleteUser(user)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 修改角色确认弹窗 -->
    <div v-if="showConfirmRole" class="modal-mask" @click.self="cancelChangeRole">
      <section class="modal confirm-modal">
        <div class="confirm-icon">👤</div>
        <h3>确认修改角色</h3>
        <p>
          将用户「<strong>{{ pendingRoleChange?.user?.displayName }}</strong>」
          的角色从「<strong>{{ roleText[pendingRoleChange?.user?.role] }}</strong>」
          修改为「<strong>{{ roleText[pendingRoleChange?.newRole] }}</strong>」？
        </p>
        <div class="confirm-actions">
          <button class="btn btn-ghost" @click="cancelChangeRole">取消</button>
          <button class="btn btn-primary" @click="confirmChangeRole">确认修改</button>
        </div>
      </section>
    </div>

    <!-- 重置密码确认弹窗 -->
    <div v-if="showConfirmReset" class="modal-mask" @click.self="cancelResetPassword">
      <section class="modal confirm-modal">
        <div class="confirm-icon">🔑</div>
        <h3>确认重置密码</h3>
        <p>
          确定要为用户「<strong>{{ pendingResetUser?.displayName }}</strong>」重置密码吗？
        </p>
        <p class="confirm-hint">
          重置后该用户的所有登录将失效，需要使用临时密码重新登录。
        </p>
        <div class="confirm-actions">
          <button class="btn btn-ghost" @click="cancelResetPassword">取消</button>
          <button class="btn btn-primary" @click="confirmResetPassword">确认重置</button>
        </div>
      </section>
    </div>

    <!-- 重置密码结果弹窗 -->
    <div v-if="showResetResult" class="modal-mask" @click.self="closeResetResult">
      <section class="modal" style="max-width: 460px">
        <div class="section-title">
          <h2>密码重置成功</h2>
          <button type="button" class="link" @click="closeResetResult">关闭</button>
        </div>
        <div class="reset-result">
          <p>已为用户「<strong>{{ resetResult?.username }}</strong>」生成临时密码：</p>
          <div class="temp-password-box">
            <code>{{ resetResult?.tempPassword }}</code>
            <button class="btn btn-ghost btn-sm" @click="copyTempPassword">复制</button>
          </div>
          <p class="login-tip" style="margin-top: 12px">
            请将此临时密码安全地告知用户。用户使用临时密码登录后，应及时修改密码。
          </p>
        </div>
        <div class="config-actions" style="margin-top: 16px">
          <button class="btn btn-primary" @click="closeResetResult">确定</button>
        </div>
      </section>
    </div>

    <!-- 修改我的密码弹窗 -->
    <div v-if="showChangePassword" class="modal-mask" @click.self="showChangePassword = false">
      <section class="modal" style="max-width: 420px">
        <form @submit.prevent="submitChangePassword">
          <div class="section-title">
            <h2>修改密码</h2>
            <button type="button" class="link" @click="showChangePassword = false">关闭</button>
          </div>
          <label>
            <span>原密码</span>
            <input v-model="changePasswordForm.oldPassword" type="password" placeholder="请输入当前密码" autocomplete="current-password" />
          </label>
          <label>
            <span>新密码</span>
            <input v-model="changePasswordForm.newPassword" type="password" placeholder="至少 8 位" autocomplete="new-password" />
          </label>
          <label>
            <span>确认新密码</span>
            <input v-model="changePasswordForm.confirmPassword" type="password" placeholder="请再次输入新密码" autocomplete="new-password" />
          </label>
          <div class="config-actions" style="margin-top: 12px">
            <button type="button" class="btn btn-ghost" @click="showChangePassword = false">取消</button>
            <button type="submit" class="btn btn-primary">确认修改</button>
          </div>
        </form>
      </section>
    </div>
  </div>
</template>