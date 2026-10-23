import { expect, test } from '@playwright/test'

for (const width of [375, 1440]) {
  test(`登录页在 ${width}px 下显示真实表单并支持键盘操作`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))

    await page.goto('/login')

    await expect(page.getByRole('heading', { name: '登录账号', exact: true })).toBeVisible()
    const username = page.getByRole('textbox', { name: '账号', exact: true })
    const password = page.getByLabel('密码', { exact: true })
    const submit = page.getByRole('button', { name: '登录', exact: true })
    await expect(submit).toBeDisabled()
    await username.focus()
    await expect(username).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(password).toBeFocused()
    await expect(password).toHaveAttribute('type', 'password')
    await expect(username).toHaveAttribute('autocomplete', 'username')
    await expect(password).toHaveAttribute('autocomplete', 'current-password')
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true)
    expect(errors).toEqual([])
  })
}

test('未登录访问论文页面时不显示业务数据', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const session = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/auth/me',
  )

  await page.goto('/student/submissions')
  const response = await session
  if (response.status() === 401) {
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('heading', { name: '登录账号', exact: true })).toBeVisible()
  } else {
    expect(
      response.status(),
      '会话服务异常时必须显示错误，不能显示论文数据',
    ).toBeGreaterThanOrEqual(500)
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('button', { name: '重试', exact: true })).toBeVisible()
  }
  await expect(page.getByRole('button', { name: '提交审核', exact: true })).toHaveCount(0)
  await expect(page.getByRole('table')).toHaveCount(0)
  expect(errors).toEqual([])
})
