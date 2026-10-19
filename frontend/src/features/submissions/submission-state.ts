import type { SubmissionStatus } from '../../shared/api/contracts'

export function outcomeText(status: SubmissionStatus, count: number | null): string {
  switch (status) {
    case 'PENDING':
      return '等待审核'
    case 'PROCESSING':
      return '正在审核'
    case 'FAILED':
      return '审核失败'
    case 'SUCCESS':
      return count === null ? '统计暂不可用' : count === 0 ? '全部通过' : `${count} 个问题`
  }
}
export function needsPolling(status: SubmissionStatus): boolean {
  return status === 'PENDING' || status === 'PROCESSING'
}
