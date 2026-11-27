import { expect, test } from '@playwright/test'

test('管理员按学院预检并导入账号，同一行的多个错误均保留且没有控制台错误', async ({
  page,
}, testInfo) => {
  const errors: string[] = []
  const unexpectedRequests: string[] = []
  const imports: { action: string; fields: string[]; body: string }[] = []
  const rowErrors = [
    { row: 3, username: '22000002', message: '姓名不能为空，请补全姓名。' },
    { row: 3, username: '22000002', message: '专业不在本学院启用目录中，请更正专业。' },
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
    if (path === '/api/auth/me') {
      data = {
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
        mustChangePassword: false,
      }
    } else if (path === '/api/auth/csrf') {
      data = { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
    } else if (path === '/api/admin/catalog') {
      data = {
        schoolName: '山东石油化工学院',
        colleges: [
          { code: 'INFORMATION_ENGINEERING', name: '信息科学与工程学院' },
          { code: 'EDUCATION_ARTS', name: '教育与艺术学院' },
        ],
      }
    } else if (
      request.method() === 'POST' &&
      (path === '/api/admin/imports/preview' || path === '/api/admin/imports/execute')
    ) {
      const action = path.endsWith('/preview') ? 'preview' : 'execute'
      const body = request.postData() ?? ''
      imports.push({
        action,
        body,
        fields: Array.from(
          body.matchAll(/Content-Disposition: form-data; name="([^"]+)"/g),
          (match) => match[1],
        ),
      })
      data = {
        totalRows: 2,
        validRows: 1,
        successCount: action === 'execute' ? 1 : 0,
        skippedCount: 0,
        failedCount: 1,
        errors: action === 'execute' ? [...rowErrors].reverse() : rowErrors,
      }
    } else {
      unexpectedRequests.push(request.method() + ' ' + path)
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'admin-import-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data, error: null, requestId: 'admin-import-fixture' }),
    })
  })

  await page.goto('/admin/imports')
  await expect(page.getByRole('heading', { name: '批量导入账号' })).toBeVisible()
  await expect(page.getByText('专业和指导教师工号均为必填。', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: /预检文件/ })).toBeDisabled()
  await page.getByRole('combobox', { name: '导入所属学院' }).click()
  await page.locator('.ant-select-item-option[title="信息科学与工程学院"]').click()
  await page.locator('input[type="file"]').setInputFiles({
    name: 'accounts.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: Buffer.from('controlled API fixture: XLSX parsing is verified by backend tests'),
  })
  await expect(page.getByRole('button', { name: '确认导入有效账号' })).toHaveCount(0)
  await page.getByRole('button', { name: /预检文件/ }).click()
  await expect(page.getByText('预检结果', { exact: true })).toBeVisible()
  const errorRows = page.getByRole('row', { name: /22000002/ })
  await expect(errorRows).toHaveCount(2)
  await expect(errorRows).toContainText(['姓名不能为空', '专业不在本学院启用目录中'])
  await page.screenshot({
    path: testInfo.outputPath('admin-import-preview.png'),
    animations: 'disabled',
  })
  expect(imports.map((item) => item.action)).toEqual(['preview'])
  await page.getByRole('button', { name: '确认导入有效账号', exact: true }).click()
  const successMessage = page.getByText('本次导入已完成。新账号初始密码为 123456，学生可直接登录。')
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
  await expect(errorRows).toHaveCount(2)
  await expect(errorRows).toContainText(['专业不在本学院启用目录中', '姓名不能为空'])
  await page.screenshot({
    path: testInfo.outputPath('admin-import-complete.png'),
    animations: 'disabled',
  })
  expect(imports.map((item) => item.action)).toEqual(['preview', 'execute'])
  for (const request of imports) {
    expect(request.fields).toEqual(['file', 'role', 'collegeCode'])
    expect(request.body).toContain('STUDENT')
    expect(request.body).toContain('INFORMATION_ENGINEERING')
  }
  expect(unexpectedRequests).toEqual([])
  expect(errors).toEqual([])
})
import { writeFile } from 'node:fs/promises'
