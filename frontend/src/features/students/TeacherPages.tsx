import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { Button, Card, Input, Table, Typography } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { api, blocksCachedData } from '../../shared/api/http'
import type { Page, StudentSummary, User } from '../../shared/api/contracts'
import { formatTime } from '../../shared/files'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { SubmissionList } from '../submissions/SubmissionPages'

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
  const [q, setQ] = useState('')
  const [page, setPage] = useState(1)
  const list = useQuery({
    queryKey: ['students', q, page],
    queryFn: ({ signal }) =>
      api<Page<StudentSummary>>(
        `/teacher/students?q=${encodeURIComponent(q)}&page=${page}&size=20`,
        { signal },
      ),
  })
  if (blocksCachedData(list.error))
    return <ApiAlert error={list.error} retry={() => void list.refetch()} />
  return (
    <>
      <PageHeader title="我的学生" description="提交次数与最近结果仅统计审核成功的记录。" />
      <Card>
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
              onChange: setPage,
            }}
            locale={{ emptyText: q ? '没有找到符合条件的学生。' : '还没有分配指导学生。' }}
          />
        )}
      </Card>
    </>
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
          <SubmissionList studentId={id} />
        </>
      )}
      {!student.data && (
        <Typography.Paragraph type="secondary">学生信息暂不可用。</Typography.Paragraph>
      )}
    </>
  )
}
