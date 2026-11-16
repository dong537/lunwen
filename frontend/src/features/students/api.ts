import type {
  ImportResult,
  Major,
  StatisticsRange,
  TeacherInsights,
  TeacherStatistics,
  User,
} from '../../shared/api/contracts'
import { api, download } from '../../shared/api/http'

function importStudents(action: 'preview' | 'execute', file: File) {
  const body = new FormData()
  body.append('file', file)
  return api<ImportResult>('/teacher/students/imports/' + action, { method: 'POST', body })
}

function statisticsParams(grade: number | 'UNSET' | null, range?: StatisticsRange, page?: number) {
  const params = new URLSearchParams()
  if (grade !== null) params.set('grade', String(grade))
  if (range) params.set('range', range)
  if (page) {
    params.set('page', String(page))
    params.set('size', '10')
  }
  return params.toString()
}

export const teacherApi = {
  statistics: (grade: number | 'UNSET' | null = null, signal?: AbortSignal) =>
    api<TeacherStatistics>('/teacher/statistics?' + statisticsParams(grade), { signal }),
  insights: (
    grade: number | 'UNSET' | null,
    range: StatisticsRange,
    page: number,
    signal?: AbortSignal,
  ) =>
    api<TeacherInsights>('/teacher/statistics/insights?' + statisticsParams(grade, range, page), {
      signal,
    }),
  exportStatistics: (grade: number | 'UNSET' | null, range: StatisticsRange) =>
    download('/teacher/statistics/export?' + statisticsParams(grade, range), '指导统计明细.xlsx'),
  majors: (signal?: AbortSignal) => api<Major[]>('/teacher/majors', { signal }),
  createStudent: (input: {
    username: string
    name: string
    grade: number
    major?: string | null
  }) => api<User>('/teacher/students', { method: 'POST', json: input }),
  template: () => download('/teacher/students/imports/template', '指导学生导入模板.xlsx'),
  preview: (file: File) => importStudents('preview', file),
  execute: (file: File) => importStudents('execute', file),
}
