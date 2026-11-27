import { api } from '../../shared/api/http'
import type {
  Dashboard,
  Major,
  Page,
  Role,
  SchoolCatalog,
  User,
  UserInput,
} from '../../shared/api/contracts'

export const userApi = {
  dashboard: (signal?: AbortSignal) => api<Dashboard>('/admin/dashboard', { signal }),
  list: (
    role: Role,
    q: string,
    active: string,
    page: number,
    collegeCode?: string,
    grade?: number | 'UNSET' | null,
    signal?: AbortSignal,
  ) => {
    const params = new URLSearchParams({ role, q, page: String(page), size: '10' })
    if (active) params.set('active', active)
    if (collegeCode) params.set('collegeCode', collegeCode)
    if (role === 'STUDENT' && grade != null) params.set('grade', String(grade))
    return api<Page<User>>('/admin/users?' + params, { signal })
  },
  teachers: (collegeCode: string, q: string, page: number, signal?: AbortSignal) => {
    const params = new URLSearchParams({ collegeCode, q, page: String(page), size: '50' })
    return api<Page<User>>('/admin/teachers?' + params, { signal })
  },
  catalog: (signal?: AbortSignal) => api<SchoolCatalog>('/admin/catalog', { signal }),
  majors: (collegeCode: string, signal?: AbortSignal) =>
    api<Major[]>('/admin/majors?collegeCode=' + encodeURIComponent(collegeCode), { signal }),
  createMajor: (collegeCode: string, name: string) =>
    api<Major>('/admin/majors', { method: 'POST', json: { collegeCode, name } }),
  renameMajor: (id: string, name: string) =>
    api<Major>('/admin/majors/' + encodeURIComponent(id), { method: 'PATCH', json: { name } }),
  setMajorActive: (id: string, active: boolean) =>
    api<Major>('/admin/majors/' + encodeURIComponent(id) + '/active', {
      method: 'PATCH',
      json: { active },
    }),
  create: (input: UserInput) => api<User>('/admin/users', { method: 'POST', json: input }),
  update: (
    id: string,
    input: Pick<UserInput, 'name' | 'major' | 'grade' | 'teacherId' | 'collegeCode'>,
  ) => api<User>('/admin/users/' + encodeURIComponent(id), { method: 'PATCH', json: input }),
  remove: (id: string) =>
    api<{ outcome: 'DELETED' | 'DEACTIVATED'; user: User | null; message: string }>(
      '/admin/users/' + encodeURIComponent(id),
      { method: 'DELETE' },
    ),
  active: (id: string, active: boolean) =>
    api<{ user: User; assignedStudents: number; message: string | null }>(
      '/admin/users/' + encodeURIComponent(id) + '/active',
      { method: 'POST', json: { active } },
    ),
  reset: (id: string) =>
    api<User>('/admin/users/' + encodeURIComponent(id) + '/reset-password', { method: 'POST' }),
}
