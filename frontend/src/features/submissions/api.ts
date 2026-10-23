import { api } from '../../shared/api/http'
import type { Page, Submission } from '../../shared/api/contracts'

export const submissionApi = {
  list: (page: number, signal?: AbortSignal, studentId?: string) =>
    api<Page<Submission>>(
      `${studentId ? `/teacher/students/${encodeURIComponent(studentId)}` : ''}/submissions?page=${page}&size=20`,
      { signal },
    ),
  get: (id: string, signal?: AbortSignal) =>
    api<Submission>(`/submissions/${encodeURIComponent(id)}`, { signal }),
  upload: (file: File) => {
    const body = new FormData()
    body.append('file', file)
    return api<Submission>('/submissions', { method: 'POST', body })
  },
}
