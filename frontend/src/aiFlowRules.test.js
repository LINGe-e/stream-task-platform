import test from 'node:test'
import assert from 'node:assert/strict'
import { canConnectNodeTypes, detectSinkKind, detectSourceKind, extractFlowSpec } from './aiFlowRules.js'

test('输入 JSON 并写入 MySQL 时，数据源识别为 JSON', () => {
  const prompt = '输入 json，过滤金额大于 1000，字段脱敏，写入 MySQL'
  assert.equal(detectSourceKind(prompt), 'json-file')
  assert.equal(detectSinkKind(prompt), 'mysql')
})

test('读取 MySQL 表并导出 CSV 时，数据源仍识别为 MySQL', () => {
  const prompt = '读取 MySQL 表，过滤状态，导出 CSV'
  assert.equal(detectSourceKind(prompt), 'mysql')
  assert.equal(detectSinkKind(prompt), 'csv')
})

test('后续写入目标不会覆盖上传附件的数据源类型', () => {
  const prompt = '读取数据，过滤金额，写入 MySQL'
  assert.equal(detectSourceKind(prompt, 'orders.json json-file'), 'json-file')
})

test('只有输出控件可以直接连接结束节点', () => {
  assert.equal(canConnectNodeTypes('transform', 'end'), false)
  assert.equal(canConnectNodeTypes('source', 'end'), false)
  assert.equal(canConnectNodeTypes('sink', 'end'), true)
})

test('可以从 Markdown 代码块中解析 AI 画布 JSON', () => {
  const rawText = '说明文字\n```json\n{"nodes":[{"id":"start"}],"edges":[]}\n```\n结束'
  assert.deepEqual(extractFlowSpec(rawText), { nodes: [{ id: 'start' }], edges: [] })
})

test('可以解析嵌套在接口结果中的 AI 画布 JSON', () => {
  const rawText = '{"code":0,"result":{"data":{"nodes":[{"id":"start"}],"edges":[]}}}'
  assert.deepEqual(extractFlowSpec(rawText), { nodes: [{ id: 'start' }], edges: [] })
})
