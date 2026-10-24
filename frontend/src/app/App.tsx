import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { Avatar, Button, Dropdown, Layout, Menu, Space, theme, Typography } from 'antd'
import {
  DashboardOutlined,
  DownOutlined,
  FileTextOutlined,
  ImportOutlined,
  LogoutOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { api } from '../shared/api/http'
import type { Role } from '../shared/api/contracts'
import { ApiAlert, CenterSpin, PageHeader } from '../shared/ui'
import { LoginPage, PasswordModal, ProfilePage } from '../features/auth/AuthPages'
import { homeFor, roleNames, SessionProvider, useSession } from '../features/auth/session'
import {
  StudentSubmissionsPage,
  SubmissionDetailPage,
} from '../features/submissions/SubmissionPages'
import { TeacherStudentDetailPage, TeacherStudentsPage } from '../features/students/TeacherPages'
import { AdminDashboardPage, UsersPage } from '../features/users/UserPages'
import { ImportsPage } from '../features/imports/ImportsPage'

const { Header, Sider, Content } = Layout

function RequireSession() {
  const { user, loading, error, refresh } = useSession()
  if (loading) return <CenterSpin tip="正在确认登录状态…" />
  if (error)
    return (
      <div style={{ maxWidth: 640, margin: '80px auto', padding: 24 }}>
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <ApiAlert error={error} retry={refresh} />
        </Space>
      </div>
    )
  return user ? <Outlet /> : <Navigate to="/login" replace />
}

function RoleGuard({ roles }: { roles: Role[] }) {
  const { user } = useSession()
  if (user && roles.includes(user.role)) return <Outlet />
  return (
    <>
      <PageHeader title="无权访问" description="你的账号没有访问此页面的权限。" />
      {user && (
        <Link to={homeFor(user.role)}>
          <Button type="link">返回工作台</Button>
        </Link>
      )}
    </>
  )
}

function Home() {
  const { user } = useSession()
  return <Navigate to={user ? homeFor(user.role) : '/login'} replace />
}

function ForcePasswordModal() {
  const { user } = useSession()
  if (!user?.mustChangePassword) return null
  return <PasswordModal open mandatory onClose={() => {}} />
}

function selectedKeyFor(role: Role, pathname: string): string {
  if (role === 'STUDENT') {
    if (pathname === '/profile') return '/profile'
    return '/student/submissions'
  }
  if (role === 'TEACHER') {
    if (pathname === '/profile') return '/profile'
    return '/teacher/students'
  }
  if (pathname.startsWith('/admin/users')) return '/admin/users'
  if (pathname.startsWith('/admin/imports')) return '/admin/imports'
  if (pathname === '/profile') return '/profile'
  return '/admin'
}

function Shell() {
  const { user, setUser } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  const {
    token: { colorPrimaryBorder },
  } = theme.useToken()
  const logout = useMutation({
    mutationFn: () => api('/auth/logout', { method: 'POST' }),
    onSuccess: () => {
      void setUser(null)
      navigate('/login', { replace: true })
    },
  })
  if (!user) return null
  const menuItems =
    user.role === 'STUDENT'
      ? [
          { key: '/student/submissions', icon: <FileTextOutlined />, label: '我的论文' },
          { key: '/profile', icon: <UserOutlined />, label: '个人信息' },
        ]
      : user.role === 'TEACHER'
        ? [
            { key: '/teacher/students', icon: <TeamOutlined />, label: '我的学生' },
            { key: '/profile', icon: <UserOutlined />, label: '个人信息' },
          ]
        : [
            { key: '/admin', icon: <DashboardOutlined />, label: '平台概览' },
            { key: '/admin/users', icon: <TeamOutlined />, label: '账号管理' },
            { key: '/admin/imports', icon: <ImportOutlined />, label: '批量导入' },
            { key: '/profile', icon: <UserOutlined />, label: '个人信息' },
          ]
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <a className="skip-link" href="#main-content">
        跳到主要内容
      </a>
      <Sider
        theme="dark"
        width={220}
        breakpoint="md"
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
      >
        <Link
          to={homeFor(user.role)}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            height: 64,
            padding: '0 20px',
            color: 'inherit',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
          }}
        >
          <img
            src="/brand-cap.svg"
            alt=""
            width={36}
            height={36}
            style={{ verticalAlign: 'middle', flex: 'none' }}
          />
          {!collapsed && (
            <span>
              <Typography.Text strong style={{ color: '#fff' }}>
                论文审核平台
              </Typography.Text>
              <br />
              <Typography.Text style={{ fontSize: 11, color: 'rgba(255,255,255,0.65)' }}>
                山东石油化工学院
              </Typography.Text>
            </span>
          )}
        </Link>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKeyFor(user.role, location.pathname)]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#001529',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            borderTop: `3px solid ${colorPrimaryBorder}`,
          }}
        >
          <Dropdown
            menu={{
              items: [
                {
                  key: 'logout',
                  icon: <LogoutOutlined />,
                  label: '退出登录',
                  onClick: () => logout.mutate(),
                },
              ],
            }}
          >
            <Button type="text" style={{ color: '#fff' }}>
              <Space>
                <Avatar size="small" icon={<UserOutlined />} />
                {user.name}
                <Typography.Text style={{ color: 'rgba(255,255,255,0.65)' }}>
                  {roleNames[user.role]}
                </Typography.Text>
                <DownOutlined style={{ fontSize: 12 }} />
              </Space>
            </Button>
          </Dropdown>
        </Header>
        <Content style={{ margin: 24 }}>
          <div id="main-content" tabIndex={-1} style={{ outline: 'none' }}>
            <ApiAlert error={logout.error} />
            <Outlet />
          </div>
        </Content>
      </Layout>
      <ForcePasswordModal />
    </Layout>
  )
}

export function App() {
  return (
    <SessionProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireSession />}>
          <Route element={<Shell />}>
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
                  <PageHeader title="页面不存在" />
                  <Link to="/">返回工作台</Link>
                </>
              }
            />
          </Route>
        </Route>
      </Routes>
    </SessionProvider>
  )
}
