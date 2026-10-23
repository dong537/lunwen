export function validateFile(file: File, type: 'docx' | 'xlsx'): string | null {
  if (!file.name.toLowerCase().endsWith(`.${type}`)) return `请选择 .${type} 格式的文件。`
  if (file.size === 0) return '文件为空，请重新选择。'
  const max = type === 'docx' ? 50 : 5
  if (file.size > max * 1024 * 1024) return `文件不能超过 ${max} MB。`
  return null
}

export function formatTime(value: string | null): string {
  return value
    ? new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(new Date(value))
    : '—'
}

export function formatSize(bytes: number): string {
  return bytes < 1024 * 1024
    ? `${Math.ceil(bytes / 1024)} KB`
    : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
