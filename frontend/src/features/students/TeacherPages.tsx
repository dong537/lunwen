import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import {
  Alert,
  App as AntdApp,
  Button,
  Card,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Typography,
} from 'antd'
import { ArrowLeftOutlined, ImportOutlined, PlusOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { api, blocksCachedData } from '../../shared/api/http'
import type { Page, StudentSummary, User } from '../../shared/api/contracts'
import { formatTime } from '../../shared/files'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { useSession } from '../auth/session'
import { SubmissionList } from '../submissions/SubmissionPages'
import { teacherApi } from './api'

const studentColumns: ColumnsType<StudentSummary> = [
  { title: '学号', dataIndex: 'username', key: 'username' },
  { title: '姓名', dataIndex: 'name', key: 'name' },
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
    title: '成功提交次数',
    dataIndex: 'submissionCount',
    key: 'submissionCount',
    align: 'right',
  },
  {
    title: '最近成功时间',
    dataIndex: 'lastSubmittedAt',
    key: 'lastSubmittedAt',
    render: (value: string | null) => formatTime(value),
  },
  {
    title: '最新问题数',
    dataIndex: 'latestProblemCount',
    key: 'latestProblemCount',
    render: (value: number | null) => (value === null ? '—' : value),
  },
  {
    title: '操作',
    key: 'action',
    width: 90,
    render: (_: unknown, record: StudentSummary) => (
      <Link to={`/teacher/students/${record.id}`}>查看</Link>
    ),
  },
]

export function TeacherStudentsPage() {
  useDocumentTitle('我的学生')
  const { user } = useSession()
  const [q, setQ] = useState('')
  const [page, setPage] = useState(1)
  const [creating, setCreating] = useState(false)
  const client = useQueryClient()
  const list = useQuery({
    queryKey: ['students', q, page],
    queryFn: ({ signal }) =>
      api<Page<StudentSummary>>(
        `/teacher/students?q=${encodeURIComponent(q)}&page=${page}&size=10`,
        { signal },
      ),
  })
  if (blocksCachedData(list.error))
    return <ApiAlert error={list.error} retry={() => void list.refetch()} />
  return (
    <>
      <PageHeader
        title="我的学生"
        description="按指导名单管理学生；提交次数与最近结果仅统计审核成功的记录。"
        extra={
          <Space wrap>
            <Link to="/teacher/students/import">
              <Button icon={<ImportOutlined />}>导入名单</Button>
            </Link>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              新增学生
            </Button>
          </Space>
        }
      />
      {!user?.collegeCode && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title="学院尚未归属，请联系校级管理员补录学院后再新增或导入学生。"
        />
      )}
      <Card className="table-card" style={{ minHeight: 599 }}>
        <Input.Search
          placeholder="按学号或姓名搜索"
          allowClear
          enterButton="搜索"
          style={{ maxWidth: 480, marginBottom: 16 }}
          onSearch={(value) => {
            setQ(value.trim())
            setPage(1)
          }}
        />
        {list.isPending && <CenterSpin />}
        <ApiAlert error={list.error} retry={() => void list.refetch()} />
        {list.data && (
          <Table<StudentSummary>
            rowKey="id"
            size="small"
            columns={studentColumns}
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
                <Empty description={q ? '没有找到符合条件的学生。' : '还没有分配指导学生。'} />
              ),
            }}
          />
        )}
      </Card>
      {creating && (
        <TeacherCreateDialog
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false)
            void client.invalidateQueries({ queryKey: ['students'] })
            void client.invalidateQueries({ queryKey: ['teacher-statistics'] })
          }}
        />
      )}
    </>
  )
}

function TeacherCreateDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const { message } = AntdApp.useApp()
  const { user } = useSession()
  const [form] = Form.useForm<{ username: string; name: string; major?: string; grade: number }>()
  const majors = useQuery({
    queryKey: ['teacher-majors'],
    queryFn: ({ signal }) => teacherApi.majors(signal),
    enabled: !!user?.collegeCode,
  })
  const create = useMutation({
    mutationFn: (values: { username: string; name: string; major?: string; grade: number }) =>
      teacherApi.createStudent({
        username: values.username.trim(),
        name: values.name.trim(),
        grade: values.grade,
        major: values.major || null,
      }),
    onSuccess: (student) => {
      void message.success('已新增' + student.name + '的账号，初始密码为 123456，可直接登录。')
      onSaved()
    },
  })
  return (
    <Modal
      title="新增指导学生"
      open
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={create.isPending}
      okButtonProps={{ disabled: !user?.collegeCode }}
      okText="保存"
      cancelText="取消"
      destroyOnHidden
    >
      {!user?.collegeCode && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title="学院尚未归属，请联系校级管理员补录后再新增学生。"
        />
      )}
      <Form
        form={form}
        layout="vertical"
        preserve={false}
        onFinish={(values) => create.mutate(values)}
      >
        <Form.Item label="学校">
          <Input value="山东石油化工学院" readOnly />
        </Form.Item>
        <Form.Item label="学院">
          <Input value={user?.collegeName ?? '待归属'} readOnly />
        </Form.Item>
        <Form.Item
          label="学号"
          name="username"
          rules={[
            { required: true, message: '请填写学号' },
            { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '学号需为 3–32 位字母、数字或下划线' },
          ]}
        >
          <Input maxLength={32} autoComplete="off" />
        </Form.Item>
        <Form.Item label="姓名" name="name" rules={[{ required: true, message: '请填写姓名' }]}>
          <Input maxLength={64} />
        </Form.Item>
        <Form.Item
          label="入学年级"
          name="grade"
          rules={[{ required: true, message: '请填写四位入学年份' }]}
        >
          <InputNumber min={2000} max={2100} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="专业（选填）" name="major">
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            loading={majors.isPending}
            placeholder="选择本学院专业"
            options={(majors.data ?? []).map((major) => ({ value: major.name, label: major.name }))}
          />
        </Form.Item>
        <ApiAlert error={majors.error ?? create.error} />
      </Form>
    </Modal>
  )
}

export function TeacherStudentDetailPage() {
  const { id = '' } = useParams()
  const student = useQuery({
    queryKey: ['student', id],
    queryFn: ({ signal }) => api<User>(`/teacher/students/${encodeURIComponent(id)}`, { signal }),
  })
  useDocumentTitle(student.data ? student.data.name : '学生详情')
  if (student.isPending) return <CenterSpin />
  if (blocksCachedData(student.error))
    return <ApiAlert error={student.error} retry={() => void student.refetch()} />
  return (
    <>
      <Link to="/teacher/students">
        <Button type="link" icon={<ArrowLeftOutlined />} style={{ paddingLeft: 0 }}>
          返回我的学生
        </Button>
      </Link>
      <ApiAlert error={student.error} retry={() => void student.refetch()} />
      {student.data && (
        <>
          <PageHeader
            title={student.data.name}
            description={`学号 ${student.data.username} · ${student.data.major || '专业未填写'} · 指导教师：${student.data.teacherName || '未分配'}`}
          />
          <SubmissionList studentId={id} pageSize={10} fixedHeight />
        </>
      )}
      {!student.data && (
        <Typography.Paragraph type="secondary">学生信息暂不可用。</Typography.Paragraph>
      )}
    </>
  )
}
