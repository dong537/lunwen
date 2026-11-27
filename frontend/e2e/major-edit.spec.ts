import { expect, test, type Page } from '@playwright/test'
import type { Major, User } from '../src/shared/api/contracts'

const admin: User = {
  id: '1',
  username: 'admin',
  name: '校级管理员',
  role: 'ADMIN',
  major: null,
  grade: null,
  teacherId: null,
  teacherName: null,
  collegeCode: null,
  collegeName: null,
  active: true,
  mustChangePassword: false,
}

async function installMajorFixture(page: Page, scoped = false, rejectFirst = false) {
  const patches: unknown[] = []
  const requestedColleges: (string | null)[] = []
  const unexpected: string[] = []
  const runtimeErrors: string[] = []
  const major: Major = {
    id: '301',
    collegeCode: 'EDUCATION_ARTS',
    name: '学前教育',
    active: false,
  }
  page.on('pageerror', (error) => runtimeErrors.push(error.message))
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    if (path === '/api/auth/me')
      data = scoped
        ? { ...admin, collegeCode: major.collegeCode, collegeName: '教育与艺术学院' }
        : admin
    else if (path === '/api/auth/csrf')
      data = { token: 'major-edit-fixture', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/admin/catalog')
      data = {
        schoolName: '山东石油化工学院',
        colleges: [
          { code: 'EDUCATION_ARTS', name: '教育与艺术学院' },
          ...(!scoped ? [{ code: 'INFORMATION_ENGINEERING', name: '信息科学与工程学院' }] : []),
        ],
      }
    else if (path === '/api/admin/majors' && request.method() === 'GET') {
      requestedColleges.push(url.searchParams.get('collegeCode'))
      data = [major]
    } else if (path === '/api/admin/majors/301' && request.method() === 'PATCH') {
      patches.push(request.postDataJSON())
      if (rejectFirst && patches.length === 1) {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            data: null,
            error: { code: 'MAJOR_EXISTS', message: '该学院已存在同名专业，请更换名称。' },
            requestId: 'major-edit-fixture',
          }),
        })
        return
      }
      major.name = (request.postDataJSON() as { name: string }).name
      data = major
    } else {
      unexpected.push(request.method() + ' ' + path)
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'major-edit-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data, error: null, requestId: 'major-edit-fixture' }),
    })
  })
  return { patches, requestedColleges, unexpected, runtimeErrors, major }
}

async function openEdit(page: Page, scoped = false) {
  await page.goto('/admin/majors')
  if (!scoped) {
    await page.getByLabel('专业所属学院').click()
    await page
      .locator('.ant-select-dropdown:visible')
      .getByText('教育与艺术学院', { exact: true })
      .click()
  }
  await expect(page.getByRole('row', { name: /学前教育/ })).toBeVisible()
  const edit = page.getByRole('button', { name: '修改学前教育', exact: true })
  await expect(edit).toBeVisible()
  await edit.click()
  const dialog = page.getByRole('dialog', { name: '修改专业名称', exact: true })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('修改后，已有账号中的专业名称保持不变。')).toBeVisible()
  await expect(dialog.getByLabel('专业名称', { exact: true })).toHaveValue('学前教育')
  return dialog
}

test.afterEach(async ({ page }, testInfo) => {
  await page.screenshot({ path: testInfo.outputPath('major-edit.png'), animations: 'disabled' })
})

for (const width of [1440, 375]) {
  test(
    '专业修改预填后可键盘保存并即时刷新，保留学院与启停状态（' + width + 'px）',
    async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 })
      const fixture = await installMajorFixture(page)
      const dialog = await openEdit(page)
      await expect
        .poll(() =>
          dialog.evaluate((element) =>
            element
              .getAnimations({ subtree: true })
              .every((animation) => !animation.pending && animation.playState !== 'running'),
          ),
        )
        .toBe(true)
      await page.screenshot({
        path: testInfo.outputPath('major-edit-dialog.png'),
        animations: 'disabled',
      })
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      ).toBe(true)
      await dialog.getByLabel('专业名称', { exact: true }).fill(' 学前教育学 ')
      await dialog.getByLabel('专业名称', { exact: true }).press('Enter')
      await expect(dialog).toHaveCount(0)
      const updated = page.getByRole('row', { name: /学前教育学/ })
      await expect(updated).toBeVisible()
      await expect(updated.getByRole('button', { name: '启用', exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: '修改学前教育', exact: true })).toHaveCount(0)
      await expect(page.getByText('专业名称已修改。', { exact: true })).toBeVisible()
      expect(fixture.patches).toEqual([{ name: '学前教育学' }])
      expect(fixture.major).toEqual({
        id: '301',
        collegeCode: 'EDUCATION_ARTS',
        name: '学前教育学',
        active: false,
      })
      expect(fixture.requestedColleges.length).toBeGreaterThanOrEqual(2)
      expect(fixture.requestedColleges.every((code) => code === 'EDUCATION_ARTS')).toBe(true)
      expect(fixture.unexpected).toEqual([])
      expect(fixture.runtimeErrors).toEqual([])
    },
  )
}

test('空白或超过64字符的专业名不提交，取消编辑也不发送请求', async ({ page }) => {
  const fixture = await installMajorFixture(page)
  const dialog = await openEdit(page)
  await dialog.getByLabel('专业名称', { exact: true }).fill('   ')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog.getByText('请填写专业名称', { exact: true })).toBeVisible()
  expect(fixture.patches).toEqual([])
  await dialog.getByLabel('专业名称', { exact: true }).fill('专'.repeat(65))
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog.getByText('专业名称不能超过64个字符', { exact: true })).toBeVisible()
  expect(fixture.patches).toEqual([])
  await dialog.getByLabel('专业名称', { exact: true }).fill('未保存专业')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('row', { name: /学前教育/ })).toBeVisible()
  expect(fixture.patches).toEqual([])
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

test('学院管理员修改失败保留名称和错误，可重试且学院始终锁定', async ({ page }) => {
  const fixture = await installMajorFixture(page, true, true)
  const dialog = await openEdit(page, true)
  await expect(page.getByLabel('专业所属学院')).toBeDisabled()
  await dialog.getByLabel('专业名称', { exact: true }).fill('学前教育学')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('该学院已存在同名专业，请更换名称。')
  await expect(dialog.getByLabel('专业名称', { exact: true })).toHaveValue('学前教育学')
  await expect(page.getByRole('row', { name: /学前教育学/ })).toHaveCount(0)
  await expect(dialog.getByLabel('专业名称', { exact: true })).toBeEnabled()
  await dialog.getByRole('button', { name: /保存$/ }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('row', { name: /学前教育学/ })).toBeVisible()
  await expect(page.getByLabel('专业所属学院')).toBeDisabled()
  expect(fixture.patches).toEqual([{ name: '学前教育学' }, { name: '学前教育学' }])
  expect(fixture.requestedColleges.every((code) => code === 'EDUCATION_ARTS')).toBe(true)
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})
