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
    signal?: AbortSignal,
  ) =>
    api<Page<User>>(
      `/admin/users?role=${role}&q=${encodeURIComponent(q)}${active ? `&active=${active}` : ''}${collegeCode ? `&collegeCode=${encodeURIComponent(collegeCode)}` : ''}&page=${page}&size=10`,
      { signal },
    ),
  teachers: (collegeCode: string, q: string, signal?: AbortSignal) =>
    api<User[]>(
      `/admin/teachers?collegeCode=${encodeURIComponent(collegeCode)}&q=${encodeURIComponent(q)}`,
      { signal },
    ),
  catalog: (signal?: AbortSignal) => api<SchoolCatalog>('/admin/catalog', { signal }),
  majors: (collegeCode: string, signal?: AbortSignal) =>
    api<Major[]>(`/admin/majors?collegeCode=${encodeURIComponent(collegeCode)}`, { signal }),
  createMajor: (collegeCode: string, name: string) =>
    api<Major>('/admin/majors', { method: 'POST', json: { collegeCode, name } }),
  setMajorActive: (id: string, active: boolean) =>
    api<Major>(`/admin/majors/${encodeURIComponent(id)}/active`, {
      method: 'PATCH',
      json: { active },
    }),
  create: (input: UserInput) => api<User>('/admin/users', { method: 'POST', json: input }),
  update: (id: string, input: Pick<UserInput, 'name' | 'major' | 'teacherId' | 'collegeCode'>) =>
    api<User>(`/admin/users/${encodeURIComponent(id)}`, { method: 'PATCH', json: input }),
  remove: (id: string) =>
    api<{ outcome: 'DELETED' | 'DEACTIVATED'; user: User | null; message: string }>(
      `/admin/users/${encodeURIComponent(id)}`,
      { method: 'DELETE' },
    ),
  active: (id: string, active: boolean) =>
    api<{ user: User; assignedStudents: number; message: string | null }>(
      `/admin/users/${encodeURIComponent(id)}/active`,
      { method: 'POST', json: { active } },
    ),
  reset: (id: string) =>
    api<User>(`/admin/users/${encodeURIComponent(id)}/reset-password`, { method: 'POST' }),
}
