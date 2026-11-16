import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import {
  App as AntdApp,
  Button,
  Card,
  Col,
  Empty,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
} from 'antd'
import { DeleteOutlined, ExclamationCircleOutlined, PlusOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import type { Role, User } from '../../shared/api/contracts'
import { DailyTrend, RankedBars } from '../../shared/AnalyticsVisuals'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { useSession } from '../auth/session'
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
        <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
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
      {dashboard.data && (
        <>
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} lg={12}>
              <Card title="近 30 天提交趋势" style={{ height: '100%' }}>
                <DailyTrend points={dashboard.data.dailySubmissions} label="平台每日论文提交次数" />
              </Card>
            </Col>
            <Col xs={24} lg={12}>
              <Card title="提交状态" style={{ height: '100%' }}>
                <RankedBars
                  items={dashboard.data.statusDistribution.map((item) => ({
                    key: item.status,
                    label: {
                      PENDING: '等待审核',
                      PROCESSING: '审核中',
                      SUCCESS: '审核成功',
                      FAILED: '审核失败',
                    }[item.status],
                    value: item.count,
                  }))}
                  emptyText="暂无论文提交"
                />
              </Card>
            </Col>
          </Row>
          <Card title="学院学生分布" style={{ marginBottom: 16 }}>
            <RankedBars
              items={dashboard.data.studentsByCollege.map((item) => ({
                key: item.collegeCode ?? 'UNASSIGNED',
                label: item.collegeName,
                value: item.count,
              }))}
              emptyText="当前范围内没有启用学生"
            />
          </Card>
        </>
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
  const { message, modal } = AntdApp.useApp()
  const { user: currentUser } = useSession()
  const [role, setRole] = useState<Role>('STUDENT')
  const [q, setQ] = useState('')
  const [active, setActive] = useState('')
  const [collegeFilter, setCollegeFilter] = useState('')
  const [gradeDraft, setGradeDraft] = useState<number | null>(null)
  const [grade, setGrade] = useState<number | 'UNSET' | null>(null)
  const [page, setPage] = useState(1)
  const client = useQueryClient()
  const [form, setForm] = useState<{ user: User | null } | null>(null)
  const catalog = useQuery({
    queryKey: ['school-catalog'],
    queryFn: ({ signal }) => userApi.catalog(signal),
  })
  const list = useQuery({
    queryKey: ['users', currentUser?.collegeCode, role, q, active, collegeFilter, grade, page],
    queryFn: ({ signal }) =>
      userApi.list(role, q, active, page, currentUser?.collegeCode ?? collegeFilter, grade, signal),
  })
  const remove = useMutation({
    mutationFn: (user: User) => userApi.remove(user.id),
    onError: (error) => {
      void message.error(error instanceof Error ? error.message : '账号处理失败，请稍后重试。')
    },
    onSuccess: (result) => {
      void client.invalidateQueries({ queryKey: ['users'] })
      void client.invalidateQueries({ queryKey: ['teacher-options'] })
      void client.invalidateQueries({ queryKey: ['dashboard'] })
      void message.success(
        result.message ||
          (result.outcome === 'DELETED' ? '账号已删除。' : '账号已停用，历史记录已保留。'),
      )
    },
  })
  function confirmRemove(user: User) {
    modal.confirm({
      title: '删除账号',
      icon: <ExclamationCircleOutlined />,
      content:
        user.name +
        '（' +
        user.username +
        '）：无关联记录时永久删除；如有关联学生或论文，则停用账号并保留历史。',
      okText: '确认处理',
      okType: 'danger',
      cancelText: '取消',
      onOk: () => remove.mutateAsync(user).then(() => undefined),
    })
  }
  const action = useMutation({
    mutationFn: async ({ kind, user }: { kind: 'active' | 'reset'; user: User }) => {
      if (kind === 'reset') {
        const updated = await userApi.reset(user.id)
        return `已将 ${user.name}（${user.username}）的密码重置为 123456，${updated.mustChangePassword ? '下次登录需重新设置密码。' : '下次可直接使用初始密码登录。'}`
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
        ? `密码将重置为 123456，当前登录会失效，${user.role === 'STUDENT' ? '下次可直接使用初始密码登录' : '下次登录必须重新设置密码'}。账号：${user.name}（${user.username}）`
        : user.active
          ? `停用后该账号无法登录，已有论文与提交记录保留。账号：${user.name}（${user.username}）`
          : `启用后该账号可以继续登录使用。账号：${user.name}（${user.username}）`
    modal.confirm({
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
    {
      title: '学院',
      dataIndex: 'collegeName',
      key: 'collegeName',
      render: (value: string | null) => value || '待归属',
    },
    ...(role === 'STUDENT'
      ? [
          {
            title: '专业',
            dataIndex: 'major',
            key: 'major',
            render: (value: string | null) => value || '—',
          },
          {
            title: '年级',
            dataIndex: 'grade',
            key: 'grade',
            render: (value: number | null) => (value === null ? '未填写' : value + '年'),
          },
          {
            title: '指导教师',
            dataIndex: 'teacherName',
            key: 'teacherName',
            render: (value: string | null) => value || '未分配',
          },
        ]
      : role === 'TEACHER'
        ? [
            {
              title: '专业',
              dataIndex: 'major',
              key: 'major',
              render: (value: string | null) => value || '—',
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
      width: 320,
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
          {user.id !== currentUser?.id && (
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
              onClick={() => confirmRemove(user)}
              aria-label={'删除' + user.name}
            >
              删除
            </Button>
          )}
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
            新建{role === 'STUDENT' ? '学生' : role === 'TEACHER' ? '教师' : '学院管理员'}
          </Button>
        }
      />
      <ApiAlert error={action.error ?? remove.error ?? catalog.error} />
      <Card className="table-card" style={{ minHeight: 681 }}>
        <Tabs
          activeKey={role}
          onChange={(key) => {
            setRole(key as Role)
            setGrade(null)
            setGradeDraft(null)
            setPage(1)
          }}
          items={[
            { key: 'STUDENT', label: '学生' },
            { key: 'TEACHER', label: '教师' },
            ...(!currentUser?.collegeCode ? [{ key: 'ADMIN', label: '学院管理员' }] : []),
          ]}
        />
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 16 }}>
          {!currentUser?.collegeCode && (
            <Select
              aria-label="按学院筛选"
              value={collegeFilter}
              style={{ width: 220, maxWidth: '100%' }}
              loading={catalog.isPending}
              onChange={(value) => {
                setCollegeFilter(value)
                setPage(1)
              }}
              options={[
                { value: '', label: '全部学院' },
                ...(catalog.data?.colleges ?? []).map((college) => ({
                  value: college.code,
                  label: college.name,
                })),
              ]}
            />
          )}
          <Input.Search
            placeholder="按账号或姓名搜索"
            allowClear
            enterButton="搜索"
            style={{ width: 360, maxWidth: '100%' }}
            onSearch={(value) => {
              setQ(value.trim())
              setPage(1)
            }}
          />
          {role === 'STUDENT' && (
            <Space.Compact style={{ maxWidth: '100%' }}>
              <InputNumber
                aria-label="按入学年级筛选"
                min={2000}
                max={2100}
                precision={0}
                value={gradeDraft}
                onChange={setGradeDraft}
                placeholder="入学年份"
                style={{ width: 132 }}
              />
              <Button
                onClick={() => {
                  if (gradeDraft !== null && (gradeDraft < 2000 || gradeDraft > 2100)) {
                    void message.error('请输入 2000—2100 年的入学年份。')
                    return
                  }
                  setGrade(gradeDraft)
                  setPage(1)
                }}
              >
                筛选年级
              </Button>
            </Space.Compact>
          )}
          {role === 'STUDENT' && (
            <Button
              type={grade === 'UNSET' ? 'primary' : 'default'}
              onClick={() => {
                setGradeDraft(null)
                setGrade('UNSET')
                setPage(1)
              }}
            >
              未填写年级
            </Button>
          )}
          <Select
            value={active}
            style={{ width: 130, maxWidth: '100%' }}
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
        </div>
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
                    q || active || grade !== null
                      ? '没有符合条件的账号。'
                      : '还没有账号，可新建或批量导入。'
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
