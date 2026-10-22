import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from '../../app/App'
import type { Submission, TransferMode, User } from '../../shared/api/contracts'

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
const reportUrl = 'https://cos.test/submissions/report-1/attempt-1/report.docx?sign=report'
const docx = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
const ok = (data: unknown) => new Response(JSON.stringify({ data, error: null }))

function setup({
  submission = success,
  role = 'STUDENT',
  mode = 'proxy',
  reportResponse,
}: {
  submission?: Submission
  role?: 'STUDENT' | 'TEACHER'
  mode?: TransferMode
  reportResponse?: () => Promise<Response>
} = {}) {
  const fetcher = vi.fn(async (url: string, _options?: RequestInit) => {
    if (url === '/api/auth/me') return ok({ ...student, role })
    if (url === '/api/submissions/transfer-mode') return ok({ mode })
    if (url === '/api/submissions/report-1') return ok(submission)
    if (url === '/api/submissions/report-1/files/report/url')
      return reportResponse
        ? reportResponse()
        : ok({ url: reportUrl, filename: '论文.report.docx', expiresAt: '2099-01-01T00:00:00Z' })
    if (url === '/api/submissions/report-1/files/report')
      return reportResponse
        ? reportResponse()
        : new Response('word-report', { headers: { 'Content-Type': docx } })
    throw new Error(`未声明请求 ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:report')
  const clicks: { href: string; filename: string }[] = []
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    clicks.push({ href: this.href, filename: this.download })
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
  return { fetcher, createObjectURL, clicks }
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it.each(['STUDENT', 'TEACHER'] as const)('%s 通过代理下载实际 Word 报告', async (role) => {
  const { fetcher, createObjectURL, clicks } = setup({ role })
  const button = await screen.findByRole('button', { name: '下载检查报告' })
  button.focus()
  await userEvent.setup().keyboard('{Enter}')

  await waitFor(() =>
    expect(clicks).toEqual([{ href: 'blob:report', filename: '论文.report.docx' }]),
  )
  expect(fetcher).toHaveBeenCalledWith(
    '/api/submissions/report-1/files/report',
    expect.objectContaining({ credentials: 'include' }),
  )
  expect(await (createObjectURL.mock.calls[0][0] as Blob).text()).toBe('word-report')
  expect(fetcher.mock.calls.some(([url]) => url.includes('/files/marked'))).toBe(false)
})

it.each(['STUDENT', 'TEACHER'] as const)('%s 通过 COS 预签名下载报告类型', async (role) => {
  const { fetcher, createObjectURL, clicks } = setup({ role, mode: 'direct' })
  await userEvent.setup().click(await screen.findByRole('button', { name: '下载检查报告' }))

  await waitFor(() => expect(clicks).toEqual([{ href: reportUrl, filename: '' }]))
  expect(fetcher).toHaveBeenCalledWith(
    '/api/submissions/report-1/files/report/url',
    expect.objectContaining({ credentials: 'include' }),
  )
  expect(createObjectURL).not.toHaveBeenCalled()
  expect(fetcher.mock.calls.some(([url]) => url === '/api/submissions/report-1/files/report')).toBe(
    false,
  )
  expect(fetcher.mock.calls.some(([url]) => url.includes('/files/marked'))).toBe(false)
})

it.each([0, 12])('历史成功记录没有报告时，问题数为 %s 也不显示报告下载', async (problemCount) => {
  setup({ submission: { ...success, problemCount, canDownloadReport: false } })

  expect(await screen.findByRole('button', { name: '下载批注论文' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '下载检查报告' })).not.toBeInTheDocument()
})

it.each(['PENDING', 'PROCESSING', 'FAILED'] as const)('%s 状态不提供报告下载', async (status) => {
  setup({ submission: { ...success, status, problemCount: null } })

  expect(await screen.findByRole('heading', { name: '第 1 次提交' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '下载检查报告' })).not.toBeInTheDocument()
})

it('报告下载中禁止重复点击，完成后恢复按钮', async () => {
  let finish: ((response: Response) => void) | undefined
  const { clicks } = setup({
    reportResponse: () =>
      new Promise<Response>((resolve) => {
        finish = resolve
      }),
  })
  const report = await screen.findByRole('button', { name: '下载检查报告' })
  await userEvent.setup().click(report)

  expect(report).toBeDisabled()
  expect(screen.getByRole('button', { name: '下载批注论文' })).toBeDisabled()
  finish!(new Response('report'))
  await waitFor(() => expect(report).toBeEnabled())
  expect(clicks).toHaveLength(1)
})

it.each(['proxy', 'direct'] as const)(
  '%s 报告下载无权限时展示原因，不保存错误响应',
  async (mode) => {
    const { createObjectURL, clicks } = setup({
      mode,
      reportResponse: async () =>
        new Response(
          JSON.stringify({
            data: null,
            error: { code: 'FORBIDDEN', message: '你已无权下载该报告。' },
            requestId: 'report-denied',
          }),
          { status: 403 },
        ),
    })
    await userEvent.setup().click(await screen.findByRole('button', { name: '下载检查报告' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('你已无权下载该报告。')
    expect(screen.getByRole('alert')).toHaveTextContent('report-denied')
    expect(clicks).toEqual([])
    expect(createObjectURL).not.toHaveBeenCalled()
  },
)

it('下载报告时会话失效则返回登录并清除下载入口', async () => {
  const { createObjectURL } = setup({
    reportResponse: async () =>
      new Response(
        JSON.stringify({
          data: null,
          error: { code: 'UNAUTHENTICATED', message: '请重新登录。' },
        }),
        { status: 401 },
      ),
  })
  await userEvent.setup().click(await screen.findByRole('button', { name: '下载检查报告' }))

  expect(await screen.findByRole('heading', { name: '登录账号' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '下载检查报告' })).not.toBeInTheDocument()
  expect(createObjectURL).not.toHaveBeenCalled()
})
