import { expect, test, type Page } from '@playwright/test'

const admin = {
  id: '1',
  username: 'admin',
  name: '校级管理员',
  role: 'ADMIN',
  major: null,
  teacherId: null,
  teacherName: null,
  collegeCode: null,
  collegeName: null,
  active: true,
}

async function installPasswordGate(page: Page, rejectPassword = false) {
  let mustChangePassword = true
  let dashboardRequests = 0
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    let error: { code: string; message: string } | null = null
    let status = 200
    if (path === '/api/auth/me') data = { ...admin, mustChangePassword }
    else if (path === '/api/auth/csrf')
      data = { token: 'password-refresh-fixture', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/auth/password' && route.request().method() === 'POST') {
      if (rejectPassword) {
        status = 400
        error = { code: 'OLD_PASSWORD_INCORRECT', message: '旧密码不正确' }
      } else {
        mustChangePassword = false
        data = { ...admin, mustChangePassword }
      }
    } else if (path === '/api/admin/dashboard') {
      dashboardRequests += 1
      if (mustChangePassword) {
        status = 403
        error = { code: 'PASSWORD_CHANGE_REQUIRED', message: '请先修改初始密码' }
      } else
        data = {
          activeStudents: 23,
          activeTeachers: 4,
          totalSubmissions: 31,
          dailySubmissions: [],
          statusDistribution: [],
          studentsByCollege: [],
        }
    } else throw new Error(`Unexpected API request: ${route.request().method()} ${path}`)
    await route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify({ data: data ?? null, error, requestId: 'password-refresh-fixture' }),
    })
  })
  return { dashboardRequests: () => dashboardRequests }
}

async function submitPassword(page: Page) {
  const dialog = page.getByRole('dialog', { name: '设置新密码', exact: true })
  await dialog.getByLabel('当前密码', { exact: true }).fill('123456')
  await dialog.getByLabel('新密码', { exact: true }).fill('NewPassword42')
  await dialog.getByLabel('再次输入新密码', { exact: true }).fill('NewPassword42')
  await dialog.getByRole('button', { name: '保存新密码', exact: true }).focus()
  await page.keyboard.press('Enter')
}

test.afterEach(async ({ page }, testInfo) => {
  await page.screenshot({ path: testInfo.outputPath('password-refresh.png') })
})

for (const width of [1440, 375]) {
  test(`强制改密后平台概览自动清除旧错误并加载最新数据（${width}px）`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const runtimeErrors: string[] = []
    page.on('pageerror', (error) => runtimeErrors.push(error.message))
    const fixture = await installPasswordGate(page)
    await page.goto('/admin')
    await expect(page.getByRole('dialog', { name: '设置新密码', exact: true })).toBeVisible()
    await expect(page.getByRole('alert').filter({ hasText: '请先修改初始密码' })).toBeVisible()
    const requestsBeforeChange = fixture.dashboardRequests()

    await submitPassword(page)

    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByRole('alert').filter({ hasText: '请先修改初始密码' })).toHaveCount(0)
    await expect(page.getByText('启用学生', { exact: true })).toBeVisible()
    await expect(page.getByText('23', { exact: true })).toBeVisible()
    await expect.poll(fixture.dashboardRequests).toBeGreaterThan(requestsBeforeChange)
    await expect(page).toHaveURL(new URL('/admin', page.url()).href)
    expect(runtimeErrors).toEqual([])
  })
}

test('改密失败时保留强制弹窗和真实错误，不展示受保护数据', async ({ page }) => {
  await installPasswordGate(page, true)
  await page.goto('/admin')
  await submitPassword(page)

  const dialog = page.getByRole('dialog', { name: '设置新密码', exact: true })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('alert').filter({ hasText: '旧密码不正确' })).toBeVisible()
  await expect(dialog.getByLabel('当前密码', { exact: true })).toHaveValue('123456')
  await expect(page.getByText('启用学生', { exact: true })).toHaveCount(0)
})
