import { expect, test, type Page } from '@playwright/test'

function envelope(data: unknown) {
  return { data, error: null, requestId: 'teacher-browser-fixture' }
}

async function expectNoPageOverflow(page: Page) {
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .every(
        (animation) =>
          animation.effect?.getTiming().iterations === Infinity ||
          (!animation.pending && animation.playState !== 'running'),
      ),
  )
  const width = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: window.innerWidth,
    overflowingElements: Array.from(document.querySelectorAll('body *'))
      .map((element) => ({
        tag: element.tagName,
        className: element.className,
        right: element.getBoundingClientRect().right,
      }))
      .filter((element) => element.right > window.innerWidth)
      .slice(0, 20),
  }))
  expect(width.document, JSON.stringify(width)).toBeLessThanOrEqual(width.viewport)
}

const teacher = {
  id: '10',
  username: 'teacher01',
  name: '教师甲',
  role: 'TEACHER',
  major: null,
  teacherId: null,
  teacherName: null,
  collegeCode: 'INFORMATION_ENGINEERING',
  collegeName: '信息科学与工程学院',
  active: true,
  mustChangePassword: false,
}

test('教师新增本院学生后刷新名单，并从菜单查看没有成功审核的统计', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const errors: string[] = []
  const unexpectedRequests: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  let listRequests = 0
  let createdBody: unknown
  const students: unknown[] = []
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    if (path === '/api/auth/me') data = teacher
    else if (path === '/api/auth/csrf')
      data = { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/teacher/majors')
      data = [
        {
          id: '1',
          collegeCode: 'INFORMATION_ENGINEERING',
          name: '计算机科学与技术',
          active: true,
        },
      ]
    else if (path === '/api/teacher/students' && request.method() === 'GET') {
      listRequests += 1
      data = { items: students, total: students.length, page: 1, size: 10 }
    } else if (path === '/api/teacher/students' && request.method() === 'POST') {
      createdBody = request.postDataJSON()
      students.push({
        id: '20',
        username: '22000001',
        name: '学生甲',
        grade: 2023,
        major: '计算机科学与技术',
        submissionCount: 0,
        lastSubmittedAt: null,
        latestProblemCount: null,
      })
      data = {
        ...teacher,
        id: '20',
        username: '22000001',
        name: '学生甲',
        role: 'STUDENT',
        grade: 2023,
        major: '计算机科学与技术',
        teacherId: teacher.id,
        teacherName: teacher.name,
        mustChangePassword: false,
      }
    } else if (path === '/api/teacher/statistics')
      data = {
        totalStudents: 1,
        activeStudents: 1,
        submittedStudents: 0,
        notSubmittedStudents: 1,
        studentsWithSuccessfulReview: 0,
        successfulSubmissions: 0,
        pendingSubmissions: 0,
        processingSubmissions: 0,
        failedSubmissions: 0,
        latestSuccessfulProblemCount: null,
      }
    else if (path === '/api/teacher/statistics/insights')
      data = {
        coverage: { eligibleReviews: 0, indexedReviews: 0 },
        topRules: [],
        problemDistribution: [],
        dailySubmissions: [],
        heatmap: { rules: [], students: [] },
        students: { items: students, total: students.length, page: 1, size: 10 },
      }
    else {
      unexpectedRequests.push(request.method() + ' ' + path)
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'teacher-browser-fixture',
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

  await page.goto('/teacher/students')
  await expect(page).toHaveTitle(/我的学生/)
  await expect(page.getByText('还没有分配指导学生。')).toBeVisible()
  await page.getByRole('button', { name: /新增学生/ }).click()
  const dialog = page.getByRole('dialog', { name: '新增指导学生' })
  await expect(dialog.locator('input[readonly]').nth(0)).toHaveValue('山东石油化工学院')
  await expect(dialog.locator('input[readonly]').nth(1)).toHaveValue('信息科学与工程学院')
  await dialog.getByLabel('学号').fill('22000001')
  await dialog.getByLabel('姓名').fill('学生甲')
  await dialog.getByLabel('入学年级').fill('2023')
  await dialog.getByLabel('专业（选填）').click()
  await expect(page.getByRole('option', { name: '计算机科学与技术', exact: true })).toHaveCount(1)
  await dialog.getByRole('combobox', { name: '专业（选填）' }).press('ArrowDown')
  await dialog.getByRole('combobox', { name: '专业（选填）' }).press('Enter')
  await expect(dialog.getByTitle('计算机科学与技术', { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('row', { name: /22000001/ })).toContainText('学生甲')
  await expect(page.getByText('已新增学生甲的账号，初始密码为 123456，可直接登录。')).toBeVisible()
  expect(createdBody).toEqual({
    username: '22000001',
    name: '学生甲',
    grade: 2023,
    major: '计算机科学与技术',
  })
  expect(listRequests).toBeGreaterThanOrEqual(2)

  await page.getByRole('menuitem', { name: /统计/ }).click()
  await expect(page).toHaveURL(/\/teacher\/statistics$/)
  await expect(page.getByRole('heading', { name: '指导统计' })).toBeVisible()
  const latestProblems = page
    .locator('.ant-statistic')
    .filter({ hasText: '各学生最近成功审核问题数合计' })
  await expect(latestProblems.locator('.ant-statistic-content')).toHaveText('—')
  await expect(
    page.getByText('“—”表示尚无成功审核，不代表零问题。', { exact: false }),
  ).toBeVisible()
  await expectNoPageOverflow(page)
  await page.screenshot({
    path: testInfo.outputPath('teacher-statistics-1440.png'),
    animations: 'disabled',
  })
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(latestProblems.locator('.ant-statistic-content')).toHaveText('—')
  await expectNoPageOverflow(page)
  await page.screenshot({
    path: testInfo.outputPath('teacher-statistics-375.png'),
    animations: 'disabled',
  })
  await page.goto('/teacher/students')
  await expect(page.getByRole('row', { name: /22000001/ })).toContainText('学生甲')
  await expectNoPageOverflow(page)
  await page.getByRole('button', { name: /新增学生/ }).click()
  const mobileDialog = page.getByRole('dialog', { name: '新增指导学生' })
  await expect(mobileDialog).toBeVisible()
  await mobileDialog.getByLabel('学号').fill('22000002')
  await mobileDialog.getByLabel('姓名').fill('移动端学生')
  await mobileDialog.getByLabel('入学年级').fill('2024')
  await mobileDialog.getByLabel('姓名').press('Tab')
  await expectNoPageOverflow(page)
  const dialogBox = await mobileDialog.boundingBox()
  expect(dialogBox).not.toBeNull()
  expect(dialogBox!.x).toBeGreaterThanOrEqual(0)
  expect(dialogBox!.x + dialogBox!.width).toBeLessThanOrEqual(375)
  await page.screenshot({
    path: testInfo.outputPath('teacher-create-student-375.png'),
    animations: 'disabled',
  })
  await mobileDialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(mobileDialog).toHaveCount(0)
  await expectNoPageOverflow(page)
  expect(unexpectedRequests).toEqual([])
  expect(errors).toEqual([])
})

test('教师从名单进入导入页面，预检成功后才能确认导入', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const errors: string[] = []
  const unexpectedRequests: string[] = []
  const importActions: string[] = []
  const uploadedFields: string[][] = []
  const importErrors = [
    {
      row: 3,
      username: '22000002',
      message: '姓名不能为空，请填写学生真实姓名后重新预检。',
    },
    {
      row: 3,
      username: '22000002',
      message: '专业不在本学院启用目录中，请选择正确专业后重新预检。',
    },
  ]
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    if (path === '/api/auth/me') data = teacher
    else if (path === '/api/auth/csrf')
      data = { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/teacher/students' && request.method() === 'GET')
      data = { items: [], total: 0, page: 1, size: 10 }
    else if (
      request.method() === 'POST' &&
      (path === '/api/teacher/students/imports/preview' ||
        path === '/api/teacher/students/imports/execute')
    ) {
      const action = path.endsWith('/preview') ? 'preview' : 'execute'
      importActions.push(action)
      uploadedFields.push(
        Array.from(
          (request.postData() ?? '').matchAll(/Content-Disposition: form-data; name="([^"]+)"/g),
          (match) => match[1],
        ),
      )
      data = {
        totalRows: 2,
        validRows: 1,
        successCount: action === 'execute' ? 1 : 0,
        skippedCount: 0,
        failedCount: 1,
        errors: action === 'execute' ? [...importErrors].reverse() : importErrors,
      }
    } else {
      unexpectedRequests.push(request.method() + ' ' + path)
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'teacher-browser-fixture',
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

  await page.goto('/teacher/students')
  await page.getByRole('button', { name: /导入名单/ }).click()
  await expect(page).toHaveURL(/\/teacher\/students\/import$/)
  await expect(page.getByRole('heading', { name: '导入指导名单' })).toBeVisible()
  await expectNoPageOverflow(page)
  await expect(page.getByRole('button', { name: /预检文件/ })).toBeDisabled()
  await expect(page.getByRole('button', { name: '确认导入有效账号' })).toHaveCount(0)
  await page.locator('input[type="file"]').setInputFiles({
    name: 'students.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: Buffer.from('controlled API fixture: file parsing is verified by backend tests'),
  })
  await page.getByRole('button', { name: /预检文件/ }).click()
  await expect(page.getByText('预检结果', { exact: true })).toBeVisible()
  const errorRows = page.getByRole('row', { name: /22000002/ })
  await expect(errorRows).toHaveCount(2)
  await expect(errorRows).toContainText(['姓名不能为空', '专业不在本学院启用目录中'])
  await expectNoPageOverflow(page)
  await page.screenshot({
    path: testInfo.outputPath('teacher-import-preview-1440.png'),
    animations: 'disabled',
  })
  expect(importActions).toEqual(['preview'])
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(page.getByText('预检结果', { exact: true })).toBeVisible()
  await expect(errorRows).toHaveCount(2)
  await expect(errorRows).toContainText(['姓名不能为空', '专业不在本学院启用目录中'])
  await expect(page.getByRole('button', { name: '下载错误清单', exact: true })).toBeVisible()
  await expectNoPageOverflow(page)
  await page
    .getByText('预检结果', { exact: true })
    .evaluate((element) => element.scrollIntoView({ block: 'start' }))
  await page.screenshot({
    path: testInfo.outputPath('teacher-import-preview-errors-375.png'),
    animations: 'disabled',
  })
  await page.getByRole('button', { name: '确认导入有效账号', exact: true }).click()
  const successMessage = page.getByText('名单导入已完成，学生可使用初始密码直接登录。')
  await expect(successMessage).toBeVisible()
  const contrast = await successMessage.evaluate((element) => {
    const style = getComputedStyle(element)
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 1
    const context = canvas.getContext('2d')
    if (!context) throw new Error('无法读取完成提示的实际颜色')
    const backgrounds: string[] = []
    for (let current: Element | null = element; current; current = current.parentElement) {
      backgrounds.unshift(getComputedStyle(current).backgroundColor)
    }
    context.fillStyle = '#fff'
    context.fillRect(0, 0, 1, 1)
    for (const color of backgrounds) {
      context.fillStyle = color
      context.fillRect(0, 0, 1, 1)
    }
    const background = [...context.getImageData(0, 0, 1, 1).data].slice(0, 3)
    context.fillStyle = style.color
    context.fillRect(0, 0, 1, 1)
    const foreground = [...context.getImageData(0, 0, 1, 1).data].slice(0, 3)
    const luminance = (rgb: number[]) =>
      rgb
        .map((value) => value / 255)
        .map((value) => (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4))
        .reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0)
    const light = luminance(background)
    const dark = luminance(foreground)
    return {
      color: style.color,
      fontSize: style.fontSize,
      background,
      ratio: (Math.max(light, dark) + 0.05) / (Math.min(light, dark) + 0.05),
    }
  })
  const contrastPath = testInfo.outputPath('import-complete-contrast.json')
  await writeFile(contrastPath, JSON.stringify(contrast, null, 2))
  await testInfo.attach('import-complete-contrast.json', {
    path: contrastPath,
    contentType: 'application/json',
  })
  expect(contrast.ratio, JSON.stringify(contrast)).toBeGreaterThanOrEqual(4.5)
  await expect(page.getByText('导入完成', { exact: true })).toBeVisible()
  await expect(errorRows).toHaveCount(2)
  await expect(errorRows).toContainText(['专业不在本学院启用目录中', '姓名不能为空'])
  await expectNoPageOverflow(page)
  await page
    .getByText('导入完成', { exact: true })
    .evaluate((element) => element.scrollIntoView({ block: 'start' }))
  await page.screenshot({
    path: testInfo.outputPath('teacher-import-complete-375.png'),
    animations: 'disabled',
  })
  expect(importActions).toEqual(['preview', 'execute'])
  expect(uploadedFields).toEqual([['file'], ['file']])
  expect(unexpectedRequests).toEqual([])
  expect(errors).toEqual([])
})
import { writeFile } from 'node:fs/promises'
