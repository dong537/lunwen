import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from './App'
import type { Submission, User } from '../shared/api/contracts'

const student: User = {
  id: '1',
  username: '00123',
  name: '测试学生',
  role: 'STUDENT',
  major: '计算机科学与技术',
  teacherId: '2',
  teacherName: '李老师',
  active: true,
  mustChangePassword: false,
}
const submission: Submission = {
  id: '4',
  studentId: '1',
  submissionNumber: 1,
  originalFilename: '毕业论文.docx',
  fileSize: 1024,
  status: 'PENDING',
  problemCount: null,
  byScope: null,
  errorMessage: null,
  createdAt: '2026-10-18T02:00:00Z',
  startedAt: null,
  finishedAt: null,
  canDownloadOriginal: true,
  canDownloadMarked: false,
  uploadReserved: false,
}
const docx = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
const ok = (data: unknown, status = 200) =>
  new Response(JSON.stringify({ data, error: null, requestId: 'test' }), { status })

class FakeXhr {
  static last: FakeXhr | null = null
  upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null }
  status = 200
  timeout = 0
  withCredentials = false
  url = ''
  headers: Record<string, string> = {}
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  ontimeout: (() => void) | null = null
  onabort: (() => void) | null = null
  constructor() {
    FakeXhr.last = this
  }
  open(_method: string, url: string) {
    this.url = url
  }
  setRequestHeader(name: string, value: string) {
    this.headers[name] = value
  }
  getResponseHeader() {
    return 'cos-test-request'
  }
  send(_file: File) {
    this.upload.onprogress?.({ lengthComputable: true, loaded: 50, total: 100 } as ProgressEvent)
    this.status = 200
    this.onload?.()
  }
  abort() {
    this.onabort?.()
  }
}

function setup(path: string) {
  FakeXhr.last = null
  vi.stubGlobal('XMLHttpRequest', FakeXhr as unknown as typeof XMLHttpRequest)
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/auth/me') return ok(student)
    if (url === '/api/auth/csrf') return ok({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (url === '/api/submissions/transfer-mode') return ok({ mode: 'direct' })
    if (url === '/api/submissions/uploads' && init?.method === 'POST')
      return ok({
        submissionId: '4',
        url: 'https://cos.test/submissions/4/original.docx?sign=test',
        method: 'PUT',
        headers: { 'Content-Type': docx },
        expiresAt: '2099-01-01T00:00:00Z',
      })
    if (url === '/api/submissions/4/upload-complete' && init?.method === 'POST')
      return ok(submission, 202)
    if (url === '/api/submissions/4/files/original/url')
      return ok({
        url: 'https://cos.test/submissions/4/original.docx?sign=download',
        filename: '毕业论文.docx',
        expiresAt: '2099-01-01T00:00:00Z',
      })
    if (url === '/api/submissions/4') return ok(submission)
    if (url.startsWith('/api/submissions?'))
      return ok({ items: [submission], total: 1, page: 1, size: 20 })
    throw new Error(`测试未声明的请求 ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
        })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return fetcher
}

afterEach(() => vi.unstubAllGlobals())

it('direct 模式下浏览器直传 COS 后再完成上传', async () => {
  const browser = userEvent.setup({ applyAccept: false })
  const fetcher = setup('/student/submissions')
  await browser.upload(
    await screen.findByLabelText('选择论文'),
    new File([new Uint8Array([1, 2, 3])], '毕业论文.docx', { type: docx }),
  )
  const submit = screen.getByRole('button', { name: '提交审核' })
  await waitFor(() => expect(submit).toBeEnabled())
  await browser.click(submit)
  expect(await screen.findByRole('heading', { name: '第 1 次提交' })).toBeInTheDocument()
  expect(FakeXhr.last?.url).toBe('https://cos.test/submissions/4/original.docx?sign=test')
  expect(FakeXhr.last?.headers['Content-Type']).toBe(docx)
  expect(
    fetcher.mock.calls.some(
      ([url, options]) => url === '/api/submissions/uploads' && options?.method === 'POST',
    ),
  ).toBe(true)
  expect(
    fetcher.mock.calls.some(
      ([url, options]) =>
        url === '/api/submissions/4/upload-complete' && options?.method === 'POST',
    ),
  ).toBe(true)
  expect(
    fetcher.mock.calls.some(
      ([url, options]) => url === '/api/submissions' && options?.method === 'POST',
    ),
  ).toBe(false)
})

it('direct 模式下下载按钮跳转到 COS 预签名 URL', async () => {
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const fetcher = setup('/submissions/4')
  await screen.findByText('等待审核')
  await userEvent.setup().click(screen.getByRole('button', { name: '下载原始论文' }))
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some(([url]) => url === '/api/submissions/4/files/original/url'),
    ).toBe(true),
  )
  expect(click).toHaveBeenCalled()
})
