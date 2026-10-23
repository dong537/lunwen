import { expect, test, type Page } from '@playwright/test'

type DownloadOptions = {
  mode: 'direct' | 'proxy'
  status?: 'SUCCESS' | 'PROCESSING'
  canDownloadPdfReport?: boolean
}

async function openSubmission(page: Page, options: DownloadOptions) {
  const { mode, status = 'SUCCESS', canDownloadPdfReport = true } = options
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    const envelope = (data: unknown) => route.fulfill({ json: { data, error: null } })

    if (path === '/api/auth/me') {
      await envelope({
        id: 'student-1',
        username: 'student-1',
        name: '学生甲',
        role: 'STUDENT',
        major: '计算机科学与技术',
        teacherId: 'teacher-1',
        teacherName: '教师甲',
        active: true,
        mustChangePassword: false,
      })
      return
    }
    if (path === '/api/submissions/transfer-mode') {
      await envelope({ mode })
      return
    }
    if (path === '/api/submissions/submission-1') {
      await envelope({
        id: 'submission-1',
        studentId: 'student-1',
        submissionNumber: 1,
        originalFilename: 'thesis.docx',
        fileSize: 2048,
        status,
        problemCount: 0,
        byScope: null,
        errorMessage: null,
        createdAt: '2026-10-23T00:00:00Z',
        startedAt: '2026-10-23T00:00:01Z',
        finishedAt: '2026-10-23T00:00:02Z',
        canDownloadOriginal: false,
        canDownloadMarked: false,
        canDownloadReport: true,
        canDownloadPdfReport,
        uploadReserved: false,
      })
      return
    }
    if (path.endsWith('/files/report-pdf/url')) {
      await envelope({
        url: new URL('/cos/report.pdf', page.url()).toString(),
        filename: 'thesis.report.pdf',
        expiresAt: '2026-10-23T00:10:00Z',
      })
      return
    }
    if (path.endsWith('/files/report-pdf')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/pdf',
        headers: { 'Content-Disposition': "attachment; filename*=UTF-8''thesis.report.pdf" },
        body: '%PDF-1.7\n%%EOF',
      })
      return
    }
    await route.fulfill({ status: 404, json: { data: null, error: { code: 'NOT_FOUND' } } })
  })

  await page.route('**/cos/report.pdf', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/pdf',
      headers: { 'Content-Disposition': "attachment; filename*=UTF-8''thesis.report.pdf" },
      body: '%PDF-1.7\n%%EOF',
    }),
  )
  await page.goto('/submissions/submission-1')
}

for (const mode of ['proxy', 'direct'] as const) {
  test(`downloads the PDF audit summary in ${mode} transfer mode`, async ({ page }) => {
    await openSubmission(page, { mode })
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: '下载 PDF 审核结果单' }).click()

    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('thesis.report.pdf')
  })
}

test('hides the PDF report button unless the successful report is available', async ({ page }) => {
  await openSubmission(page, { mode: 'proxy', canDownloadPdfReport: false })
  await expect(page.getByRole('button', { name: '下载 PDF 审核结果单' })).toHaveCount(0)

  await openSubmission(page, { mode: 'proxy', status: 'PROCESSING' })
  await expect(page.getByRole('button', { name: '下载 PDF 审核结果单' })).toHaveCount(0)
})
