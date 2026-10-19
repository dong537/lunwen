import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from './App'
import type { User } from '../shared/api/contracts'

const admin: User = {
  id: '1',
  username: 'admin',
  name: '管理员',
  role: 'ADMIN',
  major: null,
  teacherId: null,
  teacherName: null,
  active: true,
  mustChangePassword: false,
}
const student: User = {
  ...admin,
  id: '3',
  username: '00123',
  name: '测试学生',
  role: 'STUDENT',
  teacherId: '2',
  teacherName: '李老师',
}
const teacher: User = { ...admin, id: '2', username: 'teacher', name: '李老师', role: 'TEACHER' }
const result = {
  totalRows: 2,
  validRows: 1,
  successCount: 0,
  skippedCount: 0,
  failedCount: 1,
  errors: [{ row: 3, username: 'bad', message: '姓名不能为空' }],
}
const ok = (data: unknown) => new Response(JSON.stringify({ data, error: null, requestId: 'test' }))
function setup(path: string) {
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/auth/me') return ok(admin)
    if (url === '/api/auth/csrf') return ok({ token: 'test', headerName: 'X-CSRF-TOKEN' })
    if (url.startsWith('/api/admin/users?'))
      return ok({ items: [student], total: 1, page: 1, size: 20 })
    if (url.startsWith('/api/admin/teachers?'))
      return ok(
        url.endsWith('q=none')
          ? []
          : [teacher, { ...teacher, id: '4', name: '赵老师', username: 'teacher4' }],
      )
    if (url === '/api/admin/users/3' && init?.method === 'PATCH')
      return ok({ ...student, name: '新姓名' })
    if (url === '/api/admin/users' && init?.method === 'POST')
      return ok({ ...student, username: '00008', name: '新增学生' })
    if (url === '/api/admin/users/3/reset-password')
      return ok({ ...student, mustChangePassword: true })
    if (url === '/api/admin/imports/preview') return ok(result)
    if (url === '/api/admin/imports/execute') return ok({ ...result, successCount: 1 })
    throw new Error(`未声明请求：${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return fetcher
}
afterEach(() => vi.unstubAllGlobals())

it('更换导师后继续搜索，已选导师名称不会退回原导师', async () => {
  const browser = userEvent.setup()
  setup('/admin/users')
  await browser.click(await screen.findByRole('button', { name: '编辑测试学生' }))
  const dialog = screen.getByRole('dialog')
  await browser.selectOptions(within(dialog).getByRole('combobox', { name: '指导教师' }), '4')
  await browser.type(within(dialog).getByLabelText('搜索指导教师'), 'none')
  expect(await within(dialog).findByRole('option', { name: /赵老师.*当前选择/ })).toHaveValue('4')
})

it('创建学生账号保留前导零并提交真实选择的指导教师', async () => {
  const browser = userEvent.setup()
  const fetcher = setup('/admin/users')
  await browser.click(await screen.findByRole('button', { name: '新建学生' }))
  const dialog = screen.getByRole('dialog')
  await browser.type(within(dialog).getByLabelText('学号'), '00008')
  await browser.type(within(dialog).getByLabelText('姓名'), '新增学生')
  await browser.selectOptions(within(dialog).getByRole('combobox', { name: '指导教师' }), '2')
  await browser.click(within(dialog).getByRole('button', { name: '保存' }))
  expect(await screen.findByRole('status')).toHaveTextContent('已创建新增学生')
  const created = fetcher.mock.calls.find(
    ([url, init]) => url === '/api/admin/users' && init?.method === 'POST',
  )
  expect(JSON.parse(String(created?.[1]?.body))).toEqual({
    username: '00008',
    name: '新增学生',
    role: 'STUDENT',
    major: null,
    teacherId: '2',
  })
})

it('编辑学生时账号不可改，保存只提交可编辑字段', async () => {
  const browser = userEvent.setup()
  const fetcher = setup('/admin/users')
  await browser.click(await screen.findByRole('button', { name: '编辑测试学生' }))
  const dialog = screen.getByRole('dialog')
  expect(within(dialog).getByLabelText('学号')).toBeDisabled()
  await browser.clear(within(dialog).getByLabelText('姓名'))
  await browser.type(within(dialog).getByLabelText('姓名'), '新姓名')
  await browser.click(within(dialog).getByRole('button', { name: '保存' }))
  expect(await screen.findByRole('status')).toHaveTextContent('已更新')
  const saved = fetcher.mock.calls.find(
    ([url, init]) => url === '/api/admin/users/3' && init?.method === 'PATCH',
  )
  expect(JSON.parse(String(saved?.[1]?.body))).toEqual({
    name: '新姓名',
    major: null,
    teacherId: '2',
  })
})

it('重置密码必须确认具体账号，成功后说明首次改密', async () => {
  const browser = userEvent.setup()
  const fetcher = setup('/admin/users')
  await browser.click(await screen.findByRole('button', { name: '重置测试学生的密码' }))
  expect(screen.getByRole('dialog')).toHaveTextContent('00123')
  expect(fetcher.mock.calls.some(([url]) => url.includes('reset-password'))).toBe(false)
  await browser.click(screen.getByRole('button', { name: '确认重置' }))
  expect(await screen.findByRole('status')).toHaveTextContent('123456')
  expect(screen.getByRole('status')).toHaveTextContent('重新设置密码')
})

it('导入先预检，确认发送同一文件；换文件清除旧预检', async () => {
  const browser = userEvent.setup()
  const fetcher = setup('/admin/imports')
  const input = await screen.findByLabelText('选择 Excel 文件')
  const file = new File(['xlsx'], '学生.xlsx')
  await browser.upload(input, file)
  await browser.click(screen.getByRole('button', { name: '预检文件' }))
  expect(await screen.findByRole('button', { name: '确认导入有效账号' })).toBeInTheDocument()
  await browser.click(screen.getByRole('button', { name: '确认导入有效账号' }))
  expect(await screen.findByRole('heading', { name: '导入完成' })).toBeInTheDocument()
  const posted = fetcher.mock.calls.filter(([url]) => url.startsWith('/api/admin/imports/'))
  expect(posted).toHaveLength(2)
  expect((posted[0][1]?.body as FormData).get('file')).toBe(file)
  expect((posted[1][1]?.body as FormData).get('file')).toBe(file)
  await browser.upload(input, new File(['other'], '第二批.xlsx'))
  expect(screen.queryByRole('heading', { name: '导入完成' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '确认导入有效账号' })).not.toBeInTheDocument()
})
