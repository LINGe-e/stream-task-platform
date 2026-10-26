// 画布控件配置规格：集中描述用户需要知道的字段、示例和校验规则。
const json = (required = true, description = '', example = '') => ({ required, description, example })

export const CONTROL_CONFIG_SPECS = {
  'source|csv': {
    title: 'CSV 输入',
    format: 'JSON 或留空',
    summary: '留空时使用最近上传的测试文件；也可以指定 fileId 或内联 data。',
    fields: [json(true, '数据来源：fileId、data，或留空使用最近上传文件', '{"fileId":12}')],
    example: '{"fileId":12}',
  },
  'source|json-file': {
    title: 'JSON 输入',
    format: 'JSON 或留空',
    summary: '留空时使用最近上传的测试文件；也可以指定 fileId 或内联 data。',
    fields: [json(true, '数据来源：fileId、data，或留空使用最近上传文件', '{"fileId":12}')],
    example: '{"fileId":12}',
  },
  'source|excel': {
    title: 'Excel 输入',
    format: 'JSON 或留空',
    summary: '留空时使用最近上传的测试文件；也可以指定 fileId 或内联 data。',
    fields: [json(true, '数据来源：fileId、data，或留空使用最近上传文件', '{"fileId":12}')],
    example: '{"fileId":12}',
  },
  'source|mysql': {
    title: 'MySQL 输入',
    format: 'JSON',
    summary: '读取平台数据库中的表，可配置单次读取上限和增量字段。',
    fields: [
      json(true, 'table：要读取的表名', 'orders'),
      json(false, 'limit：最大读取条数', '1000'),
      json(false, 'processedField / cursorField：增量处理字段', 'updated_at'),
    ],
    example: '{"table":"orders","limit":"1000"}',
  },
  'source|jdbc': {
    title: 'JDBC 输入',
    format: 'JSON',
    summary: '连接外部 PostgreSQL、Oracle、MySQL 等数据库。',
    fields: [
      json(true, 'url：JDBC 连接地址', 'jdbc:mysql://127.0.0.1:3307/a22'),
      json(true, 'username：数据库账号', 'root'),
      json(false, 'password：数据库密码', '******'),
      json(true, 'table 或 query：读取目标', 'orders'),
    ],
    example: '{"url":"jdbc:mysql://127.0.0.1:3307/a22","username":"root","password":"******","table":"orders"}',
  },
  'source|redis': {
    title: 'Redis 输入',
    format: 'JSON',
    summary: '从 Redis List、Set 或 Hash 读取记录。',
    fields: [
      json(true, 'type：list、set 或 hash', 'list'),
      json(true, 'key：键名；hash 可用 pattern', 'a22:input'),
      json(false, 'count：最大读取条数', '1000'),
    ],
    example: '{"type":"list","key":"a22:input","count":"1000"}',
  },
  'source|kafka': {
    title: 'Kafka 输入',
    format: 'JSON',
    summary: '从指定 Topic 消费消息。',
    fields: [
      json(true, 'topic：Kafka Topic', 'orders'),
      json(false, 'limit / timeoutMs：读取上限和超时', '1000'),
    ],
    example: '{"topic":"orders","limit":"1000","timeoutMs":"5000"}',
  },
  'source|hdfs': {
    title: 'HDFS 输入',
    format: 'JSON',
    summary: '读取 HDFS 或本地兼容路径中的 CSV/JSON 文件。',
    fields: [
      json(true, 'path：文件路径', './hdfs-data/input/data.csv'),
      json(true, 'format：csv 或 json', 'csv'),
      json(false, 'fs：文件系统地址', 'file:///'),
      json(false, 'limit：最大读取条数', '100000'),
    ],
    example: '{"fs":"file:///","path":"./hdfs-data/input/data.csv","format":"csv","limit":"100000"}',
  },
  'transform|field-map': {
    title: '字段映射',
    format: 'JSON mapping 或“源字段:目标字段”列表',
    summary: '把输入字段重命名或映射为目标字段。',
    fields: [
      json(true, 'mapping：对象，键为源字段、值为目标字段', '{"订单号":"order_id"}'),
      json(false, '兼容格式：source:target,source:target', 'order_no:order_id,amount:amount'),
    ],
    example: '{"mapping":{"订单号":"order_id","金额":"amount"}}',
  },
  'transform|filter': {
    title: '条件过滤',
    format: 'JSON',
    summary: '单条件直接写 field/operator/value；多条件使用 conditions 数组。',
    fields: [
      json(true, 'field：比较字段；fieldless 关键词过滤可省略', 'amount'),
      json(true, 'operator：=、>、contains_any、in、between、date_between 等', '>'),
      json(true, 'value 或 keywords：按 operator 提供比较值', '1000'),
      json(false, 'logic：多条件连接方式，AND 或 OR', 'AND'),
    ],
    example: '{"field":"amount","operator":">","value":1000}',
  },
  'transform|dedup': {
    title: '去重',
    format: 'JSON',
    summary: '按字段分组去重；orderFields 用于决定保留哪一条。',
    fields: [
      json(true, 'fields 或 field：去重字段', '["transaction_id"]'),
      json(false, 'keep：first 或 last', 'last'),
      json(false, 'orderFields：组内排序字段', '["created_at"]'),
    ],
    example: '{"fields":["transaction_id"],"keep":"last","orderFields":["created_at"]}',
  },
  'transform|mask': {
    title: '字段脱敏',
    format: 'JSON',
    summary: '按字段或规则隐藏手机号、证件号等敏感信息。',
    fields: [
      json(true, 'rules：脱敏规则数组', '[{"field":"phone","type":"phone","showLast":4}]'),
      json(false, 'fields：使用默认规则的字段列表', '["phone","id_card"]'),
    ],
    example: '{"rules":[{"field":"phone","type":"phone","showLast":4}]}',
  },
  'transform|concat': {
    title: '字段拼接',
    format: 'JSON',
    summary: '把多个字段拼成一个目标字段。',
    fields: [
      json(true, 'fields：源字段数组', '["first_name","last_name"]'),
      json(true, 'target：目标字段名', 'full_name'),
      json(false, 'separator：分隔符', '" "'),
    ],
    example: '{"fields":["first_name","last_name"],"target":"full_name","separator":" "}',
  },
  'transform|data-repair': {
    title: '数据补全',
    format: 'JSON',
    summary: '按数据库记录的键补全缺失字段。',
    fields: [
      json(true, 'lookupTable：查询表', 'dim_users'),
      json(true, 'keys：输入字段与查询字段的对应关系', '[{"recordField":"user_id","lookupField":"user_id"}]'),
      json(true, 'fields：要补全的字段', '[{"sourceField":"phone","lookupField":"phone","targetField":"phone"}]'),
    ],
    example: '{"lookupTable":"dim_users","keys":[{"recordField":"user_id","lookupField":"user_id"}],"fields":[{"sourceField":"phone","lookupField":"phone","targetField":"phone"}]}',
  },
  'transform|redis-lookup': {
    title: 'Redis 查询补全',
    format: 'JSON',
    summary: '根据输入值生成 Redis Key，并补全 Hash 字段。',
    fields: [
      json(true, 'sourceField：生成 Key 的输入字段', 'user_id'),
      json(true, 'redisKeyPattern：Key 模板', 'user:{value}'),
      json(true, 'targetFields：Redis 字段到目标字段的映射', '[{"redisField":"phone","targetField":"phone"}]'),
    ],
    example: '{"sourceField":"user_id","redisKeyPattern":"user:{value}","redisType":"hash","targetFields":[{"redisField":"phone","targetField":"phone"}]}',
  },
  'transform|data-validate': {
    title: '数据校验',
    format: 'JSON',
    summary: '按字段规则生成审计信息，并可过滤有效或无效数据。',
    fields: [
      json(true, 'rules：校验规则数组', '[{"field":"phone","required":true,"regex":"^\\\\d{11}$"}]'),
      json(false, 'filterMode：invalid_only 或 valid_only', 'invalid_only'),
      json(false, 'issueField / validField：审计字段名', '_issues'),
    ],
    example: '{"tagRows":true,"issueField":"_issues","validField":"_audit_valid","rules":[{"field":"phone","required":true}]}',
  },
  'transform|type-convert': {
    title: '类型转换',
    format: 'JSON',
    summary: '把字段转换为字符串、整数、小数、布尔、日期或时间。',
    fields: [
      json(true, 'rules：字段转换规则数组', '[{"field":"amount","targetType":"DECIMAL"}]'),
      json(false, 'onError：error、default 或 skip', 'errorField'),
    ],
    example: '{"rules":[{"field":"amount","targetType":"DECIMAL"}],"onError":"errorField"}',
  },
  'transform|xml-json': {
    title: 'XML/JSON 转换',
    format: 'JSON',
    summary: '在 XML 和 JSON 字符串之间转换。',
    fields: [
      json(true, 'field：源字段', 'payload'),
      json(false, 'target：目标字段，默认与 field 相同', 'payload_json'),
      json(false, 'direction：xml-to-json 或 json-to-xml', 'xml-to-json'),
    ],
    example: '{"field":"payload","target":"payload_json","direction":"xml-to-json"}',
  },
  'sink|mysql': {
    title: 'MySQL 输出',
    format: 'JSON',
    summary: '写入 MySQL 结果表。',
    fields: [
      json(true, 'targetTable：结果表名；也可填写节点上的目标表名', 'a22_demo_output'),
      json(false, 'dedupFields：写入前用于去重的字段', '["order_id"]'),
    ],
    example: '{"targetTable":"a22_demo_output"}',
  },
  'sink|oceanbase': {
    title: 'OceanBase 输出',
    format: 'JSON',
    summary: '写入 OceanBase MySQL 兼容租户。',
    fields: [
      json(true, 'url：JDBC 连接地址', 'jdbc:mysql://127.0.0.1:2881/a22'),
      json(true, 'username：租户账号', 'root@test'),
      json(false, 'password：租户密码', '******'),
      json(true, 'table：目标表', 'orders_result'),
    ],
    example: '{"url":"jdbc:mysql://127.0.0.1:2881/a22","username":"root@test","password":"******","table":"orders_result"}',
  },
  'sink|jdbc': {
    title: 'JDBC 输出',
    format: 'JSON',
    summary: '写入外部 JDBC 数据库表。',
    fields: [
      json(true, 'url：JDBC 连接地址', 'jdbc:mysql://127.0.0.1:3307/a22'),
      json(true, 'username：数据库账号', 'root'),
      json(false, 'password：数据库密码', '******'),
      json(true, 'table：目标表', 'orders_result'),
    ],
    example: '{"url":"jdbc:mysql://127.0.0.1:3307/a22","username":"root","password":"******","table":"orders_result"}',
  },
  'sink|hdfs': {
    title: 'HDFS 输出',
    format: 'JSON',
    summary: '把结果写入 HDFS 或本地兼容路径。',
    fields: [
      json(true, 'path：输出文件路径', './hdfs-data/output/result.csv'),
      json(true, 'format：csv 或 json', 'csv'),
      json(false, 'overwrite：是否覆盖', 'true'),
    ],
    example: '{"fs":"file:///","path":"./hdfs-data/output/result.csv","format":"csv","overwrite":"true"}',
  },
  'sink|kafka': {
    title: 'Kafka 输出',
    format: 'JSON',
    summary: '把结果发送到指定 Topic。',
    fields: [
      json(true, 'topic：Kafka Topic', 'orders_result'),
      json(false, 'keyField：消息键字段', 'order_id'),
    ],
    example: '{"topic":"orders_result","keyField":"order_id"}',
  },
  'sink|redis': {
    title: 'Redis 输出',
    format: 'JSON',
    summary: '把结果写入 Redis Stream。',
    fields: [json(false, 'stream：Stream 名称；留空使用 a22:task-output', 'a22:task-output')],
    example: '{"stream":"a22:task-output"}',
  },
  'sink|csv': {
    title: 'CSV 输出',
    format: 'JSON',
    summary: '生成 CSV 导出文件。',
    fields: [json(false, 'fileName：导出文件名；留空使用结果表名', 'orders_result.csv')],
    example: '{"fileName":"orders_result.csv"}',
  },
  'sink|excel': {
    title: 'Excel 输出',
    format: 'JSON',
    summary: '生成 Excel 导出文件。',
    fields: [json(false, 'fileName：导出文件名；留空使用结果表名', 'orders_result.xlsx')],
    example: '{"fileName":"orders_result.xlsx"}',
  },
}

const FILTER_OPERATORS = new Set([
  '=', 'eq', '!=', '<>', 'ne', '>', '>=', '<', '<=',
  'between', 'range', 'not_between', 'not_range',
  'in', 'not_in', 'contains', 'not_contains', 'contains_all', 'contains_any',
  'starts_with', 'ends_with', 'regex',
  'date_before', 'date_after', 'date_between', 'date_equals',
])
const KEYWORD_OPERATORS = new Set(['in', 'not_in', 'contains_all', 'contains_any'])
const BETWEEN_OPERATORS = new Set(['between', 'range', 'not_between', 'not_range'])
const FIELDLESS_KEYWORD_OPERATORS = new Set(['contains_all', 'contains_any'])
const PLACEHOLDER_PATTERN = /\b(?:choose_[^\s"',}]+|source_table|target_table|dev_ymq|your_[^\s"',}]+)\b/gi

function makeIssue(level, message, hint = '') {
  return { level, message, hint }
}

function isFilled(value) {
  return value !== undefined && value !== null && String(value).trim() !== ''
}

function hasAny(object, keys) {
  return keys.some((key) => isFilled(object?.[key]))
}

function parseJsonObject(config) {
  const text = String(config || '').trim()
  if (!text) return { empty: true, value: null }
  try {
    const value = JSON.parse(text)
    if (!value || Array.isArray(value) || typeof value !== 'object') {
      return { error: '配置必须是 JSON 对象。' }
    }
    return { value }
  } catch (error) {
    return { error: `JSON 语法错误：${error.message}` }
  }
}

function validateRegex(pattern, issues) {
  if (!isFilled(pattern)) return
  try {
    new RegExp(pattern)
  } catch (error) {
    issues.push(makeIssue('error', `正则表达式无效：${error.message}`, '请检查转义反斜杠和括号是否成对。'))
  }
}

function validateCondition(condition, issues, index, nested = false) {
  const prefix = nested ? `第 ${index + 1} 个条件` : '条件'
  if (!condition || typeof condition !== 'object' || Array.isArray(condition)) {
    issues.push(makeIssue('error', `${prefix}必须是 JSON 对象。`))
    return
  }
  const operator = String(condition.operator || '=')
  if (!FILTER_OPERATORS.has(operator)) {
    issues.push(makeIssue('error', `${prefix}的 operator“${operator}”不受支持。`, '请从配置说明中的运算符列表选择。'))
  }
  if (!isFilled(condition.field) && !FIELDLESS_KEYWORD_OPERATORS.has(operator)) {
    issues.push(makeIssue('error', `${prefix}缺少 field 字段名。`))
  }
  if (KEYWORD_OPERATORS.has(operator)) {
    const keywords = Array.isArray(condition.keywords) ? condition.keywords : condition.values
    if (!Array.isArray(keywords) || !keywords.length || keywords.some((item) => !isFilled(item))) {
      issues.push(makeIssue('error', `${prefix}需要非空 keywords 或 values 数组。`))
    }
  } else if (BETWEEN_OPERATORS.has(operator)) {
    if (!hasAny(condition, ['min', 'start']) || !hasAny(condition, ['max', 'end'])) {
      issues.push(makeIssue('error', `${prefix}需要同时提供上下界，例如 min/max 或 start/end。`))
    }
  } else if (operator !== 'date_between' && !hasAny(condition, ['value', 'keyword'])) {
    issues.push(makeIssue('error', `${prefix}缺少 value 比较值。`))
  }
  if (operator === 'date_between' && !hasAny(condition, ['start', 'end'])) {
    issues.push(makeIssue('error', `${prefix}需要 start 或 end 日期。`))
  }
  if (operator === 'regex') validateRegex(condition.value, issues)
}

function validateJsonSemantics(nodeType, kind, parsed, targetTable, issues) {
  if (kind === 'field-map') {
    const mapping = parsed.mapping || parsed
    const entries = Object.entries(mapping).filter(([key, value]) => isFilled(key) && isFilled(value))
    if (!entries.length) issues.push(makeIssue('error', '字段映射 mapping 不能为空。'))
    return
  }
  if (kind === 'filter') {
    if (Array.isArray(parsed.conditions)) {
      if (!parsed.conditions.length) issues.push(makeIssue('error', 'conditions 数组不能为空。'))
      parsed.conditions.forEach((condition, index) => validateCondition(condition, issues, index, true))
      if (!['AND', 'OR'].includes(String(parsed.logic || 'AND').toUpperCase())) {
        issues.push(makeIssue('error', 'logic 仅支持 AND 或 OR。'))
      }
    } else {
      validateCondition(parsed, issues, 0)
    }
    return
  }
  if (kind === 'dedup') {
    const fields = Array.isArray(parsed.fields) ? parsed.fields : (isFilled(parsed.field) ? [parsed.field] : [])
    const orderFields = Array.isArray(parsed.orderFields) ? parsed.orderFields : []
    if (!fields.length && !orderFields.length) {
      issues.push(makeIssue('warning', '未指定去重字段，将按整行内容去重。', '建议把 fields 指向业务唯一键。'))
    }
    if (fields.some((field) => !isFilled(field) || /^choose_/i.test(String(field)))) {
      issues.push(makeIssue('error', '去重字段包含空值或占位符。'))
    }
    if (parsed.keep && !['first', 'last'].includes(parsed.keep)) {
      issues.push(makeIssue('error', 'keep 仅支持 first 或 last。'))
    }
    if (orderFields.some((field) => !isFilled(field) || /^choose_/i.test(String(field)))) {
      issues.push(makeIssue('error', '排序字段包含空值或占位符。'))
    }
    return
  }
  if (kind === 'mask') {
    const rules = Array.isArray(parsed.rules) ? parsed.rules : []
    const fields = Array.isArray(parsed.fields) ? parsed.fields : []
    if (!rules.length && !fields.length) issues.push(makeIssue('error', '至少需要 rules 或 fields。'))
    if (rules.some((rule) => !rule || !isFilled(rule.field))) issues.push(makeIssue('error', '每条脱敏规则都必须包含 field。'))
    return
  }
  if (kind === 'concat') {
    if (!Array.isArray(parsed.fields) || !parsed.fields.length || parsed.fields.some((field) => !isFilled(field))) {
      issues.push(makeIssue('error', 'fields 必须是非空字段数组。'))
    }
    if (!isFilled(parsed.target)) issues.push(makeIssue('error', '缺少 target 目标字段名。'))
    return
  }
  if (kind === 'data-repair') {
    if (!isFilled(parsed.lookupTable)) issues.push(makeIssue('error', '缺少 lookupTable 查询表名。'))
    if (!Array.isArray(parsed.keys) || !parsed.keys.length) issues.push(makeIssue('error', 'keys 必须是非空数组。'))
    if (!Array.isArray(parsed.fields) || !parsed.fields.length) issues.push(makeIssue('error', 'fields 必须是非空数组。'))
    return
  }
  if (kind === 'redis-lookup') {
    if (!isFilled(parsed.sourceField)) issues.push(makeIssue('error', '缺少 sourceField。'))
    if (!isFilled(parsed.redisKeyPattern)) issues.push(makeIssue('error', '缺少 redisKeyPattern。'))
    else if (!String(parsed.redisKeyPattern).includes('{value}')) {
      issues.push(makeIssue('error', 'redisKeyPattern 必须包含 {value} 占位符。'))
    }
    if (!Array.isArray(parsed.targetFields) || !parsed.targetFields.length) {
      issues.push(makeIssue('error', 'targetFields 必须是非空数组。'))
    }
    return
  }
  if (kind === 'data-validate') {
    if (!Array.isArray(parsed.rules) || !parsed.rules.length) {
      issues.push(makeIssue('error', 'rules 必须是非空数组。'))
      return
    }
    if (parsed.rules.some((rule) => !rule || !isFilled(rule.field))) {
      issues.push(makeIssue('error', '每条校验规则都必须包含 field。'))
    }
    if (parsed.filterMode && !['invalid_only', 'valid_only'].includes(parsed.filterMode)) {
      issues.push(makeIssue('error', 'filterMode 仅支持 invalid_only 或 valid_only。'))
    }
    if (isFilled(parsed.issueField) && String(parsed.issueField).length > 64) {
      issues.push(makeIssue('error', 'issueField 不能超过 64 个字符。'))
    }
    parsed.rules.filter((rule) => rule?.regex).forEach((rule) => validateRegex(rule.regex, issues))
    return
  }
  if (kind === 'type-convert') {
    if (Array.isArray(parsed.rules)) {
      if (!parsed.rules.length) issues.push(makeIssue('error', 'rules 数组不能为空。'))
      if (parsed.rules.some((rule) => !rule || !isFilled(rule.field) || !isFilled(rule.targetType))) {
        issues.push(makeIssue('error', '每条转换规则都必须包含 field 和 targetType。'))
      }
    } else if (!isFilled(parsed.field) || !isFilled(parsed.targetType)) {
      issues.push(makeIssue('error', '缺少 field 或 targetType。'))
    }
    if (parsed.onError && !['error', 'default', 'skip', 'errorField'].includes(parsed.onError)) {
      issues.push(makeIssue('warning', `onError“${parsed.onError}”可能无法按预期执行。`, '建议使用 error、default、skip 或 errorField。'))
    }
    return
  }
  if (kind === 'xml-json') {
    if (!isFilled(parsed.field)) issues.push(makeIssue('error', '缺少 field 源字段名。'))
    if (parsed.direction && !['xml-to-json', 'json-to-xml'].includes(parsed.direction)) {
      issues.push(makeIssue('error', 'direction 仅支持 xml-to-json 或 json-to-xml。'))
    }
    if (parsed.direction === 'json-to-xml' && !isFilled(parsed.root)) {
      issues.push(makeIssue('warning', 'json-to-xml 未指定 root，将使用默认根节点。'))
    }
    return
  }
  if (nodeType === 'source') {
    const usesSavedConnection = isFilled(parsed.connectionId)
    if (usesSavedConnection && !/^\d+$/.test(String(parsed.connectionId))) {
      issues.push(makeIssue('error', 'connectionId 必须是数据库数据源的数字 ID。', '请重新选择数据库数据源。'))
    }
    if (kind === 'mysql' && !hasAny(parsed, ['table', 'sourceTable'])) issues.push(makeIssue('error', '缺少 table 输入表名。'))
    if (kind === 'kafka' && !isFilled(parsed.topic)) issues.push(makeIssue('error', '缺少 topic。'))
    if (kind === 'redis' && !hasAny(parsed, ['key', 'pattern'])) issues.push(makeIssue('error', '缺少 key 或 pattern。'))
    if (kind === 'hdfs' && !isFilled(parsed.path)) issues.push(makeIssue('error', '缺少 path 文件路径。'))
    if (['jdbc', 'oceanbase', 'postgresql', 'oracle'].includes(kind)) {
      if (!usesSavedConnection && !isFilled(parsed.url)) {
        issues.push(makeIssue('error', '缺少数据库数据源 connectionId 或 url JDBC 地址。', '推荐在输入节点中选择已测试的数据源。'))
      }
      if (!hasAny(parsed, ['table', 'query', 'sourceTable'])) issues.push(makeIssue('error', '缺少 table 或 query。'))
      if (!usesSavedConnection && !isFilled(parsed.username)) issues.push(makeIssue('warning', '未配置数据库账号，将尝试使用环境默认凭据。'))
      if (!usesSavedConnection && isFilled(parsed.username) && !isFilled(parsed.password)) issues.push(makeIssue('warning', '数据库密码为空，请确认是否使用免密或环境凭据。'))
    }
    return
  }
  if (nodeType === 'sink') {
    const table = isFilled(targetTable) ? targetTable : (parsed.table || parsed.targetTable)
    const usesSavedConnection = isFilled(parsed.connectionId)
    if (usesSavedConnection && !/^\d+$/.test(String(parsed.connectionId))) {
      issues.push(makeIssue('error', 'connectionId 必须是数据库数据源的数字 ID。', '请重新选择数据库数据源。'))
    }
    if (['mysql'].includes(kind) && !isFilled(table)) issues.push(makeIssue('error', '缺少目标表名。'))
    if (['jdbc', 'oceanbase', 'postgresql', 'oracle'].includes(kind)) {
      if (!usesSavedConnection && !isFilled(parsed.url)) {
        issues.push(makeIssue('error', '缺少数据库数据源 connectionId 或 url JDBC 地址。', '推荐在输出节点中选择已测试的数据源。'))
      }
      if (!isFilled(table)) issues.push(makeIssue('error', '缺少 table 目标表名。'))
      if (!usesSavedConnection && !isFilled(parsed.username)) issues.push(makeIssue('warning', '未配置数据库账号，将尝试使用环境默认凭据。'))
      if (!usesSavedConnection && isFilled(parsed.username) && !isFilled(parsed.password)) issues.push(makeIssue('warning', '数据库密码为空，请确认是否使用免密或环境凭据。'))
    }
    if (kind === 'hdfs' && !isFilled(parsed.path)) issues.push(makeIssue('error', '缺少 path 输出路径。'))
    if (kind === 'kafka' && !isFilled(parsed.topic)) issues.push(makeIssue('error', '缺少 topic。'))
    if (kind === 'redis' && !isFilled(parsed.stream)) issues.push(makeIssue('warning', '未指定 stream，将使用 a22:task-output。'))
    if (['csv', 'excel'].includes(kind) && !isFilled(parsed.fileName)) issues.push(makeIssue('warning', '未指定 fileName，将使用结果表名生成文件名。'))
  }
}

function validateEmptyConfig(nodeType, kind, issues, targetTable) {
  if (nodeType === 'transform') {
    issues.push(makeIssue('error', `${kind} 没有配置参数，运行时将直接透传数据。`, '请打开节点配置并按右侧说明填写。'))
    return
  }
  if (nodeType === 'source') {
    if (['csv', 'json-file', 'excel'].includes(kind)) {
      issues.push(makeIssue('warning', '未指定数据来源，将使用最近上传的测试文件。', '如果任务必须固定数据源，请填写 fileId 或 data。'))
    } else {
      issues.push(makeIssue('error', `${kind} 输入缺少必要配置。`, '请按配置说明填写连接地址和读取目标。'))
    }
    return
  }
  if (nodeType === 'sink') {
    if (kind === 'mysql' && isFilled(targetTable)) return
    if (['csv', 'excel'].includes(kind)) {
      issues.push(makeIssue('warning', '未指定输出文件名，将自动生成。'))
    } else if (kind === 'redis') {
      issues.push(makeIssue('warning', '未指定 stream，将使用 a22:task-output。'))
    } else {
      issues.push(makeIssue('error', `${kind} 输出缺少必要配置。`, '请按配置说明填写连接地址和输出目标。'))
    }
  }
}

export function validateControlConfig({ nodeType = '', kind = '', config = '', targetTable = '' } = {}) {
  const issues = []
  if (nodeType === 'start' || nodeType === 'end') {
    return { level: 'ok', issues, spec: null }
  }
  const spec = CONTROL_CONFIG_SPECS[`${nodeType}|${kind}`]
  if (!spec) {
    issues.push(makeIssue('error', `未知控件类型：${nodeType}/${kind}。`, 'AI 生成结果可能使用了当前版本不支持的控件，请更换为画布左侧已有控件。'))
    return { level: 'error', issues, spec: null }
  }
  if (!isFilled(targetTable) && isFilled(config)) {
    // 目标表允许只放在 config 中，这里不额外提示。
  }
  const text = String(config || '').trim()
  if (!text) {
    validateEmptyConfig(nodeType, kind, issues, targetTable)
  } else if (kind === 'field-map') {
    try {
      const parsed = JSON.parse(text)
      validateJsonSemantics(nodeType, kind, parsed, targetTable, issues)
    } catch {
      const mapping = text.split(',').map((pair) => pair.split(':', 2)).filter((parts) => parts.length === 2)
      if (!mapping.length || mapping.some(([source, target]) => !isFilled(source) || !isFilled(target))) {
        issues.push(makeIssue('error', '字段映射必须是合法 JSON mapping，或使用“源字段:目标字段”格式。'))
      }
    }
  } else if (spec.format.startsWith('JSON')) {
    const parsed = parseJsonObject(text)
    if (parsed.error) {
      issues.push(makeIssue('error', parsed.error, '请检查引号、逗号和括号，或点击“使用示例”重新开始。'))
    } else if (!parsed.empty) {
      validateJsonSemantics(nodeType, kind, parsed.value, targetTable, issues)
    }
  }
  const placeholders = text.match(PLACEHOLDER_PATTERN) || []
  if (placeholders.length) {
    issues.push(makeIssue('warning', `配置中仍包含示例占位值：${[...new Set(placeholders)].join('、')}。`, '请替换成真实字段、表名或账号。'))
  }
  if (nodeType === 'sink' && kind === 'mysql' && isFilled(targetTable) && isFilled(text)) {
    try {
      const parsed = JSON.parse(text)
      const configuredTable = parsed.targetTable || parsed.table
      if (configuredTable && configuredTable !== targetTable) {
        issues.push(makeIssue('warning', `节点目标表“${targetTable}”与配置表“${configuredTable}”不一致。`, '执行时配置参数与目标表可能产生歧义，请统一两者。'))
      }
    } catch {
      // JSON 语法错误已经单独报告。
    }
  }
  const hasError = issues.some((issue) => issue.level === 'error')
  const hasWarning = issues.some((issue) => issue.level === 'warning')
  return { level: hasError ? 'error' : (hasWarning ? 'warning' : 'ok'), issues, spec }
}

export function auditCanvasConfig(nodes = []) {
  const nodeReports = nodes.map((node) => ({
    nodeId: node.id,
    label: node.data?.label || node.id,
    kind: node.data?.kind || '',
    ...validateControlConfig({
      nodeType: node.data?.nodeType || '',
      kind: node.data?.kind || '',
      config: node.data?.config || '',
      targetTable: node.data?.targetTable || '',
    }),
  }))
  const issues = nodeReports.flatMap((report) => report.issues.map((issue) => ({
    ...issue,
    nodeId: report.nodeId,
    label: report.label,
    kind: report.kind,
  })))
  const errors = issues.filter((issue) => issue.level === 'error')
  const warnings = issues.filter((issue) => issue.level === 'warning')
  return {
    nodeReports,
    issues,
    errors,
    warnings,
    total: nodeReports.length,
    passed: nodeReports.filter((report) => report.level === 'ok').length,
    level: errors.length ? 'error' : (warnings.length ? 'warning' : 'ok'),
  }
}

export function nodeConfigStatus(report, nodeId) {
  return report.nodeReports.find((item) => item.nodeId === nodeId)?.level || 'ok'
}
