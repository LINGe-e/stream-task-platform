<template>
  <div class="table-wrap task-table-wrap">
    <table class="data-table">
      <thead>
        <tr>
          <th>任务名称</th>
          <th>创建者</th>
          <th>数据源</th>
          <th>输出目标</th>
          <th>运行模式</th>
          <th>状态</th>
          <th>处理周期</th>
          <th>最近运行</th>
          <th>更新时间</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!tasks.length">
          <td colspan="10" class="empty">
            <div class="empty-state">
              <b>暂无任务</b>
              <span>{{ mode === 'workflow' ? '这里会显示你有权限编排的作业。' : '这里会显示已经发布到任务管理的任务。' }}</span>
            </div>
          </td>
        </tr>
        <tr v-for="task in tasks" :key="task.id">
          <td>
            <b>{{ task.name }}</b>
            <small>#{{ task.id }} · {{ (task.transforms || []).join('、') || '暂无处理算子' }}</small>
          </td>
          <td>
            <b>{{ ownerName(task) }}</b>
            <small>{{ ownerHint(task) }}</small>
          </td>
          <td><span class="chip chip-blue">{{ task.sourceType }}</span></td>
          <td><span class="chip chip-green">{{ task.sinkType }}</span></td>
          <td>
            <span :class="['chip', task.runMode === 'CONTINUOUS' ? 'chip-orange' : 'chip-blue']">
              {{ runModeText(task.runMode) }}
            </span>
            <span v-if="task.continuousRunning" class="chip chip-orange">执行中</span>
            <span v-if="task.incrementalMode && task.incrementalMode !== 'NONE'" class="chip chip-green">增量：{{ incrementalModeText(task.incrementalMode) }}</span>
            <small v-if="task.continuousRemark">{{ task.continuousRemark }}</small>
          </td>
          <td>
            <span :class="['status', String(task.status).toLowerCase()]">
              {{ statusText[task.status] || task.status }}
            </span>
          </td>
          <td>
            {{ scheduleText(task) }}
            <small v-if="task.runMode === 'CONTINUOUS'">下次：{{ formatDate(task.nextRunAt) }}</small>
            <small v-if="task.incrementalMode === 'STATUS'">状态：{{ task.processedField || '-' }}={{ task.unprocessedValue || 'UNPROCESSED' }}</small>
            <small v-if="task.incrementalMode === 'CURSOR'">游标：{{ task.cursorField || '-' }}>{{ task.lastCursorValue || '起点' }}</small>
          </td>
          <td>
            {{ formatDate(task.lastRunAt) }}
            <small v-if="task.runMode === 'CONTINUOUS'">重试 {{ task.retryCount || 0 }}/{{ task.maxRetryCount || 3 }}</small>
            <small v-if="task.lastError" class="task-error-hint">{{ task.lastError }}</small>
          </td>
          <td>{{ formatDate(task.updatedAt) }}</td>
          <td class="action-cell">
            <button class="btn btn-ghost" @click="$emit('view', task)">查看编排</button>
            <button v-if="canEdit(task)" class="btn btn-ghost" @click="$emit('edit', task)">编辑</button>
            <button v-if="canPublish(task)" class="btn btn-ghost" @click="$emit('publish', task)">发布</button>
            <button v-if="canWithdraw(task)" class="btn btn-ghost" @click="$emit('withdraw', task)">撤回</button>
            <button v-if="canStart(task)" class="btn btn-primary btn-sm" @click="$emit('start', task)">启动</button>
            <button v-if="canStop(task)" class="btn btn-danger btn-sm" @click="$emit('stop', task)">停止</button>
            <button v-if="canDelete(task)" class="btn btn-danger btn-sm" @click="$emit('delete', task)">删除</button>
            <button class="btn btn-ghost" @click="$emit('runs', task)">运行记录</button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
const props = defineProps({
  tasks: { type: Array, required: true },
  statusText: { type: Object, required: true },
  formatDate: { type: Function, required: true },
  canWrite: { type: Boolean, default: true },
  canExecute: { type: Boolean, default: true },
  canOperateTask: { type: Function, default: () => true },
  currentUser: { type: Object, default: null },
  mode: { type: String, default: 'management' },
})

defineEmits(['view', 'edit', 'publish', 'withdraw', 'start', 'stop', 'delete', 'runs'])

function canOperate(task) {
  return props.canOperateTask(task)
}

function canEdit(task) {
  return props.mode === 'workflow'
    && props.canWrite
    && canOperate(task)
    && ['DRAFT', 'STOPPED', 'FAILED', 'SUCCEEDED'].includes(task.status)
}

function canPublish(task) {
  return props.mode === 'workflow'
    && props.canWrite
    && canOperate(task)
    && ['DRAFT', 'STOPPED', 'FAILED', 'SUCCEEDED'].includes(task.status)
}

function canWithdraw(task) {
  return props.mode === 'management'
    && props.canWrite
    && canOperate(task)
    && ['PUBLISHED', 'STOPPED', 'FAILED', 'SUCCEEDED'].includes(task.status)
}

function canStart(task) {
  return props.mode === 'management'
    && props.canExecute
    && canOperate(task)
    && task.status === 'PUBLISHED'
}

function canStop(task) {
  return props.canExecute && canOperate(task) && task.status === 'RUNNING'
}

function canDelete(task) {
  return props.canWrite && canOperate(task) && task.status !== 'RUNNING'
}

function ownerName(task) {
  return task.creatorUsername || task.creatorName || task.ownerUsername || task.ownerName || '未记录'
}

function hasOwner(task) {
  return task.creatorId != null || Boolean(task.creatorUsername || task.creatorName || task.ownerUsername || task.ownerName)
}

function ownerHint(task) {
  if (!hasOwner(task)) return '创建者未记录'
  if (props.currentUser?.role === 'ADMIN') return '管理员可操作'
  if (props.currentUser?.role === 'OPS_READONLY') return '只读查看'
  return canOperate(task) ? '我的任务' : '仅可查看'
}

function runModeText(value) {
  return value === 'CONTINUOUS' ? '持续处理' : '一次执行'
}

function scheduleText(task) {
  if (task.runMode !== 'CONTINUOUS') return '手动触发'
  const typeText = {
    FIXED_RATE: '固定间隔',
    DAILY: '每日定时',
    CRON: 'Cron',
    MANUAL: '手动触发',
  }[task.scheduleType] || task.scheduleType || '未配置'
  return task.scheduleExpression ? `${typeText}：${task.scheduleExpression}` : typeText
}

function incrementalModeText(value) {
  if (value === 'STATUS') return '状态'
  if (value === 'CURSOR') return '游标'
  return '关闭'
}
</script>
