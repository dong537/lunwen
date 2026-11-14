import { writeFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

function envelope(data: unknown) {
  return { data, error: null, requestId: 'browser-fixture' }
}

const school = {
  schoolName: '山东石油化工学院',
  colleges: [
    { code: 'INFORMATION_ENGINEERING', name: '信息科学与工程学院' },
    { code: 'EDUCATION_ARTS', name: '教育与艺术学院' },
    { code: 'ECONOMICS_MANAGEMENT', name: '经济管理学院' },
  ],
}

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
  mustChangePassword: false,
}

for (const width of [1440, 375]) {
  test(`校级管理员按学院筛选账号，并看到关联账号的停用结果（${width}px）`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push(message.text())
    })
    const requestedColleges: string[] = []
    let adminCollege: string | null = null
    let deleteCalls = 0
    let resetCalls = 0
    let studentActive = true
    const students = [
      {
        id: '20',
        username: '22000001',
        name: '张三',
        role: 'STUDENT',
        major: '计算机科学与技术',
        teacherId: null,
        teacherName: null,
        collegeCode: 'INFORMATION_ENGINEERING',
        collegeName: '信息科学与工程学院',
        active: true,
        mustChangePassword: false,
      },
      {
        id: '21',
        username: '22000002',
        name: '李四',
        role: 'STUDENT',
        major: '学前教育',
        teacherId: null,
        teacherName: null,
        collegeCode: 'EDUCATION_ARTS',
        collegeName: '教育与艺术学院',
        active: true,
        mustChangePassword: false,
      },
    ]
    await page.route('**/api/**', async (route) => {
      const url = new URL(route.request().url())
      const path = url.pathname
      if (!path.startsWith('/api/')) {
        await route.continue()
        return
      }
      let data: unknown
      if (path === '/api/auth/me')
        data = {
          ...admin,
          collegeCode: adminCollege,
          collegeName: adminCollege === 'EDUCATION_ARTS' ? '教育与艺术学院' : null,
        }
      else if (path === '/api/auth/csrf')
        data = { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
      else if (path === '/api/admin/catalog') data = school
      else if (path === '/api/admin/users' && route.request().method() === 'GET') {
        const college = url.searchParams.get('collegeCode') ?? ''
        requestedColleges.push(college)
        const selected = students
          .map((student) => (student.id === '20' ? { ...student, active: studentActive } : student))
          .filter((student) => !college || student.collegeCode === college)
        data = { items: selected, total: selected.length, page: 1, size: 10 }
      } else if (path === '/api/admin/users/20' && route.request().method() === 'DELETE') {
        deleteCalls += 1
        studentActive = false
        data = {
          outcome: 'DEACTIVATED',
          user: { ...students[0], active: false },
          message: '账号已停用，历史记录保留。',
        }
      } else if (
        path === '/api/admin/users/20/reset-password' &&
        route.request().method() === 'POST'
      ) {
        resetCalls += 1
        data = { ...students[0], mustChangePassword: false }
      } else {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({
            data: null,
            error: { code: 'NOT_FOUND', message: '测试接口不存在' },
            requestId: 'browser-fixture',
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
    await expect(page.getByText('张三', { exact: true })).toBeVisible()
    await expect(page.getByText('李四', { exact: true })).toBeVisible()
    const layout = await page.evaluate(() => ({
      viewportWidth: window.innerWidth,
      documentWidth: document.documentElement.scrollWidth,
      controls: Array.from(
        document.querySelectorAll(
          '.ant-input-search, .ant-layout-content, .ant-card, .ant-table-content',
        ),
      ).map((element) => {
        const bounds = element.getBoundingClientRect()
        return {
          className: element.className,
          x: bounds.x,
          right: bounds.right,
          width: bounds.width,
        }
      }),
    }))
    await testInfo.attach('admin-layout.json', {
      body: JSON.stringify(layout, null, 2),
      contentType: 'application/json',
    })
    await writeFile(testInfo.outputPath('admin-layout.json'), JSON.stringify(layout, null, 2))
    await page.screenshot({ path: testInfo.outputPath('admin-initial.png'), fullPage: true })
    expect
      .soft(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
        JSON.stringify(layout),
      )
      .toBe(true)
    await page.getByLabel('按学院筛选').click()
    await page.getByText('信息科学与工程学院', { exact: true }).last().click()
    await expect(page.getByText('李四', { exact: true })).toHaveCount(0)
    expect(requestedColleges).toContain('INFORMATION_ENGINEERING')

    await page.getByRole('button', { name: '重置张三的密码' }).click()
    const resetDialog = page.getByRole('dialog', { name: '重置密码' })
    await expect(resetDialog).toContainText('下次可直接使用初始密码登录')
    await expect(resetDialog).not.toContainText('必须重新设置密码')
    await resetDialog.getByRole('button', { name: '确认重置' }).click()
    await expect(
      page.getByText('已将 张三（22000001）的密码重置为 123456，下次可直接使用初始密码登录。'),
    ).toBeVisible()
    expect(resetCalls).toBe(1)

    const deleteButton = page.getByRole('button', { name: '删除张三' })
    await page.getByRole('button', { name: '重置张三的密码' }).focus()
    await page.keyboard.press('Tab')
    await expect(deleteButton).toBeFocused()
    await page.keyboard.press('Enter')
    const dialog = page.getByRole('dialog', { name: '删除账号' })
    await expect(dialog).toBeVisible()
    await expect
      .poll(() => dialog.evaluate((element) => element.contains(document.activeElement)))
      .toBe(true)
    await expect(dialog).toBeInViewport()
    await expect
      .poll(() =>
        dialog.evaluate((element) =>
          element
            .getAnimations({ subtree: true })
            .every((animation) => animation.playState !== 'running'),
        ),
      )
      .toBe(true)
    await page.screenshot({
      path: testInfo.outputPath('admin-delete-dialog.png'),
      animations: 'disabled',
    })
    await dialog.screenshot({ path: testInfo.outputPath('admin-delete-dialog-panel.png') })
    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
    await expect(deleteButton).toBeFocused()
    expect(deleteCalls).toBe(0)
    if (width === 1440) {
      await page.evaluate(() => {
        document.documentElement.style.zoom = '2'
      })
      expect
        .soft(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
        .toBe(true)
      await page.screenshot({
        path: testInfo.outputPath('admin-200-percent-css-zoom.png'),
        fullPage: true,
      })
      await page.evaluate(() => {
        document.documentElement.style.zoom = ''
      })
    }
    await deleteButton.press('Enter')
    await expect(page.getByText('无关联记录时永久删除；如有关联学生或论文')).toBeVisible()
    await page.getByRole('button', { name: '确认处理' }).click()
    await expect(page.getByText('账号已停用，历史记录保留。')).toBeVisible()
    await expect(page.getByRole('row', { name: /22000001/ }).getByText('停用')).toBeVisible()
    expect(deleteCalls).toBe(1)
    adminCollege = 'EDUCATION_ARTS'
    await page.evaluate(() =>
      window.dispatchEvent(new CustomEvent('platform:auth', { detail: { status: 403 } })),
    )
    await expect(page.getByText('李四', { exact: true })).toBeVisible()
    await expect(page.getByText('张三', { exact: true })).toHaveCount(0)
    expect(requestedColleges).toContain('EDUCATION_ARTS')
    expect(errors).toEqual([])
  })

  test(`学生端不展示论文规则入口及旧路由（${width}px）`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
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
      if (path === '/api/auth/me') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            envelope({
              ...admin,
              id: '2',
              username: '22000001',
              name: '学生甲',
              role: 'STUDENT',
              collegeCode: 'INFORMATION_ENGINEERING',
              collegeName: '信息科学与工程学院',
            }),
          ),
        })
        return
      }
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'browser-fixture',
        }),
      })
    })
    await page.goto('/student/rules')
    await expect(page.getByRole('heading', { name: '页面不存在' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '论文规则' })).toHaveCount(0)
    await expect(page.getByRole('menuitem', { name: '我的论文' })).toBeVisible()
    await expect(page.getByRole('navigation', { name: '论文规则章节' })).toHaveCount(0)
    await expect(page.getByRole('heading', { name: '论文规则' })).toHaveCount(0)
    expect(errors).toEqual([])
  })
}
