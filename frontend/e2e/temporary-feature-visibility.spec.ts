import { expect, test, type Page } from '@playwright/test'

const teacher = {
  id: '10',
  username: 'teacher01',
  name: '教师甲',
  role: 'TEACHER',
  major: null,
  grade: null,
  teacherId: null,
  teacherName: null,
  collegeCode: null,
  collegeName: null,
  active: true,
  mustChangePassword: false,
}
const admin = { ...teacher, id: '1', username: 'admin', name: '管理员', role: 'ADMIN' }
const student = {
  ...teacher,
  id: '20',
  username: '22000001',
  name: '学生甲',
  role: 'STUDENT',
  major: '计算机科学与技术',
  grade: 2023,
  teacherId: teacher.id,
  teacherName: teacher.name,
}

async function installFixture(page: Page, user = teacher) {
  const requests: string[] = []
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) {
      await route.continue()
      return
    }
    requests.push(request.method() + ' ' + url.pathname + url.search)
    let data: unknown
    if (url.pathname === '/api/auth/me') data = user
    else if (url.pathname === '/api/teacher/students')
      data = {
        items: [{ ...student, submissionCount: 2, lastSubmittedAt: null, latestProblemCount: 5 }],
        total: 1,
        page: 1,
        size: 10,
      }
    else if (url.pathname === '/api/teacher/students/20') data = student
    else if (url.pathname === '/api/teacher/students/20/submissions')
      data = { items: [], total: 0, page: 1, size: 10 }
    else if (url.pathname === '/api/admin/dashboard')
      data = {
        activeStudents: 1,
        activeTeachers: 1,
        totalSubmissions: 2,
        dailySubmissions: [],
        statusDistribution: [],
        studentsByCollege: [],
      }
    else if (url.pathname === '/api/admin/catalog')
      data = { schoolName: '山东石油化工学院', colleges: [] }
    else if (url.pathname === '/api/admin/majors') data = []
    else if (url.pathname === '/api/teacher/statistics')
      data = {
        totalStudents: 1,
        activeStudents: 1,
        submittedStudents: 1,
        notSubmittedStudents: 0,
        studentsWithSuccessfulReview: 1,
        successfulSubmissions: 2,
        pendingSubmissions: 0,
        processingSubmissions: 0,
        failedSubmissions: 0,
        latestSuccessfulProblemCount: 5,
      }
    else if (url.pathname === '/api/teacher/statistics/insights')
      data = {
        coverage: { eligibleReviews: 0, indexedReviews: 0 },
        topRules: [],
        problemDistribution: [],
        dailySubmissions: [],
        heatmap: { rules: [], students: [] },
        students: { items: [], total: 0, page: 1, size: 10 },
      }
    else data = []
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data, error: null, requestId: 'temporary-visibility-fixture' }),
    })
  })
  return { requests, errors }
}

for (const width of [1440, 375]) {
  test(`教师隐藏统计与新增导入入口，名单搜索及查看仍可用（${width}px）`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const { requests, errors } = await installFixture(page)
    await page.goto('/teacher/students')
    await expect(page.getByRole('heading', { name: '我的学生' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: /统计/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /新增学生|导入名单/ })).toHaveCount(0)
    await expect(page.getByText(/学院尚未归属/)).toHaveCount(0)
    await expect(page.getByRole('row', { name: /22000001/ })).toContainText('学生甲')
    await page.getByPlaceholder('按学号或姓名搜索').fill('22000001')
    await page.getByPlaceholder('按学号或姓名搜索').press('Enter')
    await expect.poll(() => requests.some((request) => request.includes('q=22000001'))).toBe(true)
    await page
      .getByRole('row', { name: /22000001/ })
      .getByRole('link', { name: '查看' })
      .click()
    await expect(page).toHaveURL(/\/teacher\/students\/20$/)
    await expect(page.getByRole('heading', { name: '学生甲' })).toBeVisible()
    await page.getByRole('link', { name: /返回我的学生/ }).click()
    await expect(page.getByRole('heading', { name: '我的学生' })).toBeVisible()
    await page.screenshot({
      path: testInfo.outputPath(`teacher-hidden-${width}.png`),
      animations: 'disabled',
    })
    expect(
      requests.filter((request) => /\/teacher\/(statistics|majors)|\/imports\//.test(request)),
    ).toEqual([])
    expect(errors).toEqual([])
  })
}

for (const path of ['/teacher/statistics', '/teacher/students/import']) {
  test(`教师旧链接 ${path} 返回名单且不加载隐藏模块`, async ({ page }) => {
    const { requests, errors } = await installFixture(page)
    await page.goto(path)
    await expect(page).toHaveURL(/\/teacher\/students$/)
    await expect(page.getByRole('heading', { name: '我的学生' })).toBeVisible()
    await expect(page.getByRole('heading', { name: /指导统计|导入指导名单/ })).toHaveCount(0)
    expect(
      requests.filter((request) => /\/teacher\/(statistics|majors)|\/imports\//.test(request)),
    ).toEqual([])
    expect(errors).toEqual([])
  })
}

test('管理员专业目录旧链接回平台概览，保留账号和批量导入菜单', async ({ page }, testInfo) => {
  const { requests, errors } = await installFixture(page, admin)
  await page.goto('/admin/majors')
  await expect(page).toHaveURL(/\/admin$/)
  await expect(page.getByRole('heading', { name: '平台概览' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: /专业目录/ })).toHaveCount(0)
  await expect(page.getByRole('menuitem', { name: /账号管理/ })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: /批量导入/ })).toBeVisible()
  expect(requests.filter((request) => /\/admin\/(majors|catalog)/.test(request))).toEqual([])
  expect(errors).toEqual([])
  await page.screenshot({
    path: testInfo.outputPath('admin-hidden-majors.png'),
    animations: 'disabled',
  })
})

for (const { user, path } of [
  { user: student, path: '/teacher/statistics' },
  { user: student, path: '/admin/majors' },
  { user: teacher, path: '/admin/majors' },
  { user: admin, path: '/teacher/statistics' },
]) {
  test(`${user.role} 访问其他角色的隐藏路由 ${path} 仍被角色守卫拒绝`, async ({ page }) => {
    const { requests, errors } = await installFixture(page, user)
    await page.goto(path)
    await expect(page.getByRole('heading', { name: '无权访问' })).toBeVisible()
    await expect(page.getByRole('heading', { name: /指导统计|专业目录/ })).toHaveCount(0)
    await expect(page.getByRole('link', { name: '返回工作台' })).toBeVisible()
    expect(requests.length).toBeGreaterThan(0)
    expect(requests.every((request) => request === 'GET /api/auth/me')).toBe(true)
    expect(errors).toEqual([])
  })
}
