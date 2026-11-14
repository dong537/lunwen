import type { ImportResult, Major, TeacherStatistics, User } from '../../shared/api/contracts'
import { api, download } from '../../shared/api/http'

function importStudents(action: 'preview' | 'execute', file: File) {
  const body = new FormData()
  body.append('file', file)
  return api<ImportResult>('/teacher/students/imports/' + action, { method: 'POST', body })
}

export const teacherApi = {
  statistics: (signal?: AbortSignal) => api<TeacherStatistics>('/teacher/statistics', { signal }),
  majors: (signal?: AbortSignal) => api<Major[]>('/teacher/majors', { signal }),
  createStudent: (input: { username: string; name: string; major?: string | null }) =>
    api<User>('/teacher/students', { method: 'POST', json: input }),
  template: () => download('/teacher/students/imports/template', '指导学生导入模板.xlsx'),
  preview: (file: File) => importStudents('preview', file),
  execute: (file: File) => importStudents('execute', file),
}
