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
const pending: Submission = {
  id: '4',
  studentId: '1',
  submissionNumber: 3,
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
  canDownloadReport: false,
}
const ok = (data: unknown) =>
  new Response(JSON.stringify({ data, error: null, requestId: 'test' }), { status: 200 })
function setup(user: User | null, path: string, submission = pending) {
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/auth/me')
      return user
        ? ok(user)
        : new Response(
            JSON.stringify({ data: null, error: { code: 'UNAUTHENTICATED', message: '请登录' } }),
            { status: 401 },
          )
    if (url === '/api/auth/csrf') return ok({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (url === '/api/auth/login') return ok(student)
    if (url === '/api/auth/password' && init?.method === 'POST')
      return ok({ ...student, mustChangePassword: false })
    if (url === '/api/submissions/4') return ok(submission)
    if (url.startsWith('/api/submissions?'))
      return ok({ items: [submission], total: 1, page: 1, size: 20 })
    throw new Error(`测试未声明的请求 ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return fetcher
}
afterEach(() => vi.unstubAllGlobals())

it('未登录用户通过真实登录表单进入学生提交页', async () => {
  const browser = userEvent.setup()
  const fetcher = setup(null, '/student/submissions')
  await browser.type(await screen.findByLabelText('账号'), '00123')
  await browser.type(screen.getByLabelText('密码'), 'test123')
  await browser.click(screen.getByRole('button', { name: '登录' }))
  expect(await screen.findByRole('heading', { name: '我的论文' })).toBeInTheDocument()
  expect(
    fetcher.mock.calls.some(
      ([url, options]) =>
        url === '/api/auth/login' && JSON.parse(String(options?.body)).username === '00123',
    ),
  ).toBe(true)
})

it('首次改密拦截业务请求，并校验两次密码一致', async () => {
  const browser = userEvent.setup()
  const fetcher = setup({ ...student, mustChangePassword: true }, '/student/submissions')
  expect(await screen.findByRole('heading', { name: '设置新密码' })).toBeInTheDocument()
  expect(fetcher.mock.calls.some(([url]) => url.startsWith('/api/submissions'))).toBe(false)
  await browser.type(screen.getByLabelText('当前密码'), '123456')
  await browser.type(screen.getByLabelText('新密码'), 'new12345')
  await browser.type(screen.getByLabelText('再次输入新密码'), 'different')
  await browser.click(screen.getByRole('button', { name: '保存新密码' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('两次输入的新密码不一致')
  expect(fetcher.mock.calls.some(([url]) => url === '/api/auth/password')).toBe(false)
})

it('首次改密成功后更新会话并进入学生业务页', async () => {
  const browser = userEvent.setup()
  setup({ ...student, mustChangePassword: true }, '/student/submissions')
  await browser.type(await screen.findByLabelText('当前密码'), '123456')
  await browser.type(screen.getByLabelText('新密码'), 'new12345')
  await browser.type(screen.getByLabelText('再次输入新密码'), 'new12345')
  await browser.click(screen.getByRole('button', { name: '保存新密码' }))
  expect(await screen.findByRole('heading', { name: '我的论文' })).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '设置新密码' })).not.toBeInTheDocument()
})

it('管理员访问论文详情显示无权访问且不请求论文数据', async () => {
  const fetcher = setup({ ...student, role: 'ADMIN' }, '/submissions/4')
  expect(await screen.findByRole('heading', { name: '无权访问' })).toBeInTheDocument()
  expect(fetcher.mock.calls.some(([url]) => url === '/api/submissions/4')).toBe(false)
})

it('待审核详情不显示零问题通过，并只提供原始论文', async () => {
  setup(student, '/submissions/4')
  expect(await screen.findByText('等待审核')).toBeInTheDocument()
  expect(screen.queryByText('全部通过')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '下载原始论文' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '下载批注论文' })).not.toBeInTheDocument()
})

it('失败详情只显示原因，不显示下载或问题统计', async () => {
  setup(student, '/submissions/4', {
    ...pending,
    status: 'FAILED',
    errorMessage: '文档损坏，请重新保存为 DOCX 后上传。',
    canDownloadOriginal: false,
  })
  expect(await screen.findByText('审核失败')).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('文档损坏')
  expect(screen.queryByRole('button', { name: /下载/ })).not.toBeInTheDocument()
})

it('上传非 DOCX 时显示校验原因且不提交请求', async () => {
  const browser = userEvent.setup({ applyAccept: false })
  const fetcher = setup(student, '/student/submissions')
  await browser.upload(
    await screen.findByLabelText('选择论文'),
    new File(['wrong'], '论文.pdf', { type: 'application/pdf' }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent('.docx')
  await waitFor(() => expect(screen.getByRole('button', { name: '提交审核' })).toBeDisabled())
  expect(
    fetcher.mock.calls.some(([url, init]) => url === '/api/submissions' && init?.method === 'POST'),
  ).toBe(false)
})

it('登录允许 128 个非 BMP 字符密码，不按 UTF16 截断', async () => {
  const browser = userEvent.setup()
  const fetcher = setup(null, '/login')
  await browser.type(await screen.findByLabelText('账号'), '00123')
  await browser.type(screen.getByLabelText('密码'), '😀'.repeat(128))
  await browser.click(screen.getByRole('button', { name: '登录' }))
  expect(await screen.findByRole('heading', { name: '我的论文' })).toBeInTheDocument()
  const request = fetcher.mock.calls.find(([url]) => url === '/api/auth/login')
  expect([...JSON.parse(String(request?.[1]?.body)).password]).toHaveLength(128)
})

it('访问权限被撤销后刷新，不继续展示缓存中的论文和下载按钮', async () => {
  const browser = userEvent.setup()
  const fetcher = setup({ ...student, role: 'TEACHER' }, '/submissions/4')
  expect(await screen.findByRole('button', { name: '下载原始论文' })).toBeInTheDocument()
  fetcher.mockImplementationOnce(
    async () =>
      new Response(
        JSON.stringify({
          data: null,
          error: { code: 'FORBIDDEN', message: '该学生已不在你的指导范围内' },
        }),
        { status: 403 },
      ),
  )
  await browser.click(screen.getByRole('button', { name: '刷新状态' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('已不在你的指导范围')
  expect(screen.queryByRole('button', { name: '下载原始论文' })).not.toBeInTheDocument()
  expect(screen.queryByText('毕业论文.docx')).not.toBeInTheDocument()
})
