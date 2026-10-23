import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { api, blocksCachedData } from '../../shared/api/http'
import type { Page, StudentSummary, User } from '../../shared/api/contracts'
import { formatTime } from '../../shared/files'
import {
  Empty,
  ErrorNotice,
  Loading,
  PageHeading,
  Pagination,
  SearchForm,
  useListParams,
} from '../../shared/ui'
import { SubmissionList } from '../submissions/SubmissionPages'

export function TeacherStudentsPage() {
  const { params, page, change } = useListParams()
  const q = params.get('q') ?? ''
  const list = useQuery({
    queryKey: ['students', q, page],
    queryFn: ({ signal }) =>
      api<Page<StudentSummary>>(
        `/teacher/students?q=${encodeURIComponent(q)}&page=${page}&size=20`,
        { signal },
      ),
  })
  if (blocksCachedData(list.error))
    return <ErrorNotice error={list.error} retry={() => void list.refetch()} />
  return (
    <>
      <PageHeading title="我的学生" description="提交次数与最近结果仅统计审核成功的记录。" />
      <section className="panel list-panel">
        <div className="toolbar">
          <SearchForm
            value={q}
            placeholder="按学号或姓名搜索"
            onSearch={(value) => change({ q: value, page: 1 })}
          />
        </div>
        {list.isPending && <Loading />}
        <ErrorNotice error={list.error} retry={() => void list.refetch()} />
        {list.data && (
          <>
            {!list.data.items.length ? (
              <Empty>{q ? '没有找到符合条件的学生。' : '还没有分配指导学生。'}</Empty>
            ) : (
              <div className="table-scroll">
                <table>
                  <caption className="sr-only">名下学生，按学号升序</caption>
                  <thead>
                    <tr>
                      <th>学号</th>
                      <th>姓名</th>
                      <th>专业</th>
                      <th className="numeric">成功提交次数</th>
                      <th>最近成功时间</th>
                      <th>最新问题数</th>
                      <th>
                        <span className="sr-only">操作</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.data.items.map((student) => (
                      <tr key={student.id}>
                        <td>{student.username}</td>
                        <td>{student.name}</td>
                        <td>{student.major || '—'}</td>
                        <td className="numeric">{student.submissionCount}</td>
                        <td className="nowrap">{formatTime(student.lastSubmittedAt)}</td>
                        <td>
                          {student.latestProblemCount === null ? '—' : student.latestProblemCount}
                        </td>
                        <td>
                          <Link
                            to={`/teacher/students/${student.id}`}
                            aria-label={`查看${student.name}的提交`}
                          >
                            查看
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <Pagination
              page={page}
              size={list.data.size}
              total={list.data.total}
              onChange={(next) => change({ page: next })}
            />
          </>
        )}
      </section>
    </>
  )
}

export function TeacherStudentDetailPage() {
  const { id = '' } = useParams()
  const student = useQuery({
    queryKey: ['student', id],
    queryFn: ({ signal }) => api<User>(`/teacher/students/${encodeURIComponent(id)}`, { signal }),
  })
  if (student.isPending) return <Loading />
  if (blocksCachedData(student.error))
    return <ErrorNotice error={student.error} retry={() => void student.refetch()} />
  return (
    <>
      <Link className="back-link" to="/teacher/students">
        ← 返回我的学生
      </Link>
      <ErrorNotice error={student.error} retry={() => void student.refetch()} />
      {student.data && (
        <>
          <PageHeading
            title={student.data.name}
            description={`学号 ${student.data.username} · ${student.data.major || '专业未填写'} · 指导教师：${student.data.teacherName || '未分配'}`}
          />
          <SubmissionList studentId={id} />
        </>
      )}
    </>
  )
}
