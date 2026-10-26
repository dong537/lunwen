import { createContext, Fragment, useContext, useEffect, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, clearCsrfCache } from '../../shared/api/http'
import type { User, Role } from '../../shared/api/contracts'

export const roleNames: Record<Role, string> = { STUDENT: '学生', TEACHER: '教师', ADMIN: '管理员' }
export const homeFor = (role: Role) =>
  role === 'STUDENT' ? '/student/submissions' : role === 'TEACHER' ? '/teacher/students' : '/admin'
const SessionContext = createContext<{
  user: User | null | undefined
  loading: boolean
  error: Error | null
  refresh: () => void
  setUser: (user: User | null) => Promise<void>
} | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient()
  async function clearBusinessData() {
    await client.cancelQueries({ predicate: (query) => query.queryKey[0] !== 'session' })
    client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' })
  }
  const session = useQuery({
    queryKey: ['session'],
    queryFn: async ({ signal }) => {
      try {
        const next = await api<User>('/auth/me', { signal })
        const previous = client.getQueryData<User | null>(['session'])
        if (
          previous?.id !== next.id ||
          previous?.role !== next.role ||
          previous?.mustChangePassword !== next.mustChangePassword
        ) {
          // 其他页签也能改变会话；在发布新身份前清理旧账号的服务器数据。
          await clearBusinessData()
        }
        return next
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null
        throw error
      }
    },
    retry: false,
    staleTime: 30_000,
  })
  async function setUser(user: User | null) {
    clearCsrfCache()
    await client.cancelQueries({ queryKey: ['session'], exact: true })
    await clearBusinessData()
    client.setQueryData(['session'], user)
  }
  useEffect(() => {
    const handleAuth = (event: Event) => {
      const error = (event as CustomEvent<ApiError>).detail
      if (error.status === 401) void setUser(null)
      else void client.invalidateQueries({ queryKey: ['session'] })
    }
    window.addEventListener('platform:auth', handleAuth)
    return () => window.removeEventListener('platform:auth', handleAuth)
  }, [client])
  return (
    <SessionContext.Provider
      value={{
        user: session.data,
        loading: session.isPending,
        error: session.error,
        refresh: () => void session.refetch(),
        setUser,
      }}
    >
      <Fragment key={`${session.data?.id ?? 'anonymous'}:${session.data?.role ?? ''}`}>
        {children}
      </Fragment>
    </SessionContext.Provider>
  )
}
export function useSession() {
  const value = useContext(SessionContext)
  if (!value) throw new Error('会话组件必须位于 SessionProvider 内')
  return value
}
