import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import type { Page, Submission } from '../../shared/api/contracts'
import { SubmissionList } from './SubmissionPages'

const failedSubmission: Submission = {
  id: 'failed-1',
  studentId: 'student-1',
  submissionNumber: 21,
  originalFilename: '论文.docx',
  fileSize: 1024,
  status: 'FAILED',
  problemCount: null,
  byScope: null,
  errorMessage: '上传文件无法读取，请重新导出为 DOCX。',
  createdAt: '2026-10-19T13:57:00Z',
  startedAt: null,
  finishedAt: '2026-10-19T13:58:00Z',
  canDownloadOriginal: false,
  canDownloadMarked: false,
  canDownloadReport: false,
}

function page(items: Submission[]): Page<Submission> {
  return { items, total: items.length, page: 1, size: 20 }
}

function ok(data: unknown) {
  return new Response(JSON.stringify({ data, error: null }), { status: 200 })
}

function renderList(fetcher: typeof fetch) {
  vi.stubGlobal('fetch', fetcher)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/student/submissions']}>
        <SubmissionList />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

it('失败提交在列表中说明审核未完成并显示服务端原因', async () => {
  renderList(vi.fn().mockResolvedValue(ok(page([failedSubmission]))))

  expect(await screen.findByText('审核未完成')).toBeInTheDocument()
  expect(screen.getByText('上传文件无法读取，请重新导出为 DOCX。')).toBeInTheDocument()
  expect(screen.queryByText(/个问题/)).not.toBeInTheDocument()
})

it('失败提交没有原因时提示用户查看详情', async () => {
  renderList(vi.fn().mockResolvedValue(ok(page([{ ...failedSubmission, errorMessage: '  ' }]))))

  expect(await screen.findByText('失败原因暂不可用，请查看详情。')).toBeInTheDocument()
})

it('提交列表将较长的失败原因缩短显示', async () => {
  const reason = '文件无法读取。'.repeat(15)
  const summary = `${Array.from(reason).slice(0, 80).join('')}…`
  renderList(vi.fn().mockResolvedValue(ok(page([{ ...failedSubmission, errorMessage: reason }]))))

  expect(await screen.findByText(summary)).toBeInTheDocument()
  expect(screen.queryByText(reason)).not.toBeInTheDocument()
})

it('列表刷新失败但仍有缓存记录时提示状态可能过期且不显示技术异常', async () => {
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(ok(page([failedSubmission])))
    .mockRejectedValueOnce(new Error('ECONNRESET database host internals'))
  renderList(fetcher)

  expect(await screen.findByText('论文.docx')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '刷新记录' }))

  expect(
    await screen.findByText('列表刷新失败，当前显示的是上次加载的记录，状态可能已过期。'),
  ).toBeInTheDocument()
  expect(screen.getByText('论文.docx')).toBeInTheDocument()
  expect(screen.queryByText(/database host internals/)).not.toBeInTheDocument()
})
