import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from './App'

afterEach(() => vi.unstubAllGlobals())

it('教师按后端成功统计查看名下学生，并能打开只读提交详情', async () => {
  const browser = userEvent.setup()
  const teacher = {
    id: '2',
    username: 't001',
    name: '李老师',
    role: 'TEACHER',
    active: true,
    mustChangePassword: false,
    major: null,
    teacherId: null,
    teacherName: null,
  }
  const submission = {
    id: '9',
    studentId: '3',
    submissionNumber: 5,
    originalFilename: '论文.docx',
    fileSize: 100,
    status: 'SUCCESS',
    problemCount: 0,
    byScope: null,
    errorMessage: null,
    createdAt: '2026-10-18T02:00:00Z',
    startedAt: '2026-10-18T02:01:00Z',
    finishedAt: '2026-10-18T02:02:00Z',
    canDownloadOriginal: true,
    canDownloadMarked: true,
    canDownloadReport: true,
  }
  const ok = (data: unknown) => new Response(JSON.stringify({ data, error: null }))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/auth/me') return ok(teacher)
      if (url.startsWith('/api/teacher/students?'))
        return ok({
          items: [
            {
              id: '3',
              username: '00003',
              name: '王同学',
              major: '计算机科学与技术',
              submissionCount: 2,
              lastSubmittedAt: submission.createdAt,
              latestProblemCount: 0,
            },
          ],
          total: 1,
          page: 1,
          size: 20,
        })
      if (url === '/api/teacher/students/3')
        return ok({
          ...teacher,
          id: '3',
          username: '00003',
          name: '王同学',
          role: 'STUDENT',
          teacherId: '2',
          teacherName: '李老师',
        })
      if (url.startsWith('/api/teacher/students/3/submissions?'))
        return ok({ items: [submission], total: 5, page: 1, size: 20 })
      if (url === '/api/submissions/9') return ok(submission)
      throw new Error(`未声明请求 ${url}`)
    }),
  )
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <MemoryRouter initialEntries={['/teacher/students']}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  const studentRow = (await screen.findByText('王同学')).closest('tr')!
  expect(within(studentRow).getByText('2')).toBeInTheDocument()
  expect(within(studentRow).getByText('0')).toBeInTheDocument()
  await browser.click(within(studentRow).getByRole('link', { name: '查看王同学的提交' }))
  expect(await screen.findByText(/指导教师：李老师/)).toBeInTheDocument()
  expect(screen.queryByLabelText('选择论文')).not.toBeInTheDocument()
  await browser.click(await screen.findByRole('link', { name: '查看第 5 次提交' }))
  expect(await screen.findByText('全部通过')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '下载批注论文' })).toBeInTheDocument()
  expect(screen.queryByText('各部分问题数')).not.toBeInTheDocument()
})
