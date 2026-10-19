import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from './App'

afterEach(() => vi.unstubAllGlobals())

it('另一个页签切换账号后会话刷新，不显示上一账号缓存的论文', async () => {
  let switched = false
  const first = {
    id: '1',
    username: 'first',
    name: '前一个学生',
    role: 'STUDENT',
    active: true,
    mustChangePassword: false,
    major: null,
    teacherId: null,
    teacherName: null,
  }
  const second = { ...first, id: '2', username: 'second', name: '当前学生' }
  const ok = (data: unknown) => new Response(JSON.stringify({ data, error: null }))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/auth/me') return ok(switched ? second : first)
      if (url.startsWith('/api/submissions?')) {
        if (switched)
          return new Response(
            JSON.stringify({
              data: null,
              error: { code: 'TEMPORARY_ERROR', message: '暂时无法获取新账号记录' },
            }),
            { status: 503 },
          )
        return ok({
          items: [
            {
              id: '7',
              studentId: '1',
              submissionNumber: 1,
              originalFilename: '上一账号私有论文.docx',
              fileSize: 10,
              status: 'SUCCESS',
              problemCount: 0,
              byScope: null,
              errorMessage: null,
              createdAt: '2026-10-18T02:00:00Z',
              startedAt: null,
              finishedAt: null,
              canDownloadOriginal: true,
              canDownloadMarked: true,
            },
          ],
          total: 1,
          page: 1,
          size: 20,
        })
      }
      throw new Error(`未声明请求 ${url}`)
    }),
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/student/submissions']}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(await screen.findByText('上一账号私有论文.docx')).toBeInTheDocument()
  switched = true
  await act(() => client.invalidateQueries({ queryKey: ['session'] }))
  expect(await screen.findByText('当前学生')).toBeInTheDocument()
  await waitFor(() => expect(screen.queryByText('上一账号私有论文.docx')).not.toBeInTheDocument())
  expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法获取新账号记录')
})

it('上传期间离开页面，迟到的响应不会跳回详情或重新填充旧页面缓存', async () => {
  const browser = userEvent.setup()
  let finishUpload: ((response: Response) => void) | undefined
  const ok = (data: unknown) => new Response(JSON.stringify({ data, error: null }))
  const student = {
    id: '1',
    username: 's001',
    name: '学生',
    role: 'STUDENT',
    active: true,
    mustChangePassword: false,
    major: null,
    teacherId: null,
    teacherName: null,
  }
  const submitted = {
    id: '99',
    studentId: '1',
    submissionNumber: 1,
    originalFilename: '论文.docx',
    fileSize: 4,
    status: 'PENDING',
    problemCount: null,
    byScope: null,
    errorMessage: null,
    createdAt: '2026-10-18T02:00:00Z',
    startedAt: null,
    finishedAt: null,
    canDownloadOriginal: true,
    canDownloadMarked: false,
  }
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/auth/me') return ok(student)
    if (url === '/api/auth/csrf') return ok({ token: 'test', headerName: 'X-CSRF-TOKEN' })
    if (url.startsWith('/api/submissions?')) return ok({ items: [], total: 0, page: 1, size: 20 })
    if (url === '/api/submissions' && init?.method === 'POST')
      return new Promise<Response>((resolve) => {
        finishUpload = resolve
      })
    if (url === '/api/submissions/99') return ok(submitted)
    throw new Error(`未声明请求 ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/student/submissions']}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await browser.upload(await screen.findByLabelText('选择论文'), new File(['word'], '论文.docx'))
  await browser.click(screen.getByRole('button', { name: '提交审核' }))
  await waitFor(() => expect(finishUpload).toBeTypeOf('function'))
  await browser.click(screen.getByRole('link', { name: '个人信息' }))
  expect(await screen.findByRole('heading', { name: '个人信息' })).toBeInTheDocument()
  await act(async () => {
    finishUpload!(ok(submitted))
  })
  expect(screen.getByRole('heading', { name: '个人信息' })).toBeInTheDocument()
  expect(client.getQueryData(['submission', '99'])).toBeUndefined()
})
