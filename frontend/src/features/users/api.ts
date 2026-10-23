import { api } from '../../shared/api/http'
import type { AccountRole, Dashboard, Page, User, UserInput } from '../../shared/api/contracts'

export const userApi = {
  dashboard: (signal?: AbortSignal) => api<Dashboard>('/admin/dashboard', { signal }),
  list: (role: AccountRole, q: string, active: string, page: number, signal?: AbortSignal) =>
    api<Page<User>>(
      `/admin/users?role=${role}&q=${encodeURIComponent(q)}${active ? `&active=${active}` : ''}&page=${page}&size=20`,
      { signal },
    ),
  teachers: (q: string, signal?: AbortSignal) =>
    api<User[]>(`/admin/teachers?q=${encodeURIComponent(q)}`, { signal }),
  create: (input: UserInput) => api<User>('/admin/users', { method: 'POST', json: input }),
  update: (id: string, input: Pick<UserInput, 'name' | 'major' | 'teacherId'>) =>
    api<User>(`/admin/users/${encodeURIComponent(id)}`, { method: 'PATCH', json: input }),
  active: (id: string, active: boolean) =>
    api<{ user: User; assignedStudents: number; message: string | null }>(
      `/admin/users/${encodeURIComponent(id)}/active`,
      { method: 'POST', json: { active } },
    ),
  reset: (id: string) =>
    api<User>(`/admin/users/${encodeURIComponent(id)}/reset-password`, { method: 'POST' }),
}
