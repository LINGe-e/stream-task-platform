const SOURCE_ACTION_PATTERN = /读取|输入|消费|导入|来源|接入|source/
const SINK_ACTION_PATTERN = /写入|输出|导出|发送|落库|目标|sink/

function normalize(value) {
  return String(value || '').trim().toLowerCase()
}

function beforeSinkAction(text) {
  const normalized = normalize(text)
  const sinkIndex = normalized.search(SINK_ACTION_PATTERN)
  return sinkIndex >= 0 ? normalized.slice(0, sinkIndex) : normalized
}

export function detectSourceKind(prompt, attachmentContext = '') {
  const attachments = normalize(attachmentContext)
  if (attachments.includes('json-file')) return 'json-file'
  if (attachments.includes('.csv') || /\bcsv\b/.test(attachments)) return 'csv'
  if (attachments.includes('.xlsx') || attachments.includes('.xls') || /\bexcel\b/.test(attachments)) return 'excel'
  if (attachments.includes('.xml') || /\bxml\b/.test(attachments)) return 'xml'
  if (/\bkafka\b/.test(attachments)) return 'kafka'

  const sourceText = beforeSinkAction(prompt)
  if (/json/.test(sourceText)) return 'json-file'
  if (/kafka|消息|队列/.test(sourceText)) return 'kafka'
  if (/excel|xlsx|xls/.test(sourceText)) return 'excel'
  if (/\bcsv\b/.test(sourceText)) return 'csv'
  if ((/mysql|数据库|表/.test(sourceText)) && SOURCE_ACTION_PATTERN.test(sourceText)) return 'mysql'
  return 'json-file'
}

export function detectSinkKind(prompt) {
  const normalized = normalize(prompt)
  const sinkIndex = normalized.search(SINK_ACTION_PATTERN)
  const sinkText = sinkIndex >= 0 ? normalized.slice(sinkIndex) : normalized
  if (/redis/.test(sinkText)) return 'redis'
  if (/kafka|消息|队列/.test(sinkText)) return 'kafka'
  if (/excel|xlsx|xls/.test(sinkText)) return 'excel'
  if (/\bcsv\b/.test(sinkText)) return 'csv'
  if (/json/.test(sinkText)) return 'json-file'
  return 'mysql'
}

export function canConnectNodeTypes(from, to) {
  return (from === 'start' && to === 'source')
    || (from === 'source' && ['transform', 'sink'].includes(to))
    || (from === 'transform' && ['transform', 'sink'].includes(to))
    || (from === 'sink' && ['transform', 'end'].includes(to))
}

function findFlowSpec(value, depth = 0) {
  if (!value || depth > 5 || typeof value !== 'object') return null
  if (Array.isArray(value.nodes)) return value
  for (const key of ['data', 'result', 'flow', 'payload', 'response', 'output']) {
    const nested = findFlowSpec(value[key], depth + 1)
    if (nested) return nested
  }
  return null
}

function extractJsonObjects(text) {
  const values = []
  for (let start = 0; start < text.length; start += 1) {
    if (text[start] !== '{') continue
    let depth = 0
    let inString = false
    let escaped = false
    for (let end = start; end < text.length; end += 1) {
      const character = text[end]
      if (inString) {
        if (escaped) escaped = false
        else if (character === '\\') escaped = true
        else if (character === '"') inString = false
        continue
      }
      if (character === '"') inString = true
      else if (character === '{') depth += 1
      else if (character === '}') {
        depth -= 1
        if (depth === 0) {
          values.push(text.slice(start, end + 1))
          break
        }
      }
    }
  }
  return values
}

export function extractFlowSpec(rawText) {
  if (rawText && typeof rawText === 'object') return findFlowSpec(rawText)
  const text = String(rawText || '').trim()
  if (!text) return null
  const fenced = [...text.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)].map((match) => match[1])
  const candidates = [...fenced, text, ...extractJsonObjects(text)]
  for (const candidate of candidates) {
    try {
      const parsed = JSON.parse(candidate)
      const flowSpec = findFlowSpec(parsed)
      if (flowSpec) return flowSpec
    } catch {
      // 继续尝试其他 JSON 片段
    }
  }
  return null
}
