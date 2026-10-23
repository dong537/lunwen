import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from '../../app/App'
import type { Submission, User } from '../../shared/api/contracts'

const student: User = {
  id: 'student-1',
  username: 's001',
  name: '测试学生',
  role: 'STUDENT',
  major: null,
  teacherId: 'teacher-1',
  teacherName: '李老师',
  active: true,
  mustChangePassword: false,
}

const success = {
  id: 'report-1',
  studentId: student.id,
  submissionNumber: 1,
  originalFilename: '论文.docx',
  fileSize: 1024,
  status: 'SUCCESS',
  problemCount: 0,
  byScope: null,
  errorMessage: null,
  createdAt: '2026-10-20T02:00:00Z',
  startedAt: '2026-10-20T02:01:00Z',
  finishedAt: '2026-10-20T02:02:00Z',
  canDownloadOriginal: true,
  canDownloadMarked: true,
  canDownloadReport: true,
} satisfies Submission

const ok = (data: unknown) => new Response(JSON.stringify({ data, error: null }))

function setup(
  submission: Submission = success,
  user: User = student,
  reportResponse: () => Promise<Response> = async () =>
    new Response('word-report', {
      headers: {
        'Content-Type': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent('论文-检查报告.docx')}`,
      },
    }),
) {
  const fetcher = vi.fn(async (url: string) => {
    if (url === '/api/auth/me') return ok(user)
    if (url === '/api/submissions/report-1') return ok(submission)
    if (url === '/api/submissions/report-1/files/report') return reportResponse()
    throw new Error(`未声明请求 ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:report')
  const savedNames: string[] = []
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    savedNames.push(this.download)
  })
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/submissions/report-1']}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { fetcher, createObjectURL, savedNames }
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it.each(['STUDENT', 'TEACHER'] as const)('%s 可下载成功记录的 Word 检查报告', async (role) => {
  const browser = userEvent.setup()
  const { fetcher, createObjectURL, savedNames } = setup(success, { ...student, role })

  const button = await screen.findByRole('button', { name: '下载检查报告' })
  button.focus()
  await browser.keyboard('{Enter}')

  await waitFor(() => expect(savedNames).toEqual(['论文-检查报告.docx']))
  expect(fetcher).toHaveBeenCalledWith(
    '/api/submissions/report-1/files/report',
    expect.objectContaining({ credentials: 'include' }),
  )
  expect(createObjectURL).toHaveBeenCalledOnce()
  expect(await (createObjectURL.mock.calls[0][0] as Blob).text()).toBe('word-report')
})

it.each([0, 12])('没有报告的成功记录不因问题数为 %s 而显示报告下载', async (problemCount) => {
  setup({ ...success, problemCount, canDownloadReport: false })

  expect(await screen.findByRole('button', { name: '下载批注论文' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '下载检查报告' })).not.toBeInTheDocument()
})

it.each(['PENDING', 'PROCESSING', 'FAILED'] as const)(
  '%s 记录即使含有报告可用标记也不提供报告下载',
  async (status) => {
    setup({ ...success, status, problemCount: null })

    expect(await screen.findByRole('heading', { name: '第 1 次提交' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下载检查报告' })).not.toBeInTheDocument()
  },
)

it('报告下载中禁止重复下载，完成后恢复按钮', async () => {
  const browser = userEvent.setup()
  let finish: ((response: Response) => void) | undefined
  const { savedNames } = setup(
    success,
    student,
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve
      }),
  )
  const report = await screen.findByRole('button', { name: '下载检查报告' })
  await browser.click(report)

  expect(report).toBeDisabled()
  expect(screen.getByRole('button', { name: '下载批注论文' })).toBeDisabled()
  finish!(new Response('report'))
  await waitFor(() => expect(report).toBeEnabled())
  expect(savedNames).toEqual(['论文-检查报告.docx'])
})

it('报告下载无权限时显示中文原因并且不保存错误响应', async () => {
  const browser = userEvent.setup()
  const { createObjectURL, savedNames } = setup(
    success,
    student,
    async () =>
      new Response(
        JSON.stringify({
          data: null,
          error: { code: 'FORBIDDEN', message: '你已无权下载该报告。' },
          requestId: 'report-denied',
        }),
        { status: 403 },
      ),
  )
  await browser.click(await screen.findByRole('button', { name: '下载检查报告' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('你已无权下载该报告。')
  expect(screen.getByRole('alert')).toHaveTextContent('report-denied')
  expect(savedNames).toEqual([])
  expect(createObjectURL).not.toHaveBeenCalled()
})

it('报告下载时会话失效则返回登录并清除下载入口', async () => {
  const browser = userEvent.setup()
  const { createObjectURL } = setup(
    success,
    student,
    async () =>
      new Response(
        JSON.stringify({
          data: null,
          error: { code: 'UNAUTHENTICATED', message: '请重新登录。' },
        }),
        { status: 401 },
      ),
  )
  await browser.click(await screen.findByRole('button', { name: '下载检查报告' }))

  expect(await screen.findByRole('heading', { name: '登录账号' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '下载检查报告' })).not.toBeInTheDocument()
  expect(createObjectURL).not.toHaveBeenCalled()
})
