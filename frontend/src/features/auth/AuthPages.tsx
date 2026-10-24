import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, Navigate } from 'react-router-dom'
import { Button, Card, Descriptions, Form, Input, message, Typography } from 'antd'
import { api } from '../../shared/api/http'
import type { User } from '../../shared/api/contracts'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { homeFor, roleNames, useSession } from './session'

export function LoginPage() {
  const { user, loading, error, refresh, setUser } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  useDocumentTitle('登录')
  const login = useMutation({
    mutationFn: () =>
      api<User>('/auth/login', { method: 'POST', json: { username: username.trim(), password } }),
    onSuccess: (next) => {
      void setUser(next)
      void message.success(`登录成功，欢迎回来，${next.name}`)
    },
    onError: (error) => {
      void message.error(error instanceof Error ? error.message : '登录失败，请重试')
    },
  })
  if (loading) return <CenterSpin />
  if (user)
    return (
      <Navigate to={user.mustChangePassword ? '/change-password' : homeFor(user.role)} replace />
    )
  return (
    <div className="login-page">
      <Card className="login-card" variant="borderless">
        <Typography.Title level={4} style={{ textAlign: 'center', marginBottom: 4 }}>
          论文审核平台
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center' }}>
          山东石油化工学院
        </Typography.Paragraph>
        <Typography.Title level={5}>登录账号</Typography.Title>
        <Typography.Paragraph type="secondary">
          使用管理员分配的学号或工号登录。
        </Typography.Paragraph>
        <ApiAlert error={error} retry={refresh} />
        <Form layout="vertical" onFinish={() => login.mutate()}>
          <Form.Item label="账号" htmlFor="login-username">
            <Input
              id="login-username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              maxLength={32}
              autoFocus
            />
          </Form.Item>
          <Form.Item label="密码" htmlFor="login-password">
            <Input.Password
              id="login-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              maxLength={256}
            />
          </Form.Item>
          <ApiAlert error={login.error} />
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={login.isPending}
              disabled={!username.trim() || !password}
            >
              登录
            </Button>
          </Form.Item>
        </Form>
        <Typography.Paragraph type="secondary">
          忘记密码或账号被停用，请联系管理员。
        </Typography.Paragraph>
      </Card>
    </div>
  )
}

export function PasswordPage() {
  const { user, setUser } = useSession()
  useDocumentTitle(user?.mustChangePassword ? '设置新密码' : '修改密码')
  const [form] = Form.useForm()
  const change = useMutation({
    mutationFn: (values: { oldPassword: string; newPassword: string; confirmPassword: string }) =>
      api<User>('/auth/password', { method: 'POST', json: values }),
    onSuccess: (updated) => {
      void setUser(updated)
    },
  })
  if (change.isSuccess && user && !user.mustChangePassword) {
    return <Navigate to={homeFor(user.role)} replace />
  }
  return (
    <div style={{ maxWidth: 640 }}>
      <PageHeader
        title={user?.mustChangePassword ? '设置新密码' : '修改密码'}
        description={
          user?.mustChangePassword
            ? '首次登录或密码重置后，请先设置自己的密码。'
            : '新密码需为 6–128 个字符，不能与当前密码相同。'
        }
      />
      <Card>
        <Form form={form} layout="vertical" onFinish={(values) => change.mutate(values)}>
          <Form.Item
            label="当前密码"
            name="oldPassword"
            htmlFor="password-old"
            rules={[{ required: true, message: '请填写当前密码' }]}
          >
            <Input.Password id="password-old" autoComplete="current-password" />
          </Form.Item>
          <Form.Item
            label="新密码"
            name="newPassword"
            htmlFor="password-new"
            rules={[
              { required: true, message: '请填写新密码' },
              { min: 6, max: 128, message: '新密码需为 6–128 个字符' },
              ({ getFieldValue }) => ({
                validator(_, value: string) {
                  if (!value || value !== getFieldValue('oldPassword')) return Promise.resolve()
                  return Promise.reject(new Error('新密码不能与当前密码相同'))
                },
              }),
            ]}
          >
            <Input.Password id="password-new" autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            label="再次输入新密码"
            name="confirmPassword"
            htmlFor="password-confirm"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次填写新密码' },
              ({ getFieldValue }) => ({
                validator(_, value: string) {
                  if (!value || value === getFieldValue('newPassword')) return Promise.resolve()
                  return Promise.reject(new Error('两次输入的新密码不一致'))
                },
              }),
            ]}
          >
            <Input.Password id="password-confirm" autoComplete="new-password" />
          </Form.Item>
          <ApiAlert error={change.error} />
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={change.isPending}>
              保存新密码
            </Button>
            {!user?.mustChangePassword && (
              <Link to="/profile" style={{ marginLeft: 12 }}>
                <Button>取消</Button>
              </Link>
            )}
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export function ProfilePage() {
  const { user } = useSession()
  useDocumentTitle('个人信息')
  if (!user) return null
  return (
    <div style={{ maxWidth: 720 }}>
      <PageHeader title="个人信息" description="如需更正个人信息，请联系管理员。" />
      <Card>
        <Descriptions column={1} bordered>
          <Descriptions.Item label="姓名">{user.name}</Descriptions.Item>
          <Descriptions.Item label={user.role === 'STUDENT' ? '学号' : '账号'}>
            {user.username}
          </Descriptions.Item>
          <Descriptions.Item label="身份">{roleNames[user.role]}</Descriptions.Item>
          {user.role === 'STUDENT' && (
            <>
              <Descriptions.Item label="专业">{user.major || '未填写'}</Descriptions.Item>
              <Descriptions.Item label="指导教师">{user.teacherName || '未分配'}</Descriptions.Item>
            </>
          )}
          <Descriptions.Item label="账号状态">{user.active ? '启用' : '停用'}</Descriptions.Item>
        </Descriptions>
        <Link to="/change-password">
          <Button style={{ marginTop: 16 }}>修改密码</Button>
        </Link>
      </Card>
    </div>
  )
}
