import { expect, test, type Page } from '@playwright/test'
import type { Submission, User } from '../src/shared/api/contracts'

const collegeAdmin: User = {
  id: '10',
  username: 'college_admin',
  name: '学院管理员甲',
  role: 'ADMIN',
  major: null,
  grade: null,
  teacherId: null,
  teacherName: null,
  collegeCode: 'INFORMATION_ENGINEERING',
  collegeName: '信息科学与工程学院',
  active: true,
  mustChangePassword: false,
}

const student: User = {
  ...collegeAdmin,
  id: '20',
  username: '22000001',
  name: '指导学生甲',
  role: 'STUDENT',
  major: '计算机科学与技术',
  grade: 2023,
  teacherId: collegeAdmin.id,
  teacherName: collegeAdmin.name,
}

const submission: Submission = {
  id: '30',
  studentId: student.id,
  submissionNumber: 1,
  originalFilename: '指导学生论文.docx',
  fileSize: 1024,
  status: 'SUCCESS',
  problemCount: 2,
  byScope: { 正文: 2 },
  errorMessage: null,
  createdAt: '2026-11-27T01:00:00Z',
  startedAt: '2026-11-27T01:00:01Z',
  finishedAt: '2026-11-27T01:00:02Z',
  canDownloadOriginal: true,
  canDownloadMarked: true,
  canDownloadReport: true,
}

async function installGuidanceFixture(page: Page, user: User) {
  const requests: string[] = []
  const unexpected: string[] = []
  const runtimeErrors: string[] = []
  page.on('pageerror', (error) => runtimeErrors.push(error.message))
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    requests.push(path)
    let data: unknown
    if (path === '/api/auth/me') data = user
    else if (path === '/api/admin/dashboard')
      data = {
        activeStudents: 1,
        activeTeachers: 2,
        totalSubmissions: 1,
        dailySubmissions: [],
        statusDistribution: [],
        studentsByCollege: [],
      }
    else if (path === '/api/teacher/students')
      data = {
        items: [
          {
            id: student.id,
            username: student.username,
            name: student.name,
            major: student.major,
            grade: student.grade,
            submissionCount: 1,
            lastSubmittedAt: submission.createdAt,
            latestProblemCount: 2,
          },
        ],
        total: 1,
        page: 1,
        size: 10,
      }
    else if (path === '/api/teacher/students/20') data = student
    else if (path === '/api/teacher/students/20/submissions')
      data = { items: [submission], total: 1, page: 1, size: 10 }
    else if (path === '/api/submissions/30') data = submission
    else if (path === '/api/submissions/transfer-mode') data = { mode: 'proxy' }
    else if (path === '/api/teacher/majors')
      data = [
        {
          id: '1',
          name: student.major,
          collegeCode: user.collegeCode,
          active: true,
        },
      ]
    else {
      unexpected.push(path)
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'college-guidance-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data, error: null, requestId: 'college-guidance-fixture' }),
    })
  })
  return { requests, unexpected, runtimeErrors }
}

test.afterEach(async ({ page }, testInfo) => {
  await page.screenshot({ path: testInfo.outputPath('college-guidance.png') })
})

for (const width of [1440, 375]) {
  test(`学院管理员保留管理入口并查看本人指导学生及论文（${width}px）`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const fixture = await installGuidanceFixture(page, collegeAdmin)
    await page.goto('/admin')
    await expect(page.getByRole('heading', { name: '平台概览' })).toBeVisible()
    for (const name of ['平台概览', '账号管理', '专业目录', '批量导入', '我的学生']) {
      await expect(page.getByRole('menuitem', { name: new RegExp(name + '$') })).toBeVisible()
    }
    await expect(page.getByRole('menuitem', { name: /统计$/ })).toHaveCount(0)
    await page.getByRole('menuitem', { name: /我的学生$/ }).click()
    await expect(page.getByRole('heading', { name: '我的学生' })).toBeVisible()
    await expect(page.getByRole('row', { name: /22000001/ })).toContainText('指导学生甲')
    expect(fixture.requests).toContain('/api/teacher/students')

    await page.getByRole('button', { name: /新增学生$/ }).click()
    const dialog = page.getByRole('dialog', { name: '新增指导学生' })
    await expect(dialog.locator('input[readonly]').nth(1)).toHaveValue('信息科学与工程学院')
    await expect.poll(() => fixture.requests.includes('/api/teacher/majors')).toBe(true)
    await dialog.getByRole('button', { name: '取消', exact: true }).click()
    await page.getByRole('button', { name: /导入名单$/ }).click()
    await expect(page.getByRole('heading', { name: '导入指导名单' })).toBeVisible()
    await expect(page.getByRole('button', { name: '下载名单模板' })).toBeEnabled()
    await page.getByRole('link', { name: '返回我的学生' }).click()

    await page
      .getByRole('row', { name: /22000001/ })
      .getByRole('link', { name: '查看' })
      .click()
    await expect(page.getByRole('heading', { name: '指导学生甲' })).toBeVisible()
    await expect(page.getByRole('row', { name: /指导学生论文.docx/ })).toBeVisible()
    await page
      .getByRole('row', { name: /指导学生论文.docx/ })
      .getByRole('link', { name: '查看' })
      .click()
    await expect(page.getByRole('heading', { name: '第 1 次提交' })).toBeVisible()
    const back = page.getByRole('link', { name: '返回提交列表' })
    await expect(back).toHaveAttribute('href', '/teacher/students/20')
    await expect(page.getByRole('menuitem', { name: /我的学生$/ })).toHaveClass(
      /ant-menu-item-selected/,
    )
    await back.click()
    await expect(page.getByRole('heading', { name: '指导学生甲' })).toBeVisible()
    await page.getByRole('link', { name: '返回我的学生' }).click()
    await expect(page.getByRole('heading', { name: '我的学生' })).toBeVisible()
    expect(fixture.unexpected).toEqual([])
    expect(fixture.runtimeErrors).toEqual([])
  })
}

test('校级管理员没有指导入口，直接路由也不请求学生或论文接口', async ({ page }) => {
  const fixture = await installGuidanceFixture(page, {
    ...collegeAdmin,
    collegeCode: null,
    collegeName: null,
  })
  for (const path of [
    '/teacher/students',
    '/teacher/students/import',
    '/teacher/students/20',
    '/submissions/30',
  ]) {
    await page.goto(path)
    await expect(page.getByRole('heading', { name: '无权访问' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: /我的学生$/ })).toHaveCount(0)
  }
  expect(fixture.requests.filter((path) => path !== '/api/auth/me')).toEqual([])
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

test('学院管理员不能通过直接路由访问教师统计', async ({ page }) => {
  const fixture = await installGuidanceFixture(page, collegeAdmin)
  await page.goto('/teacher/statistics')
  await expect(page.getByRole('heading', { name: '无权访问' })).toBeVisible()
  expect(fixture.requests.filter((path) => path !== '/api/auth/me')).toEqual([])
  expect(fixture.unexpected).toEqual([])
})

test('普通教师仍可查看指导学生，提交详情返回原学生页面', async ({ page }) => {
  const fixture = await installGuidanceFixture(page, { ...collegeAdmin, role: 'TEACHER' })
  await page.goto('/teacher/students')
  await expect(page.getByRole('row', { name: /22000001/ })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: /统计$/ })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: /账号管理$/ })).toHaveCount(0)
  await page.goto('/submissions/30')
  await expect(page.getByRole('link', { name: '返回提交列表' })).toHaveAttribute(
    'href',
    '/teacher/students/20',
  )
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

test('学生论文详情仍返回本人的提交列表', async ({ page }) => {
  const fixture = await installGuidanceFixture(page, student)
  await page.goto('/submissions/30')
  await expect(page.getByRole('link', { name: '返回提交列表' })).toHaveAttribute(
    'href',
    '/student/submissions',
  )
  await expect(page.getByRole('menuitem', { name: /我的学生$/ })).toHaveCount(0)
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})
