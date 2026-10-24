import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import {
  Button,
  Card,
  Col,
  Empty,
  Input,
  message,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
} from 'antd'
import { ExclamationCircleOutlined, PlusOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import type { AccountRole, User } from '../../shared/api/contracts'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { userApi } from './api'
import { UserForm } from './UserForm'

export function AdminDashboardPage() {
  useDocumentTitle('平台概览')
  const dashboard = useQuery({
    queryKey: ['dashboard'],
    queryFn: ({ signal }) => userApi.dashboard(signal),
  })
  return (
    <>
      <PageHeader title="平台概览" description="管理学生与教师账号，查看平台使用情况。" />
      <ApiAlert error={dashboard.error} retry={() => void dashboard.refetch()} />
      {dashboard.isPending && <CenterSpin />}
      {dashboard.data && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col xs={24} sm={8}>
            <Card>
              <Statistic title="启用学生" value={dashboard.data.activeStudents} />
            </Card>
          </Col>
          <Col xs={24} sm={8}>
            <Card>
              <Statistic title="启用教师" value={dashboard.data.activeTeachers} />
            </Card>
          </Col>
          <Col xs={24} sm={8}>
            <Card>
              <Statistic title="累计提交" value={dashboard.data.totalSubmissions} />
            </Card>
          </Col>
        </Row>
      )}
      <Card title="账号维护">
        <p style={{ color: 'rgba(0,0,0,0.45)' }}>
          新建账号或从 Excel 导入，分配指导教师，维护账号状态。
        </p>
        <Space>
          <Link to="/admin/users">
            <Button type="primary">管理账号</Button>
          </Link>
          <Link to="/admin/imports">
            <Button>批量导入</Button>
          </Link>
        </Space>
      </Card>
    </>
  )
}

export function UsersPage() {
  useDocumentTitle('账号管理')
  const [role, setRole] = useState<AccountRole>('STUDENT')
  const [q, setQ] = useState('')
  const [active, setActive] = useState('')
  const [page, setPage] = useState(1)
  const client = useQueryClient()
  const [form, setForm] = useState<{ user: User | null } | null>(null)
  const list = useQuery({
    queryKey: ['users', role, q, active, page],
    queryFn: ({ signal }) => userApi.list(role, q, active, page, signal),
  })
  const action = useMutation({
    mutationFn: async ({ kind, user }: { kind: 'active' | 'reset'; user: User }) => {
      if (kind === 'reset') {
        await userApi.reset(user.id)
        return `已将 ${user.name}（${user.username}）的密码重置为 123456，下次登录需重新设置密码。`
      }
      const result = await userApi.active(user.id, !user.active)
      const notice =
        !result.user.active && user.role === 'TEACHER' && result.assignedStudents > 0
          ? `该教师名下还有 ${result.assignedStudents} 名学生，请及时重新分配。`
          : ''
      return `已${result.user.active ? '启用' : '停用'} ${user.name}（${user.username}）。${result.message || notice}`
    },
    onSuccess: (notice) => {
      void client.invalidateQueries({ queryKey: ['users'] })
      void client.invalidateQueries({ queryKey: ['teacher-options'] })
      void client.invalidateQueries({ queryKey: ['dashboard'] })
      void message.success(notice)
    },
  })
  function confirmAction(kind: 'active' | 'reset', user: User) {
    const title = kind === 'reset' ? '重置密码' : user.active ? '停用账号' : '启用账号'
    const content =
      kind === 'reset'
        ? `密码将重置为 123456，当前登录会失效，下次登录必须重新设置密码。账号：${user.name}（${user.username}）`
        : user.active
          ? `停用后该账号无法登录，已有论文与提交记录保留。账号：${user.name}（${user.username}）`
          : `启用后该账号可以继续登录使用。账号：${user.name}（${user.username}）`
    Modal.confirm({
      title,
      icon: <ExclamationCircleOutlined />,
      content,
      okText: kind === 'reset' ? '确认重置' : user.active ? '确认停用' : '确认启用',
      okType: kind === 'reset' || user.active ? 'danger' : 'primary',
      cancelText: '取消',
      onOk: () => action.mutateAsync({ kind, user }),
    })
  }
  const columns: ColumnsType<User> = [
    {
      title: role === 'STUDENT' ? '学号' : '工号',
      dataIndex: 'username',
      key: 'username',
    },
    { title: '姓名', dataIndex: 'name', key: 'name' },
    ...(role === 'STUDENT'
      ? [
          {
            title: '专业',
            dataIndex: 'major',
            key: 'major',
            render: (value: string | null) => value || '—',
          },
          {
            title: '指导教师',
            dataIndex: 'teacherName',
            key: 'teacherName',
            render: (value: string | null) => value || '未分配',
          },
        ]
      : []),
    {
      title: '状态',
      dataIndex: 'active',
      key: 'active',
      width: 100,
      render: (value: boolean) =>
        value ? <Tag color="success">启用</Tag> : <Tag color="default">停用</Tag>,
    },
    {
      title: '操作',
      key: 'action',
      width: 240,
      render: (_: unknown, user: User) => (
        <Space>
          <Button
            type="link"
            size="small"
            onClick={() => setForm({ user })}
            aria-label={`编辑${user.name}`}
          >
            编辑
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => confirmAction('active', user)}
            aria-label={`${user.active ? '停用' : '启用'}${user.name}`}
          >
            {user.active ? '停用' : '启用'}
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => confirmAction('reset', user)}
            aria-label={`重置${user.name}的密码`}
          >
            重置密码
          </Button>
        </Space>
      ),
    },
  ]
  return (
    <>
      <PageHeader
        title="账号管理"
        description="维护学生与教师资料、指导关系和账号状态。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ user: null })}>
            新建{role === 'STUDENT' ? '学生' : '教师'}
          </Button>
        }
      />
      <ApiAlert error={action.error} />
      <Card className="table-card" style={{ minHeight: 1091 }}>
        <Tabs
          activeKey={role}
          onChange={(key) => {
            setRole(key as AccountRole)
            setPage(1)
          }}
          items={[
            { key: 'STUDENT', label: '学生' },
            { key: 'TEACHER', label: '教师' },
          ]}
        />
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search
            placeholder="按账号或姓名搜索"
            allowClear
            enterButton="搜索"
            style={{ width: 360 }}
            onSearch={(value) => {
              setQ(value.trim())
              setPage(1)
            }}
          />
          <Select
            value={active}
            style={{ width: 130 }}
            onChange={(value) => {
              setActive(value)
              setPage(1)
            }}
            options={[
              { value: '', label: '全部状态' },
              { value: 'true', label: '启用' },
              { value: 'false', label: '停用' },
            ]}
          />
        </Space>
        {list.isPending && <CenterSpin />}
        <ApiAlert error={list.error} retry={() => void list.refetch()} />
        {list.data && (
          <Table<User>
            rowKey="id"
            size="small"
            columns={columns}
            dataSource={list.data.items}
            scroll={{ x: 'max-content' }}
            pagination={{
              current: page,
              pageSize: list.data.size,
              total: list.data.total,
              showTotal: (total) => `共 ${total} 条`,
              showQuickJumper: true,
              onChange: setPage,
            }}
            locale={{
              emptyText: (
                <Empty
                  description={
                    q || active ? '没有符合条件的账号。' : '还没有账号，可新建或批量导入。'
                  }
                />
              ),
            }}
          />
        )}
      </Card>
      {form && (
        <UserForm
          user={form.user}
          role={role}
          onClose={() => setForm(null)}
          onSaved={() => {
            setForm(null)
            void client.invalidateQueries({ queryKey: ['users'] })
            void client.invalidateQueries({ queryKey: ['teacher-options'] })
            void client.invalidateQueries({ queryKey: ['dashboard'] })
          }}
        />
      )}
    </>
  )
}
