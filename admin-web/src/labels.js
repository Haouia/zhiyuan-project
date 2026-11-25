export const STAGES = [
  ['PENDING', '已接收', '文件已接收，等待解析'],
  ['PARSING', '解析中', '正在提取文字与结构'],
  ['INDEXING', '索引中', '正在生成向量并写入索引'],
  ['READY', '可检索', '内容已可检索与引用'],
]

export const STATUS = {
  PENDING: { label: '已接收', tone: 'neutral' },
  PARSING: { label: '解析中', tone: 'info' },
  INDEXING: { label: '索引中', tone: 'info' },
  READY: { label: '可检索', tone: 'success' },
  FAILED: { label: '处理失败', tone: 'danger' },
}

export const ERROR_REASONS = {
  NO_EXTRACTABLE_TEXT: '没有可提取的文字，扫描版 PDF 需先进行文字识别。',
  INVALID_DOCUMENT: '文件内容无法解析，请检查文件是否损坏或受密码保护。',
  DOCUMENT_TOO_COMPLEX: '文档超出解析资源上限，请拆分后重新上传。',
  PARSE_ABANDONED: '多次解析均未完成，已停止重试。请上传新版本，持续出现请联系运维。',
  EMBEDDING_FAILED: '向量服务调用失败，请检查模型端点后上传新版本。',
  INDEX_WRITE_FAILED: '写入 OpenSearch 失败。',
  INDEX_INCOMPLETE: '索引尚未包含此版本的全部分块。',
  INDEX_MAPPING_MISMATCH: '索引与当前向量模型配置不一致。',
  PARSED_DOCUMENT_UNAVAILABLE: '暂时无法读取已解析的原文。',
}

const UNITS = { line: '行', page: '页', paragraph: '段' }
const GROUPS = new Set(['PENDING', 'PARSING', 'INDEXING'])

export const inProgress = (status) => GROUPS.has(status)

export function statusOf(status) {
  return STATUS[status] ?? { label: '—', tone: 'neutral' }
}

export function reasonOf(code) {
  return code ? ERROR_REASONS[code] ?? code : ''
}

export function versionName(value) {
  return value == null ? '—' : 'v' + value
}

export function formatNumber(value) {
  return new Intl.NumberFormat('zh-CN').format(Number(value) || 0)
}

export function formatCompact(value) {
  const number = Number(value) || 0
  if (number >= 100000000) return (number / 100000000).toFixed(1).replace(/\.0$/u, '') + ' 亿'
  if (number >= 10000) return (number / 10000).toFixed(1).replace(/\.0$/u, '') + ' 万'
  return formatNumber(number)
}

/**
 * Accepts ISO strings, epoch seconds and epoch milliseconds. The admin APIs
 * mix seconds (legacy timestamps) and milliseconds (report generatedAt), so
 * anything above 1e11 is treated as milliseconds instead of being multiplied.
 */
function toDate(value) {
  if (value == null) return null
  if (typeof value === 'number') {
    const date = new Date(value >= 1e11 ? value : value * 1000)
    return Number.isNaN(date.getTime()) ? null : date
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatDateTime(value) {
  const date = toDate(value)
  if (!date) return '—'
  const parts = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).formatToParts(date).reduce((all, part) => ({ ...all, [part.type]: part.value }), {})
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

export function relativeTime(value) {
  const date = toDate(value)
  if (!date) return '—'
  const minutes = Math.round((Date.now() - date.getTime()) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.round(hours / 24)
  if (days < 7) return `${days} 天前`
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(date)
}

export function locatorLabel(locator) {
  if (!locator) return ''
  const unit = UNITS[locator.unit] ?? locator.unit ?? ''
  const start = locator.start ?? 0
  const end = locator.end ?? start
  return `第 ${start}${start !== end ? '–' + end : ''} ${unit}`
}

export function shortId(value) {
  return value ? String(value).slice(0, 8) : '—'
}

export function formatFileSize(bytes) {
  if (bytes == null) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1).replace(/\.0$/u, '') + ' KB'
  return (bytes / 1024 / 1024).toFixed(1).replace(/\.0$/u, '') + ' MB'
}

export function localDate(offsetDays = 0) {
  const date = new Date()
  date.setDate(date.getDate() + offsetDays)
  const parts = new Intl.DateTimeFormat('sv-SE', {
    year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(date)
  return parts
}
