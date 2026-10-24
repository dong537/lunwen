import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Navigate } from 'react-router-dom'
import {
  Alert,
  Avatar,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  message,
  Modal,
  Space,
  Tag,
  Typography,
} from 'antd'
import { CheckCircleOutlined, UserOutlined } from '@ant-design/icons'
import { api } from '../../shared/api/http'
import type { User } from '../../shared/api/contracts'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { homeFor, roleNames, useSession } from './session'

export function LoginPage() {
  const { user, loading, error, refresh, setUser } = useSession()
  useDocumentTitle('登录')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
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
  if (user) return <Navigate to={homeFor(user.role)} replace />
  return (
    <div className="login-page">
      <div className="login-wrap">
        <aside className="login-brand" aria-label="平台介绍">
          <img className="login-brand-mark" src="/brand-cap.svg" alt="" />
          <Typography.Title level={3} className="login-brand-title">
            论文审核平台
          </Typography.Title>
          <Typography.Paragraph className="login-brand-school">
            山东石油化工学院
          </Typography.Paragraph>
          <ul className="login-brand-points">
            <li>
              <CheckCircleOutlined aria-hidden="true" />
              在线提交论文，版本自动留存
            </li>
            <li>
              <CheckCircleOutlined aria-hidden="true" />
              实时跟踪审核进度与问题统计
            </li>
            <li>
              <CheckCircleOutlined aria-hidden="true" />
              下载批注稿与 Word 检查报告
            </li>
          </ul>
        </aside>
        <div className="login-form">
          <Typography.Title level={5}>登录账号</Typography.Title>
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
        </div>
      </div>
    </div>
  )
}

export function PasswordModal({
  open,
  mandatory,
  onClose,
}: {
  open: boolean
  mandatory?: boolean
  onClose: () => void
}) {
  const { setUser } = useSession()
  const [form] = Form.useForm()
  const change = useMutation({
    mutationFn: (values: { oldPassword: string; newPassword: string; confirmPassword: string }) =>
      api<User>('/auth/password', { method: 'POST', json: values }),
    onSuccess: (updated) => {
      void setUser(updated)
      void message.success('密码已更新')
      form.resetFields()
      if (!mandatory) onClose()
    },
  })
  return (
    <Modal
      title={mandatory ? '设置新密码' : '修改密码'}
      open={open}
      onOk={() => form.submit()}
      onCancel={mandatory ? undefined : onClose}
      closable={!mandatory}
      maskClosable={!mandatory}
      keyboard={!mandatory}
      okText="保存新密码"
      cancelText="取消"
      cancelButtonProps={mandatory ? { hidden: true } : undefined}
      confirmLoading={change.isPending}
      destroyOnClose
    >
      {mandatory && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="首次登录或密码重置后，请先设置自己的密码。"
        />
      )}
      <Form
        form={form}
        layout="vertical"
        preserve={false}
        onFinish={(values) => change.mutate(values)}
      >
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
      </Form>
    </Modal>
  )
}

export function ProfilePage() {
  const { user } = useSession()
  useDocumentTitle('个人信息')
  const [pwdOpen, setPwdOpen] = useState(false)
  if (!user) return null
  return (
    <>
      <PageHeader title="个人信息" description="如需更正个人信息，请联系管理员。" />
      <Card style={{ marginBottom: 16 }}>
        <Space size={16} align="center" wrap>
          <Avatar size={64} icon={<UserOutlined />} style={{ backgroundColor: '#1677ff' }} />
          <div>
            <Typography.Title level={4} style={{ margin: 0 }}>
              {user.name}
            </Typography.Title>
            <Space style={{ marginTop: 8 }} wrap>
              <Tag color="blue">{roleNames[user.role]}</Tag>
              <Tag color={user.active ? 'success' : 'default'}>{user.active ? '启用' : '停用'}</Tag>
              {user.mustChangePassword && <Tag color="warning">需重设密码</Tag>}
            </Space>
          </div>
        </Space>
      </Card>
      <Card title="账号详情" extra={<Button onClick={() => setPwdOpen(true)}>修改密码</Button>}>
        <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} layout="vertical" bordered>
          <Descriptions.Item label="姓名">{user.name}</Descriptions.Item>
          <Descriptions.Item label={user.role === 'STUDENT' ? '学号' : '账号'}>
            {user.username}
          </Descriptions.Item>
          <Descriptions.Item label="身份">{roleNames[user.role]}</Descriptions.Item>
          <Descriptions.Item label="账号状态">{user.active ? '启用' : '停用'}</Descriptions.Item>
          {user.role === 'STUDENT' && (
            <>
              <Descriptions.Item label="专业">{user.major || '未填写'}</Descriptions.Item>
              <Descriptions.Item label="指导教师">{user.teacherName || '未分配'}</Descriptions.Item>
            </>
          )}
        </Descriptions>
      </Card>
      <PasswordModal open={pwdOpen} onClose={() => setPwdOpen(false)} />
    </>
  )
}
