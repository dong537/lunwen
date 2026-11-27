import { expect, test } from '@playwright/test'

function envelope(data: unknown) {
  return { data, error: null, requestId: 'iteration-11-browser-fixture' }
}

const admin = {
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

test('管理员概览展示真实聚合，账号可筛未填写年级', async ({ page }, testInfo) => {
  const errors: string[] = []
  const grades: (string | null)[] = []
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
    let data: unknown
    if (url.pathname === '/api/auth/me') data = admin
    else if (url.pathname === '/api/admin/dashboard')
      data = {
        activeStudents: 8,
        activeTeachers: 3,
        totalSubmissions: 12,
        dailySubmissions: [
          { date: '2026-11-15', count: 2 },
          { date: '2026-11-16', count: 5 },
        ],
        statusDistribution: [
          { status: 'SUCCESS', count: 7 },
          { status: 'FAILED', count: 2 },
        ],
        studentsByCollege: [
          { collegeCode: 'INFORMATION_ENGINEERING', collegeName: '信息科学与工程学院', count: 8 },
        ],
      }
    else if (url.pathname === '/api/admin/catalog')
      data = {
        schoolName: '山东石油化工学院',
        colleges: [{ code: 'INFORMATION_ENGINEERING', name: '信息科学与工程学院' }],
      }
    else if (url.pathname === '/api/admin/users') {
      grades.push(url.searchParams.get('grade'))
      const unset = url.searchParams.get('grade') === 'UNSET'
      const students = [
        {
          ...admin,
          id: '20',
          username: '22000001',
          name: '历史学生',
          role: 'STUDENT',
          grade: null,
          collegeName: '信息科学与工程学院',
        },
        {
          ...admin,
          id: '21',
          username: '23000001',
          name: '新生',
          role: 'STUDENT',
          grade: 2023,
          collegeName: '信息科学与工程学院',
        },
      ]
      data = {
        items: unset ? students.slice(0, 1) : students,
        total: unset ? 1 : 2,
        page: 1,
        size: 10,
      }
    } else {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'iteration-11-browser-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(data)),
    })
  })
  await page.goto('/admin')
  await expect(page.getByRole('heading', { name: '平台概览' })).toBeVisible()
  await expect(page.getByText('近 30 天提交趋势', { exact: true })).toBeVisible()
  await expect(page.getByRole('img', { name: /2026-11-16 5 次/ })).toBeVisible()
  await expect(page.getByText('信息科学与工程学院')).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('admin-dashboard-populated.png'),
    fullPage: true,
  })
  await page.goto('/admin/users')
  await expect(page.getByRole('row', { name: /历史学生/ })).toBeVisible()
  await page.getByRole('button', { name: '未填写年级' }).click()
  await expect(page.getByRole('row', { name: /新生/ })).toHaveCount(0)
  await expect(page.getByRole('row', { name: /历史学生/ })).toBeVisible()
  expect(grades).toContain('UNSET')
  expect(errors).toEqual([])
})

test('教师统计筛选、规则覆盖和导出使用同一条件', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const errors: string[] = []
  const insightRequests: string[] = []
  const exportRequests: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) {
      await route.continue()
      return
    }
    if (url.pathname === '/api/teacher/statistics/export') {
      exportRequests.push(url.search)
      await route.fulfill({
        status: 200,
        headers: {
          'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          'Content-Disposition': 'attachment; filename="stats.xlsx"',
        },
        body: 'test workbook bytes',
      })
      return
    }
    let data: unknown
    if (url.pathname === '/api/auth/me')
      data = {
        ...admin,
        id: '10',
        username: 'teacher01',
        name: '教师甲',
        role: 'TEACHER',
        collegeCode: 'INFORMATION_ENGINEERING',
        collegeName: '信息科学与工程学院',
      }
    else if (url.pathname === '/api/teacher/statistics')
      data = {
        totalStudents: 2,
        activeStudents: 2,
        submittedStudents: 1,
        notSubmittedStudents: 1,
        studentsWithSuccessfulReview: 1,
        successfulSubmissions: 2,
        pendingSubmissions: 0,
        processingSubmissions: 0,
        failedSubmissions: 1,
        latestSuccessfulProblemCount: 4,
      }
    else if (url.pathname === '/api/teacher/statistics/insights') {
      insightRequests.push(url.search)
      data = {
        coverage: { eligibleReviews: 2, indexedReviews: 1 },
        topRules: [
          { ruleId: 'BODY-INDENT', description: '正文首行缩进', count: 4, affectedStudents: 1 },
        ],
        problemDistribution: [
          { label: '0 个', students: 1 },
          { label: '1–5 个', students: 1 },
        ],
        dailySubmissions: [
          { date: '2026-11-15', count: 1 },
          { date: '2026-11-16', count: 2 },
        ],
        heatmap: {
          rules: [{ ruleId: 'BODY-INDENT', description: '正文首行缩进' }],
          students: [{ studentId: '20', name: '学生甲', counts: { 'BODY-INDENT': 4 } }],
        },
        students: {
          items: [
            {
              id: '20',
              username: '23000001',
              name: '学生甲',
              grade: 2023,
              major: '计算机科学与技术',
              lastSubmittedAt: '2026-11-16T06:00:00Z',
              latestProblemCount: 4,
              latestReviewStatus: 'SUCCESS',
            },
          ],
          total: 1,
          page: 1,
          size: 10,
        },
      }
    } else {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'iteration-11-browser-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(data)),
    })
  })
  await page.goto('/teacher/statistics')
  await expect(page.getByRole('heading', { name: '指导统计' })).toBeVisible()
  await expect(page.getByText('规则明细已覆盖 1 / 2 份最近成功审核')).toBeVisible()
  await expect(page.getByText('规则明细覆盖 1 / 2 份最近成功审核')).toHaveCount(0)
  await expect(page.getByText('高频格式问题 TOP10', { exact: true })).toBeVisible()
  await expect(page.getByText('学生明细', { exact: true })).toHaveCount(0)
  const ranking = page.getByRole('table', { name: '问题数与触达人数', exact: true })
  await expect(ranking.getByRole('columnheader', { name: '问题数', exact: true })).toBeVisible()
  await expect(ranking.getByRole('columnheader', { name: '触达人数', exact: true })).toBeVisible()
  const rankedRule = ranking.getByRole('row').filter({ hasText: '正文首行缩进' })
  await expect(rankedRule.getByRole('cell', { name: '4', exact: true })).toBeVisible()
  await expect(rankedRule.getByRole('cell', { name: '1人', exact: true })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '正文首行缩进' })).toBeVisible()
  await expect(page.getByRole('row', { name: /23000001/ })).toHaveCount(0)
  await page.screenshot({
    path: testInfo.outputPath('teacher-statistics-populated.png'),
    fullPage: true,
  })
  await page.getByRole('spinbutton', { name: '按入学年级筛选统计' }).fill('2023')
  await page.getByRole('button', { name: '筛选年级' }).click()
  await page.getByRole('combobox', { name: '统计时间范围' }).click()
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '近 90 天' })
    .click()
  await expect
    .poll(() =>
      insightRequests.some(
        (request) => request.includes('grade=2023') && request.includes('range=90'),
      ),
    )
    .toBe(true)
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Excel' }).click()
  await expect((await download).suggestedFilename()).toBe('stats.xlsx')
  expect(exportRequests).toEqual([expect.stringContaining('grade=2023')])
  expect(exportRequests[0]).toContain('range=90')
  await page.screenshot({
    path: testInfo.outputPath('teacher-statistics-filtered.png'),
    fullPage: true,
  })
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(rankedRule.getByRole('cell', { name: '1人', exact: true })).toBeVisible()
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(375)
  await ranking.scrollIntoViewIfNeeded()
  await page.screenshot({
    path: testInfo.outputPath('teacher-statistics-reach-375.png'),
    fullPage: true,
  })
  expect(errors).toEqual([])
})

test('教师统计空态在 375px 可阅读且没有整页横向溢出', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 375, height: 812 })
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    if (url.pathname === '/api/auth/me')
      data = {
        ...admin,
        id: '10',
        username: 'teacher01',
        name: '教师甲',
        role: 'TEACHER',
        collegeCode: 'INFORMATION_ENGINEERING',
        collegeName: '信息科学与工程学院',
      }
    else if (url.pathname === '/api/teacher/statistics')
      data = {
        totalStudents: 0,
        activeStudents: 0,
        submittedStudents: 0,
        notSubmittedStudents: 0,
        studentsWithSuccessfulReview: 0,
        successfulSubmissions: 0,
        pendingSubmissions: 0,
        processingSubmissions: 0,
        failedSubmissions: 0,
        latestSuccessfulProblemCount: null,
      }
    else if (url.pathname === '/api/teacher/statistics/insights')
      data = {
        coverage: { eligibleReviews: 0, indexedReviews: 0 },
        topRules: [],
        problemDistribution: [],
        dailySubmissions: [],
        heatmap: { rules: [], students: [] },
        students: { items: [], total: 0, page: 1, size: 20 },
      }
    else {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'iteration-11-browser-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(data)),
    })
  })
  await page.goto('/teacher/statistics')
  await expect(page.getByRole('heading', { name: '指导统计' })).toBeVisible()
  await expect(page.getByText('所选范围内没有已索引的规则问题')).toBeVisible()
  await expect(page.getByText('所选范围内没有成功审核')).toBeVisible()
  await expect(page.getByText('所选范围内没有提交记录')).toBeVisible()
  await expect(page.getByText('所选范围内暂无可展示的规则明细')).toBeVisible()
  await expect(page.getByText('规则明细覆盖 0 / 0 份最近成功审核')).toHaveCount(0)
  await expect(page.getByText('学生明细', { exact: true })).toHaveCount(0)
  await expect(page.getByText('所选范围内没有学生记录。')).toHaveCount(0)
  await expect(page.getByText('—', { exact: true })).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('teacher-statistics-empty-375.png'),
    fullPage: true,
  })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
  expect(errors).toEqual([])
})

test('触达人数直接取完整榜单数据，长问题名和大数值在桌面手机均可阅读', async ({
  page,
}, testInfo) => {
  const errors: string[] = []
  const description = '正文首行缩进应为2字符；包含较长内容时仍需保持每一段的缩进格式一致'
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    if (path === '/api/auth/me')
      data = { ...admin, id: '10', role: 'TEACHER', collegeCode: 'INFORMATION_ENGINEERING' }
    else if (path === '/api/teacher/statistics')
      data = {
        totalStudents: 12,
        activeStudents: 12,
        submittedStudents: 12,
        notSubmittedStudents: 0,
        studentsWithSuccessfulReview: 12,
        successfulSubmissions: 12,
        pendingSubmissions: 0,
        processingSubmissions: 0,
        failedSubmissions: 0,
        latestSuccessfulProblemCount: 12345,
      }
    else if (path === '/api/teacher/statistics/insights')
      data = {
        coverage: { eligibleReviews: 12, indexedReviews: 12 },
        topRules: [{ ruleId: 'BODY-INDENT', description, count: 12345, affectedStudents: 12 }],
        problemDistribution: [{ label: '100 个以上', students: 12 }],
        dailySubmissions: [{ date: '2026-11-27', count: 12 }],
        heatmap: {
          rules: [{ ruleId: 'BODY-INDENT', description }],
          students: Array.from({ length: 10 }, (_, index) => ({
            studentId: String(index + 20),
            name: '学生' + (index + 1),
            counts: { 'BODY-INDENT': 900 },
          })),
        },
        students: {
          items: Array.from({ length: 10 }, (_, index) => ({
            id: String(index + 20),
            username: '230000' + String(index + 1).padStart(2, '0'),
            name: '学生' + (index + 1),
            grade: 2023,
            major: '计算机科学与技术',
            lastSubmittedAt: '2026-11-27T01:00:00Z',
            latestProblemCount: 900,
            latestReviewStatus: 'SUCCESS',
          })),
          total: 12,
          page: 1,
          size: 10,
        },
      }
    else throw new Error('Unexpected statistics fixture request: ' + path)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(data)),
    })
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/teacher/statistics')
  const ranking = page.getByRole('table', { name: '问题数与触达人数', exact: true })
  const card = page.locator('.ant-card').filter({ has: ranking })
  await expect(ranking.getByRole('cell', { name: '12345', exact: true })).toBeVisible()
  await expect(ranking.getByRole('cell', { name: '12人', exact: true })).toBeVisible()
  await expect(ranking.getByRole('rowheader', { name: description, exact: true })).toBeVisible()
  await expect(page.getByText(/^规则明细(?:已)?覆盖/)).toHaveCount(0)
  for (const width of [1440, 375]) {
    await page.setViewportSize({ width, height: 900 })
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
      .toBeLessThanOrEqual(width)
    await card.screenshot({
      path: testInfo.outputPath('reach-ranking-' + width + '.png'),
      animations: 'disabled',
    })
  }
  expect(errors).toEqual([])
})

test('第 101 位同院导师可滚动加载并搜索选择，跨院导师不出现', async ({ page }) => {
  const teacherRequests: { college: string | null; q: string; page: number }[] = []
  const errors: string[] = []
  const collegeCode = 'INFORMATION_ENGINEERING'
  const teachers = Array.from({ length: 101 }, (_, index) => ({
    ...admin,
    id: String(index + 101),
    role: 'TEACHER',
    username: 'teacher' + String(index + 1).padStart(3, '0'),
    name: '教师' + String(index + 1).padStart(3, '0'),
    collegeCode,
    collegeName: '信息科学与工程学院',
  }))
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    if (url.pathname === '/api/auth/me') data = admin
    else if (url.pathname === '/api/admin/catalog')
      data = {
        schoolName: '山东石油化工学院',
        colleges: [
          { code: collegeCode, name: '信息科学与工程学院' },
          { code: 'EDUCATION_ARTS', name: '教育与艺术学院' },
        ],
      }
    else if (url.pathname === '/api/admin/users') data = { items: [], total: 0, page: 1, size: 10 }
    else if (url.pathname === '/api/admin/majors') data = []
    else if (url.pathname === '/api/admin/teachers') {
      const college = url.searchParams.get('collegeCode')
      const q = url.searchParams.get('q') ?? ''
      const pageNumber = Number(url.searchParams.get('page') ?? '1')
      const size = Number(url.searchParams.get('size') ?? '50')
      teacherRequests.push({ college, q, page: pageNumber })
      const matches =
        college === collegeCode
          ? teachers.filter((teacher) => teacher.username.includes(q) || teacher.name.includes(q))
          : [{ ...teachers[0], id: '999', name: '跨院教师', collegeCode: 'EDUCATION_ARTS' }]
      data = {
        items: matches.slice((pageNumber - 1) * size, pageNumber * size),
        total: matches.length,
        page: pageNumber,
        size,
      }
    } else {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'iteration-11-browser-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(data)),
    })
  })

  await page.goto('/admin/users')
  await page.getByRole('button', { name: /新建学生/ }).click()
  let dialog = page.getByRole('dialog', { name: '新建学生账号' })
  await dialog.getByLabel('学院', { exact: true }).click()
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '信息科学与工程学院' })
    .click()
  await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0)
  const teacherField = dialog.getByLabel('指导教师（选填）')
  await teacherField.click()
  const dropdown = page.locator('.ant-select-dropdown:visible')
  const holder = dropdown.locator('.ant-select-dropdown-list-holder')
  await expect
    .poll(() => teacherRequests.some((request) => request.page === 1 && request.q === ''))
    .toBe(true)
  for (const nextPage of [2, 3]) {
    for (
      let attempt = 0;
      attempt < 20 &&
      !teacherRequests.some((request) => request.page === nextPage && request.q === '');
      attempt++
    ) {
      await holder.hover()
      await page.mouse.wheel(0, 240)
    }
    expect(teacherRequests.some((request) => request.page === nextPage && request.q === '')).toBe(
      true,
    )
  }
  await dropdown
    .locator('.ant-select-item-option')
    .filter({ hasText: '教师101 · teacher101' })
    .click()
  await expect(dropdown).toHaveCount(0)
  await expect(dialog).toContainText('教师101 · teacher101')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()

  await page.getByRole('button', { name: /新建学生/ }).click()
  dialog = page.getByRole('dialog', { name: '新建学生账号' })
  await dialog.getByLabel('学院', { exact: true }).click()
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '信息科学与工程学院' })
    .click()
  await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0)
  await dialog.getByLabel('指导教师（选填）').fill('teacher101')
  await expect.poll(() => teacherRequests.some((request) => request.q === 'teacher101')).toBe(true)
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '教师101 · teacher101' })
    .click()
  await expect(dialog).toContainText('教师101 · teacher101')
  expect(teacherRequests.every((request) => request.college === collegeCode)).toBe(true)
  await expect(page.getByText('跨院教师')).toHaveCount(0)
  expect(errors).toEqual([])
})
