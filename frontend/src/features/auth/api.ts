import { api } from '../../shared/api/http'
import type { College, User } from '../../shared/api/contracts'

export const authApi = {
  colleges: (signal?: AbortSignal) => api<College[]>('/auth/colleges', { signal }),
  login: (input: { username: string; password: string; collegeCode: string | null }) =>
    api<User>('/auth/login', { method: 'POST', json: input }),
}
