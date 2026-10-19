import { useMutation } from '@tanstack/react-query'
import { Link, NavLink, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import {
  BookOpen,
  GraduationCap,
  Import,
  LayoutDashboard,
  LogOut,
  UserRound,
  Users,
} from 'lucide-react'
import { api } from '../shared/api/http'
import type { Role } from '../shared/api/contracts'
import { ErrorNotice, Loading, PageHeading } from '../shared/ui'
import { LoginPage, PasswordPage, ProfilePage } from '../features/auth/AuthPages'
import { homeFor, roleNames, SessionProvider, useSession } from '../features/auth/session'
import {
  StudentSubmissionsPage,
  SubmissionDetailPage,
} from '../features/submissions/SubmissionPages'
import { TeacherStudentDetailPage, TeacherStudentsPage } from '../features/students/TeacherPages'
import { AdminDashboardPage, UsersPage } from '../features/users/UserPages'
import { ImportsPage } from '../features/imports/ImportsPage'

function RequireSession() {
  const { user, loading, error, refresh } = useSession()
  if (loading) return <Loading>正在确认登录状态…</Loading>
  if (error)
    return (
      <div className="auth-error">
        <ErrorNotice error={error} retry={refresh} />
      </div>
    )
  return user ? <Outlet /> : <Navigate to="/login" replace />
}
function RequirePasswordChanged() {
  const { user } = useSession()
  return user?.mustChangePassword ? <Navigate to="/change-password" replace /> : <Outlet />
}
function RoleGuard({ roles }: { roles: Role[] }) {
  const { user } = useSession()
  return user && roles.includes(user.role) ? (
    <Outlet />
  ) : (
    <>
      <PageHeading title="无权访问" description="你的账号没有访问此页面的权限。" />
      {user && (
        <Link className="button secondary" to={homeFor(user.role)}>
          返回工作台
        </Link>
      )}
    </>
  )
}
function Home() {
  const { user } = useSession()
  return <Navigate to={user ? homeFor(user.role) : '/login'} replace />
}

function Shell() {
  const { user, setUser } = useSession()
  const logout = useMutation({
    mutationFn: () => api('/auth/logout', { method: 'POST' }),
    onSuccess: () => setUser(null),
  })
  if (!user) return null
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        跳到主要内容
      </a>
      <header className="topbar">
        <Link className="brand" to={homeFor(user.role)}>
          <span className="brand-mark" aria-hidden="true">
            论
          </span>
          <span>
            论文审核平台<small>山东石油化工学院</small>
          </span>
        </Link>
        <div className="account">
          <span>
            {user.name}
            <small>{roleNames[user.role]}</small>
          </span>
          <button
            className="button plain"
            onClick={() => logout.mutate()}
            disabled={logout.isPending}
          >
            <LogOut size={16} aria-hidden="true" />
            退出登录
          </button>
        </div>
      </header>
      <div className="workspace-layout">
        <aside className="sidebar">
          <nav aria-label="主导航">
            {!user.mustChangePassword && (
              <>
                {user.role === 'STUDENT' && (
                  <NavLink to="/student/submissions">
                    <BookOpen size={18} aria-hidden="true" />
                    我的论文
                  </NavLink>
                )}
                {user.role === 'TEACHER' && (
                  <NavLink to="/teacher/students">
                    <GraduationCap size={18} aria-hidden="true" />
                    我的学生
                  </NavLink>
                )}
                {user.role === 'ADMIN' && (
                  <>
                    <NavLink to="/admin" end>
                      <LayoutDashboard size={18} aria-hidden="true" />
                      平台概览
                    </NavLink>
                    <NavLink to="/admin/users">
                      <Users size={18} aria-hidden="true" />
                      账号管理
                    </NavLink>
                    <NavLink to="/admin/imports">
                      <Import size={18} aria-hidden="true" />
                      批量导入
                    </NavLink>
                  </>
                )}
                <NavLink to="/profile">
                  <UserRound size={18} aria-hidden="true" />
                  个人信息
                </NavLink>
              </>
            )}
            {user.mustChangePassword && (
              <NavLink to="/change-password">
                <UserRound size={18} aria-hidden="true" />
                设置新密码
              </NavLink>
            )}
          </nav>
          <p className="sidebar-note">
            论文版本与审核记录
            <br />
            在这里统一管理
          </p>
        </aside>
        <main id="main-content" className="workspace" tabIndex={-1}>
          <ErrorNotice error={logout.error} />
          <Outlet />
        </main>
      </div>
    </div>
  )
}

export function App() {
  return (
    <SessionProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireSession />}>
          <Route element={<Shell />}>
            <Route path="/change-password" element={<PasswordPage />} />
            <Route element={<RequirePasswordChanged />}>
              <Route index element={<Home />} />
              <Route path="/profile" element={<ProfilePage />} />
              <Route element={<RoleGuard roles={['STUDENT']} />}>
                <Route path="/student/submissions" element={<StudentSubmissionsPage />} />
              </Route>
              <Route element={<RoleGuard roles={['STUDENT', 'TEACHER']} />}>
                <Route path="/submissions/:id" element={<SubmissionDetailPage />} />
              </Route>
              <Route element={<RoleGuard roles={['TEACHER']} />}>
                <Route path="/teacher/students" element={<TeacherStudentsPage />} />
                <Route path="/teacher/students/:id" element={<TeacherStudentDetailPage />} />
              </Route>
              <Route element={<RoleGuard roles={['ADMIN']} />}>
                <Route path="/admin" element={<AdminDashboardPage />} />
                <Route path="/admin/users" element={<UsersPage />} />
                <Route path="/admin/imports" element={<ImportsPage />} />
              </Route>
              <Route
                path="*"
                element={
                  <>
                    <PageHeading title="页面不存在" />
                    <Link to="/">返回工作台</Link>
                  </>
                }
              />
            </Route>
          </Route>
        </Route>
      </Routes>
    </SessionProvider>
  )
}
