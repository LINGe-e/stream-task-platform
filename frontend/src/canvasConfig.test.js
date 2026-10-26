import test from 'node:test'
import assert from 'node:assert/strict'
import { auditCanvasConfig, CONTROL_CONFIG_SPECS, validateControlConfig } from './canvasConfig.js'

test('所有控件配置示例都能通过体检', () => {
  for (const [key, spec] of Object.entries(CONTROL_CONFIG_SPECS)) {
    const [nodeType, kind] = key.split('|')
    const result = validateControlConfig({ nodeType, kind, config: spec.example })
    assert.equal(
      result.level,
      'ok',
      `${key} 示例未通过：${result.issues.map((issue) => issue.message).join('；')}`,
    )
  }
})

test('JSON 语法错误会报告为阻断性错误', () => {
  const result = validateControlConfig({
    nodeType: 'transform',
    kind: 'filter',
    config: '{"field":"amount",}',
  })
  assert.equal(result.level, 'error')
  assert.match(result.issues[0].message, /JSON 语法错误/)
})

test('过滤条件缺少字段和比较值时给出明确错误', () => {
  const result = validateControlConfig({
    nodeType: 'transform',
    kind: 'filter',
    config: '{"operator":">"}',
  })
  assert.equal(result.level, 'error')
  assert.ok(result.issues.some((issue) => issue.message.includes('缺少 field')))
  assert.ok(result.issues.some((issue) => issue.message.includes('缺少 value')))
})

test('MySQL 输出可使用节点目标表，不重复要求配置表名', () => {
  const result = validateControlConfig({
    nodeType: 'sink',
    kind: 'mysql',
    config: '{"dedupFields":["order_id"]}',
    targetTable: 'orders_result',
  })
  assert.equal(result.level, 'ok')
})

test('JDBC 输出可引用已保存数据源而不要求节点内明文 URL', () => {
  const result = validateControlConfig({
    nodeType: 'sink',
    kind: 'jdbc',
    config: '{"connectionId":"42","writeMode":"upsert","keyFields":"[\\\"order_id\\\"]"}',
    targetTable: 'orders_result',
  })
  assert.equal(result.level, 'ok')
})

test('MySQL 输出引用已保存数据源时使用外部 JDBC 配置', () => {
  const result = validateControlConfig({
    nodeType: 'sink',
    kind: 'mysql',
    config: '{"connectionId":"7"}',
    targetTable: 'orders_result',
  })
  assert.equal(result.level, 'ok')
})

test('MySQL 输入引用已保存数据源时不要求节点内 URL', () => {
  const result = validateControlConfig({
    nodeType: 'source',
    kind: 'mysql',
    config: '{"connectionId":"7","table":"orders","limit":"10000"}',
  })
  assert.equal(result.level, 'ok')
})

test('JDBC 输入引用已保存数据源时不要求节点内 URL', () => {
  const result = validateControlConfig({
    nodeType: 'source',
    kind: 'jdbc',
    config: '{"connectionId":"42","table":"orders","limit":"10000"}',
  })
  assert.equal(result.level, 'ok')
})

test('数据库数据源 ID 非数字时阻断保存', () => {
  const result = validateControlConfig({
    nodeType: 'sink',
    kind: 'jdbc',
    config: '{"connectionId":"orders-source"}',
    targetTable: 'orders_result',
  })
  assert.equal(result.level, 'error')
  assert.match(result.issues[0].message, /数字 ID/)
})

test('输入数据源 ID 非数字时阻断保存', () => {
  const result = validateControlConfig({
    nodeType: 'source',
    kind: 'mysql',
    config: '{"connectionId":"orders-source","table":"orders"}',
  })
  assert.equal(result.level, 'error')
  assert.match(result.issues[0].message, /数字 ID/)
})

test('示例占位符会提示用户继续确认', () => {
  const result = validateControlConfig({
    nodeType: 'sink',
    kind: 'jdbc',
     config: '{"url":"jdbc:mysql://127.0.0.1:3307/a22","username":"root","password":"secret","table":"target_table"}',
  })
  assert.equal(result.level, 'warning')
  assert.ok(result.issues.some((issue) => issue.message.includes('示例占位值')))
})

test('AI 返回未知控件时会阻断保存', () => {
  const result = validateControlConfig({
    nodeType: 'transform',
    kind: 'ai-mystery',
    config: '{}',
  })
  assert.equal(result.level, 'error')
  assert.match(result.issues[0].message, /未知控件类型/)
})

test('画布体检汇总节点状态、错误和警告', () => {
  const report = auditCanvasConfig([
    { id: 'start', data: { nodeType: 'start', kind: 'start', label: '开始' } },
    {
      id: 'filter',
      data: { nodeType: 'transform', kind: 'filter', label: '金额过滤', config: '{"operator":">"}' },
    },
    {
      id: 'sink',
      data: { nodeType: 'sink', kind: 'redis', label: 'Redis 输出', config: '' },
    },
  ])
  assert.equal(report.total, 3)
  assert.equal(report.passed, 1)
  assert.equal(report.errors.length, 2)
  assert.equal(report.warnings.length, 1)
  assert.equal(report.level, 'error')
  assert.equal(report.nodeReports.find((item) => item.nodeId === 'filter').level, 'error')
  assert.equal(report.nodeReports.find((item) => item.nodeId === 'sink').level, 'warning')
})
