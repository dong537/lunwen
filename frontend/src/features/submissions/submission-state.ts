import type { SubmissionStatus } from '../../shared/api/contracts'

export function outcomeText(status: SubmissionStatus, count: number | null): string {
  switch (status) {
    case 'PENDING':
      return '等待审核'
    case 'PROCESSING':
      return '正在审核'
    case 'FAILED':
      return '审核未完成'
    case 'SUCCESS':
      return count === null ? '统计暂不可用' : count === 0 ? '全部通过' : `${count} 个问题`
  }
}

export function failureSummary(errorMessage: string | null): string {
  const reason = errorMessage?.trim()
  if (!reason) return '失败原因暂不可用，请查看详情。'

  const characters = Array.from(reason)
  return characters.length > 80 ? `${characters.slice(0, 80).join('')}…` : reason
}

export function needsPolling(status: SubmissionStatus): boolean {
  return status === 'PENDING' || status === 'PROCESSING'
}
